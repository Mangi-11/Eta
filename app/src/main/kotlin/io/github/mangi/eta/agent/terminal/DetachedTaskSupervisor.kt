package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONArray
import io.github.mangi.eta.agent.terminal.linux.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.linux.ProotCommandBuilder
import io.github.mangi.eta.agent.terminal.linux.SharedFolderMount

/**
 * 守护任务（detached task）宿主：启动脱离任何命令会话进程组的长驻进程，并托管其生命周期。
 *
 * 与普通终端命令的差别在于回收语义：托管 shell 退出前会清理同组进程，守护任务通过
 * setsid 自立会话脱离这张回收网；输出重定向到工作区日志文件而非内存，任务记录落盘，
 * App 重启后按 pid + ownership token 认领仍存活的进程。
 *
 * 手机重启后任务全部失效；App 被强制停止时 user identity 任务会被系统连带终止，
 * root identity 任务不受影响。
 */
internal class DetachedTaskSupervisor(
    private val logger: AgentLogger,
    private val recordsFile: File,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val daemonDir: String = DEFAULT_DAEMON_DIR,
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
    private val rootAvailable: () -> Boolean = { TerminalRuntime.rootAvailable },
    private val oneShotSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(rootAvailable = rootAvailable),
    private val acquireUserLease: (String, () -> Unit) -> Boolean = TerminalRuntime::acquireUserTask,
    private val releaseUserLease: (String) -> Unit = TerminalRuntime::releaseUserTask,
) {
    companion object {
        const val DEFAULT_DAEMON_DIR = "/data/local/tmp/eta/daemon"
        const val LINUX_DAEMON_DIR = "/workspace/daemon"
        const val MAX_TASKS = 8
        const val MAX_RETAINED_RECORDS = 32
        const val MAX_LOG_READ_BYTES = 64 * 1024

        // 进程内允许 AI 侧与 UI 侧各持一个实例；记录文件的读-改-写必须经同一把锁串行。
        private val RECORDS_LOCK = Any()
        private val COMMAND_PROCESSES = java.util.concurrent.ConcurrentHashMap<String, CommandProcess>()
        private val USER_WAITERS = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        /** AI 工具侧与 UI 侧共用同一份记录文件，路径只能在这里定义一次。 */
        fun defaultRecordsFile(context: Context): File =
            File(context.filesDir, "terminal-daemons.json")
    }

    /** command/cwd/identity/environment 必须已由调用方归一化。 */
    fun start(
        command: String,
        cwd: String,
        identity: String,
        environment: TerminalEnvironment,
        commandTask: Boolean = false,
        description: String = "",
        timeoutMs: Long = 0,
    ): DaemonStartResult {
        if (identity != "root" && identity != "user") return DaemonStartResult.Failed("INVALID_ARGUMENT", "执行身份无效")
        if (identity == "root" && !rootAvailable()) return DaemonStartResult.Failed("ROOT_REQUIRED", "Root 授权不可用")
        if (identity == "user" && environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath(environment)) != LinuxExecutionBackend.PROOT) {
            return DaemonStartResult.Failed("LINUX_ENVIRONMENT_REQUIRES_ROOT", "所选 Linux 环境需要 Root")
        }
        if (identity == "root" && environment.isLinux && LinuxEnvironmentPaths.backendOf(rootfsPath(environment)) == LinuxExecutionBackend.PROOT) {
            return DaemonStartResult.Failed("INVALID_IDENTITY", "免 Root Linux 使用普通应用身份")
        }
        if (list().count { it.running } >= MAX_TASKS) {
            return DaemonStartResult.Failed("MAX_TASKS_REACHED", "守护任务数量已达上限 $MAX_TASKS，请先停止不用的任务")
        }
        val id = (if (commandTask) "job_" else "dm_") + UUID.randomUUID().toString().take(8)
        val token = UUID.randomUUID().toString().replace("-", "")
        if (commandTask) return startCommand(id, command, cwd, identity, environment, description, timeoutMs)
        if (identity == "user") return startUserDaemon(id, token, command, cwd, environment)
        val wireDir = wireDaemonDir(environment)
        val wirePidFile = "$wireDir/$id.pid"
        val wireLogFile = "$wireDir/$id.log"
        // 内层 sh 先落 pidfile 再 exec 目标命令，PID 在 exec 前后不变。
        val innerScript = "echo \$\$ > ${shellQuote(wirePidFile)}; " +
            "export $ETA_PROCESS_OWNER_ENV=${shellQuote(token)}; " +
            "exec sh -c ${shellQuote(command)} >> ${shellQuote(wireLogFile)} 2>&1"
        val launcherScript = buildString {
            appendLine("mkdir -p ${shellQuote(wireDir)} || exit 1")
            appendLine("rm -f ${shellQuote(wirePidFile)}")
            appendLine("cd ${shellQuote(cwd)} || exit 1")
            // 两个分支都必须后台化：setsid 只是脱离会话，调用方仍会前台等待长驻命令结束。
            appendLine("if command -v setsid >/dev/null 2>&1; then")
            appendLine("  setsid sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("else")
            appendLine("  sh -c ${shellQuote(innerScript)} < /dev/null &")
            appendLine("fi")
            appendLine("i=0")
            appendLine("while [ \$i -lt 30 ]; do")
            appendLine("  [ -s ${shellQuote(wirePidFile)} ] && break")
            appendLine("  i=\$((i+1))")
            appendLine("  sleep 0.1")
            appendLine("done")
            append("cat ${shellQuote(wirePidFile)} 2>/dev/null")
        }
        // 启动器必须裸跑：托管壳的 wait 会卡住已后台化的子进程，退出前的同组清理也会杀死它们。
        val result = launchRaw(identity, environment, launcherScript, timeoutSeconds = 10)
        val outputText = result.output.decodeToString().trim()
        val pid = outputText.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.toLongOrNull()
        if (result.exitCode != 0 || pid == null || pid <= 1) {
            val message = outputText.ifBlank { "exit=${result.exitCode}" }
            logger.warn(
                "Agent terminal daemon action=start outcome=failed environment=${environment.wireName} " +
                    "exitCode=${result.exitCode} errorChars=${message.length}"
            )
            return DaemonStartResult.Failed("DAEMON_START_FAILED", message)
        }
        val task = DetachedTask(
            id = id,
            pid = pid,
            token = token,
            command = command,
            cwd = cwd,
            identity = identity,
            environment = environment,
            logPath = "$wireDir/$id.log",
            startedAt = System.currentTimeMillis(),
            commandTask = commandTask,
            description = description,
            timeoutMs = timeoutMs,
        )
        if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
            stopTaskProcess(task)
            return DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "无法保存后台任务，请检查内部存储")
        }
        logger.info(
            "Agent terminal daemon action=start outcome=started taskId=$id " +
                "environment=${environment.wireName} identity=$identity commandChars=${command.length}"
        )
        return DaemonStartResult.Started(task)
    }

    /** 巡检全部记录：活着的重新认领，死掉的保留记录供查看日志，并清理超额的已退出记录。 */
    fun list(): List<DetachedTaskStatus> {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        if (tasks.isEmpty()) return emptyList()
        // 不同身份分组巡检：/proc/<pid>/environ 只有进程属主与 root 可读，用任务自身身份探测最稳。
        val aliveById = mutableMapOf<String, Boolean>()
        tasks.filter { it.completedAt == null }.groupBy { it.identity }.forEach { (identity, group) ->
            if (identity == "root" && !rootAvailable()) return@forEach
            val probeScript = aliveCheckFunction() + "\n" + group.joinToString("\n") { task ->
                "if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; " +
                    "then echo '${task.id} 1'; else echo '${task.id} 0'; fi"
            }
            val result = runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = probeScript,
                timeoutSeconds = 15,
            )
            if (result.exitCode == 0) {
                result.output.decodeToString().lineSequence().forEach { line ->
                    val parts = line.trim().split(" ")
                    if (parts.size == 2) aliveById[parts[0]] = parts[1] == "1"
                }
            } else {
                logger.warn(
                    "Agent terminal daemon action=list outcome=probe_failed identity=$identity exitCode=${result.exitCode}"
                )
            }
        }
        // 探测失败的任务保守视为仍在运行，不误报死亡；确认死亡的才允许被 prune 清掉。
        val statuses = tasks.map { task ->
            if (task.completedAt != null) return@map DetachedTaskStatus(task, false)
            var running = aliveById[task.id] ?: true
            if (!task.commandTask && task.identity == "user" && aliveById[task.id] == true && !adoptUserTask(task)) running = false
            if (task.commandTask && !running) {
                val exitCode = readCommandExit(task)
                DetachedTaskStatus(completeCommand(task.id, exitCode, if (exitCode == null) "interrupted" else null) ?: task, false)
            } else DetachedTaskStatus(task, running)
        }
        if (aliveById.isNotEmpty()) {
            pruneExitedRecords(statuses)
        }
        return statuses
    }

    fun readLogs(id: String, maxBytes: Int = MAX_LOG_READ_BYTES): DaemonLogsResult {
        val task = synchronized(RECORDS_LOCK) { loadTasksLocked() }.firstOrNull { it.id == id }
            ?: return DaemonLogsResult(ok = false, code = "TASK_NOT_FOUND", message = "未找到守护任务：$id")
        if (task.identity == "root" && !rootAvailable()) return DaemonLogsResult(ok = false, code = "ROOT_REQUIRED", message = "Root 授权不可用")
        val limit = maxBytes.coerceIn(1_024, MAX_LOG_READ_BYTES)
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = "tail -c $limit ${shellQuote(hostDaemonPath(task, task.logPath))} 2>/dev/null",
            timeoutSeconds = 15,
        )
        if (result.exitCode != 0) {
            return DaemonLogsResult(ok = false, code = "LOGS_UNAVAILABLE", message = "日志不可用：exit=${result.exitCode}")
        }
        return DaemonLogsResult(
            ok = true,
            text = result.output.decodeToString(),
            truncated = result.output.size >= limit,
        )
    }

    fun findTask(id: String): DetachedTask? = synchronized(RECORDS_LOCK) { loadTasksLocked().firstOrNull { it.id == id } }

    private class CommandProcess(val process: Process, val supervisor: ShellProcessSupervisor) {
        @Volatile var stopReason: String? = null
    }

    private fun startCommand(id: String, command: String, cwd: String, identity: String,
        environment: TerminalEnvironment, description: String, timeoutMs: Long): DaemonStartResult {
        val workspace = if (identity == "user") TerminalRuntime.userWorkspacePath else TerminalRuntime.ROOT_WORKSPACE_PATH
        val wireDir = when {
            environment.isLinux -> LINUX_DAEMON_DIR
            identity == "user" && daemonDir == DEFAULT_DAEMON_DIR -> "$workspace/daemon"
            else -> daemonDir
        }
        val logPath = "$wireDir/$id.log"
        val script = "mkdir -p ${shellQuote(wireDir)} && { cd ${shellQuote(cwd)} && " +
            commandPayload(command, "$wireDir/$id.exit") + "; } > ${shellQuote(logPath)} 2>&1"
        val process = oneShotSupervisor.startShellProcess(identity, script, true, environment,
            linuxRootfsPath = rootfsPath(environment), linuxSharedMounts = linuxSharedMountsProvider())
            ?: return DaemonStartResult.Failed("PROCESS_START_FAILED", "无法启动后台命令")
        val ownership = oneShotSupervisor.ownershipOf(process)
        if (ownership == null) {
            oneShotSupervisor.terminateAndReap(process)
            oneShotSupervisor.unregisterProcess(process)
            return DaemonStartResult.Failed("PROCESS_START_FAILED", "后台命令未完成进程归属确认")
        }
        val task = DetachedTask(id, ownership.pid, ownership.token, command, cwd, identity, environment,
            logPath, System.currentTimeMillis(), LinuxEnvironmentPaths.backendOf(rootfsPath(environment)), workspace,
            commandTask = true, description = description, timeoutMs = timeoutMs, isolatedGroup = ownership.isolatedGroup)
        if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
            oneShotSupervisor.terminateAndReap(process)
            oneShotSupervisor.unregisterProcess(process)
            return DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "无法保存后台命令")
        }
        val running = CommandProcess(process, oneShotSupervisor)
        COMMAND_PROCESSES[id] = running
        process.outputStream.close()
        val reader = thread(name = "eta-command-output", isDaemon = true) {
            process.inputStream.use { ByteArrayOutputCollector().readFrom(it, 8_192) }
        }
        thread(name = "eta-command-completion", isDaemon = true) {
            try {
                val exitCode = process.waitFor()
                reader.join(500)
                completeCommand(id, if (running.stopReason == null) exitCode else null, running.stopReason)
            } finally {
                oneShotSupervisor.retireExitedProcess(process)
                COMMAND_PROCESSES.remove(id, running)
            }
        }
        return DaemonStartResult.Started(task)
    }

    fun stopCommand(id: String, reason: String = "stopped"): Boolean {
        val task = findTask(id)?.takeIf { it.commandTask } ?: return false
        if (task.completedAt != null) return true
        if (task.identity == "root" && !rootAvailable()) return false
        COMMAND_PROCESSES[id]?.stopReason = reason
        if (!stopTaskProcess(task, removeFiles = false)) return false
        return completeCommand(id, null, reason) != null
    }

    fun markCompletionNotified(id: String): Boolean = synchronized(RECORDS_LOCK) {
        val tasks = loadTasksLocked()
        val index = tasks.indexOfFirst { it.id == id && it.completedAt != null }
        if (index < 0) return@synchronized false
        tasks[index] = tasks[index].copy(completionNotified = true)
        saveTasksLocked(tasks)
    }

    private fun completeCommand(id: String, exitCode: Int?, reason: String?): DetachedTask? = synchronized(RECORDS_LOCK) {
        val tasks = loadTasksLocked()
        val index = tasks.indexOfFirst { it.id == id }
        if (index < 0) return@synchronized null
        val existing = tasks[index]
        if (existing.completedAt != null) return@synchronized existing
        val completed = existing.copy(completedAt = System.currentTimeMillis(), exitCode = exitCode, endReason = reason)
        tasks[index] = completed
        if (saveTasksLocked(tasks)) completed else null
    }

    private fun readCommandExit(task: DetachedTask): Int? {
        val path = hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".exit")
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = "head -c 32 ${shellQuote(path)} 2>/dev/null",
            timeoutSeconds = 5,
            maxOutputBytes = 32,
        )
        return if (result.exitCode == 0) result.output.decodeToString().trim().toIntOrNull() else null
    }

    private fun commandPayload(command: String, exitPath: String): String {
        val payload = "$command\neta_command_status=${'$'}?\nwait\nexit ${'$'}eta_command_status"
        // 命令自身可调用 exit；在子壳执行后由宿主壳原子记录退出码，App 重启后仍可确认结果。
        return "{ sh -c ${shellQuote(payload)}; eta_status=$?; " +
            "printf '%s\\n' \"${'$'}eta_status\" > ${shellQuote("$exitPath.tmp")} && " +
            "mv ${shellQuote("$exitPath.tmp")} ${shellQuote(exitPath)}; exit \"${'$'}eta_status\"; }"
    }

    /** 停止并删除记录与日志；进程已退出时等价于清理记录。 */
    fun stop(id: String): Boolean {
        val tasks = synchronized(RECORDS_LOCK) { loadTasksLocked() }
        val task = tasks.firstOrNull { it.id == id } ?: return false
        if (task.commandTask && task.completedAt == null) return stopCommand(id)
        if (task.identity == "root" && !rootAvailable()) return false
        if (!stopTaskProcess(task)) return false
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id == id })
        }
        logger.info(
            "Agent terminal daemon action=stop outcome=stopped taskId=$id " +
                "environment=${task.environment.wireName}"
        )
        if (task.identity == "user") releaseUserLease("daemon:$id")
        return true
    }

    private fun stopTaskProcess(task: DetachedTask, removeFiles: Boolean = true): Boolean {
        COMMAND_PROCESSES[task.id]?.let { live ->
            live.supervisor.terminateAndReap(live.process)
            if (!removeFiles) return true
        }
        val stopScript = buildString {
            appendLine(aliveCheckFunction())
            appendLine("if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            // setsid 路径下 pgid==pid，按组一次收整棵树；无 setsid 的退化环境只杀主进程。
            if (task.isolatedGroup != null) {
                appendLine("  kill -TERM ${if (task.isolatedGroup) "-" else ""}${task.pid} 2>/dev/null")
            } else appendLine("  if [ -d /proc ]; then kill -TERM -${task.pid} 2>/dev/null; else kill -TERM ${task.pid} 2>/dev/null; fi")
            appendLine("  sleep 1")
            appendLine("  if eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then")
            if (task.isolatedGroup != null) {
                appendLine("    kill -9 ${if (task.isolatedGroup) "-" else ""}${task.pid} 2>/dev/null")
            } else appendLine("    if [ -d /proc ]; then kill -9 -${task.pid} 2>/dev/null; else kill -9 ${task.pid} 2>/dev/null; fi")
            appendLine("  fi")
            appendLine("  sleep 0.1")
            appendLine("  eta_alive ${task.pid} ${shellQuote(ownerProof(task))} && exit 81")
            appendLine("fi")
            if (removeFiles) append("rm -f ${shellQuote(hostDaemonPath(task, task.logPath))} ${shellQuote(hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".pid"))} ${shellQuote(hostDaemonPath(task, task.logPath.removeSuffix(".log") + ".exit"))}")
        }
        val result = runOneShotShell(
            processSupervisor = oneShotSupervisor,
            identity = task.identity,
            command = stopScript,
            timeoutSeconds = 10,
        )
        return result.exitCode == 0
    }

    /** 存活判定校验 ownership token：PID 被系统复用时不会把无关进程当作本任务。无 /proc 的环境退化为存在性探测。 */
    private fun aliveCheckFunction(): String =
        "eta_alive() { " +
            "if [ -d /proc ]; then " +
                "[ -d /proc/\$1 ] && " +
                "tr '\\000' '\\n' < /proc/\$1/environ 2>/dev/null | grep -Fqx \"\$2\"; " +
            "else kill -0 \"\$1\" 2>/dev/null; fi; " +
            "}"

    private fun ownerProof(task: DetachedTask): String = "$ETA_PROCESS_OWNER_ENV=${task.token}"

    /** 普通守护任务保留完整宿主壳和 tracer；guest 内不能再次脱离 PRoot 的生命周期。 */
    private fun startUserDaemon(id: String, token: String, command: String, cwd: String, environment: TerminalEnvironment): DaemonStartResult {
        val workspace = if (environment.isLinux || daemonDir == DEFAULT_DAEMON_DIR) TerminalRuntime.userWorkspacePath else File(daemonDir).parent!!
        val hostDir = if (environment.isLinux || daemonDir == DEFAULT_DAEMON_DIR) File(workspace, "daemon") else File(daemonDir)
        if (!hostDir.mkdirs() && !hostDir.isDirectory) return DaemonStartResult.Failed("WORKSPACE_UNAVAILABLE", "工作目录不可访问")
        val pidFile = File(hostDir, "$id.pid")
        val logFile = File(hostDir, "$id.log")
        val commandBody = "exec sh -c ${shellQuote(command)}"
        val payload = if (environment.isLinux) {
            val rootfs = rootfsPath(environment)
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs)) return DaemonStartResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "Linux 环境尚未安装")
            if (!ProotCommandBuilder.available()) return DaemonStartResult.Failed("PROOT_UNAVAILABLE", "当前设备没有可用的免 Root Linux 运行组件")
            ProotCommandBuilder.payload(requireNotNull(rootfs), "cd ${shellQuote(cwd)} && $commandBody", linuxSharedMountsProvider(), workspace = workspace)
        } else "cd ${shellQuote(cwd)} && $commandBody"
        val lease = "daemon:$id"
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        val launchLock = Any()
        // 停止先标记意图，再等启动临界区结束；记录建立前的停止不会被丢弃。
        return synchronized(launchLock) {
            if (!acquireUserLease(lease) {
                    stopped.set(true)
                    synchronized(launchLock) { stop(id) }
                }) return@synchronized DaemonStartResult.Failed("BACKGROUND_START_NOT_ALLOWED", "请返回 Eta 后重新启动后台任务")
            if (stopped.get()) {
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("TASK_CANCELLED", "后台任务已取消")
            }
            val script = "printf '%s\\n' \"${'$'}${'$'}\" > ${shellQuote(pidFile.absolutePath)}; $payload"
            val launcher = "if command -v setsid >/dev/null 2>&1; then exec setsid -w sh -c ${shellQuote(script)}; else exec sh -c ${shellQuote(script)}; fi"
            val process = try {
                ProcessBuilder("sh", "-c", launcher).apply {
                    environment()[ETA_PROCESS_OWNER_ENV] = token
                    if (environment == TerminalEnvironment.ANDROID) environment()["HOME"] = workspace
                    redirectInput(File("/dev/null"))
                    redirectOutput(logFile)
                    redirectErrorStream(true)
                }.start()
            } catch (_: java.io.IOException) {
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("PROCESS_START_FAILED", "无法启动后台任务")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (pidFile.length() == 0L && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            val pid = if (pidFile.isFile) pidFile.readText().trim().toLongOrNull() else null
            if (pid == null || pid <= 1) {
                process.destroyForcibly()
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("PROCESS_START_FAILED", "后台任务未完成启动")
            }
            val task = DetachedTask(id, pid, token, command, cwd, "user", environment,
                if (environment.isLinux) "$LINUX_DAEMON_DIR/$id.log" else logFile.absolutePath,
                System.currentTimeMillis(), if (environment.isLinux) LinuxExecutionBackend.PROOT else LinuxExecutionBackend.CHROOT, workspace)
            USER_WAITERS.add(id)
            if (!synchronized(RECORDS_LOCK) { saveTasksLocked(loadTasksLocked() + task) }) {
                USER_WAITERS.remove(id)
                stopTaskProcess(task)
                process.destroyForcibly()
                releaseUserLease(lease)
                return@synchronized DaemonStartResult.Failed("RECORDS_WRITE_FAILED", "无法保存后台任务，请检查内部存储")
            }
            thread(name = "eta-daemon-wait", isDaemon = true) {
                try { process.waitFor() } finally {
                    USER_WAITERS.remove(id)
                    releaseUserLease(lease)
                }
            }
            if (stopped.get()) {
                stop(id)
                DaemonStartResult.Failed("TASK_CANCELLED", "后台任务已取消")
            } else DaemonStartResult.Started(task)
        }
    }

    /** App 进程重建后只认领已确认归属的普通任务，避免后台 tracer 失去通知和停止入口。 */
    private fun adoptUserTask(task: DetachedTask): Boolean {
        if (!USER_WAITERS.add(task.id)) return true
        val lease = "daemon:${task.id}"
        val stopped = java.util.concurrent.atomic.AtomicBoolean(false)
        if (!acquireUserLease(lease) { stopped.set(true); stop(task.id) }) {
            USER_WAITERS.remove(task.id)
            return !stop(task.id)
        }
        thread(name = "eta-daemon-adopt", isDaemon = true) {
            try {
                while (!stopped.get()) {
                    val result = runOneShotShell(
                        processSupervisor = oneShotSupervisor, identity = "user",
                        command = aliveCheckFunction() + "\nif eta_alive ${task.pid} ${shellQuote(ownerProof(task))}; then echo alive; else echo exited; fi",
                        timeoutSeconds = 5,
                    )
                    if (result.exitCode == 0 && result.output.decodeToString().trim() == "exited") break
                    Thread.sleep(2_000)
                }
            } finally {
                USER_WAITERS.remove(task.id)
                releaseUserLease(lease)
            }
        }
        return !stopped.get()
    }

    /**
     * 裸启动器：不经过 [ShellProcessSupervisor] 的托管壳（壳的 wait 与退出清理正是守护任务要逃离的回收语义），
     * 只做启动、输出收集与超时防御。Linux 环境仍复用同一套 unshare + chroot 包装。
     */
    private fun launchRaw(
        identity: String,
        environment: TerminalEnvironment,
        script: String,
        timeoutSeconds: Long,
    ): OneShotShellResult {
        val payload = when (environment) {
            TerminalEnvironment.ANDROID -> oneShotSupervisor.buildAndroidPayload(identity, script)
            else -> {
                val rootfs = rootfsPath(environment)
                    ?: return OneShotShellResult(-1, ByteArray(0), "Linux rootfs 未配置".toByteArray())
                oneShotSupervisor.buildLinuxPayload(rootfs, script, linuxSharedMountsProvider())
            }
        }
        val process = runCatching {
            val builder = if (identity == "root") {
                ProcessBuilder("su", "-c", payload)
            } else {
                ProcessBuilder("sh", "-c", payload)
            }
            builder.redirectErrorStream(true).start()
        }.getOrElse {
            return OneShotShellResult(-1, ByteArray(0), (it.message ?: "无法启动进程").toByteArray())
        }
        val output = ByteArrayOutputCollector()
        val reader = thread(name = "agent-daemon-launch-reader", isDaemon = true) {
            process.inputStream.use { input -> output.readFrom(input) }
        }
        val finished = runCatching { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { reader.join(500) }
            return OneShotShellResult(-2, output.bytes(), "命令执行超时".toByteArray())
        }
        runCatching { reader.join(500) }
        return OneShotShellResult(process.exitValue(), output.bytes(), ByteArray(0))
    }

    private fun wireDaemonDir(environment: TerminalEnvironment): String =
        if (environment.isLinux) LINUX_DAEMON_DIR else daemonDir

    /**
     * Linux 任务的 /workspace 是宿主工作目录的 bind 视图，日志与 pid 文件的物理位置仍在宿主。
     * 读写一律走宿主路径，避免为读日志专门进入 chroot。
     */
    internal fun hostDaemonPath(task: DetachedTask, wirePath: String): String =
        if (task.environment.isLinux && wirePath.startsWith(LINUX_DAEMON_DIR)) {
            (task.hostWorkspace?.let { "$it/daemon" } ?: DEFAULT_DAEMON_DIR) + wirePath.removePrefix(LINUX_DAEMON_DIR)
        } else {
            wirePath
        }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private fun pruneExitedRecords(statuses: List<DetachedTaskStatus>) {
        val excess = statuses.size - MAX_RETAINED_RECORDS
        if (excess <= 0) return
        val victims = statuses
            .filter { !it.running && (!it.task.commandTask || it.task.completionNotified) }
            .sortedBy { it.task.startedAt }
            .take(excess)
        if (victims.isEmpty()) return
        victims.groupBy { it.task.identity }.forEach { (identity, group) ->
            val removeScript = group.joinToString("\n") {
                "rm -f ${shellQuote(hostDaemonPath(it.task, it.task.logPath))} " +
                    shellQuote(hostDaemonPath(it.task, it.task.logPath.removeSuffix(".log") + ".pid")) + " " +
                    shellQuote(hostDaemonPath(it.task, it.task.logPath.removeSuffix(".log") + ".exit"))
            }
            runOneShotShell(
                processSupervisor = oneShotSupervisor,
                identity = identity,
                command = removeScript,
                timeoutSeconds = 10,
            )
        }
        val victimIds = victims.mapTo(mutableSetOf()) { it.task.id }
        synchronized(RECORDS_LOCK) {
            saveTasksLocked(loadTasksLocked().filterNot { it.id in victimIds })
        }
    }

    private fun loadTasksLocked(): MutableList<DetachedTask> {
        if (!recordsFile.exists()) return mutableListOf()
        return runCatching {
            val array = JSONArray(recordsFile.readText())
            (0 until array.length()).mapTo(mutableListOf()) { index ->
                array.getJSONObject(index).toDetachedTask()
            }
        }.getOrElse {
            logger.warn("Agent terminal daemon records corrupted, resetting")
            mutableListOf()
        }
    }

    private fun saveTasksLocked(tasks: List<DetachedTask>): Boolean =
        runCatching {
            recordsFile.parentFile?.mkdirs()
            val array = JSONArray()
            tasks.forEach { array.put(it.toRecordJson()) }
            val tmp = File(recordsFile.parentFile, recordsFile.name + ".tmp")
            tmp.writeText(array.toString())
            if (!tmp.renameTo(recordsFile)) {
                recordsFile.writeText(array.toString())
            }
            true
        }.getOrElse {
            logger.warn("Agent terminal daemon records save failed")
            false
        }

}
