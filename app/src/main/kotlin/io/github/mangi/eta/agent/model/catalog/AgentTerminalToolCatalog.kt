package io.github.mangi.eta.agent.model.catalog

import org.json.JSONArray
import org.json.JSONObject

internal object AgentTerminalToolCatalog {
    val names = setOf("terminal", "terminal_job", "terminal_daemon")

    fun appendTo(tools: JSONArray) {
        tools.put(
            AgentToolSchema.function(
                name = "terminal",
                description = "执行 Shell 命令，直接提供 command 即可。默认环境、身份和工作目录见本次运行上下文。" +
                    "需要保留 cd、环境变量等 Shell 状态时，先 action=open，再通过 session_id 执行命令，最后 action=close。" +
                    "单次命令结束后进程会回收，勿用 & 挂后台；耗时或后台命令使用 terminal_job，跨运行的后台服务使用 terminal_daemon。" +
                    "已知文件的读写、编辑和搜索优先使用对应文件工具。",
                parameters = parameters(
                    commandProperties()
                        .put("action", action("exec", "open", "close", "check_root")
                            .put("description", "默认 exec 执行命令；open 创建持久 Shell；close 关闭会话；check_root 用固定的 su -c 'id -u' 检测并更新 Root 授权，不需要 command，可能显示系统授权请求。"))
                        .put("session_id", text("open 返回的会话 ID。exec 沿用会话的目录、环境和身份；切换目录请在会话中执行 cd。")),
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "terminal_job",
                description = "启动可在本次回复结束后继续运行的后台命令。start 立即返回 job_id，完成或失败时发送系统通知；返回的 completion_notifications_enabled 表示当前通知是否可用。" +
                    "list 查看任务，read 查看当前状态和日志，wait 在需要结果时等待，stop 终止任务。" +
                    "日志保存为文件，read 返回 output_path；预览截断时可用 read_file 分页读取完整日志。" +
                    "后台命令使用独立 Shell；不继承 terminal 会话。启动后可继续其他工作或回复用户，不必轮询等待。",
                parameters = parameters(
                    executionProperties()
                        .put("action", action("start", "list", "read", "wait", "stop"))
                        .put("command", text("start 要执行的完整命令。"))
                        .put("description", text("可选的简短任务说明，用于列表和完成通知。"))
                        .put("timeout_ms", integer("start 的命令运行上限，默认 600000 毫秒；0 表示不设超时。"))
                        .put("job_id", text("start 或 list 返回的任务 ID；read、wait、stop 使用。"))
                        .put("active_only", bool("list 是否只返回运行中的任务，默认 true。"))
                        .put("max_bytes", integer("read 返回的日志尾部字节数，默认 16000，最多 65536；完整日志保存在 output_path。"))
                        .put("wait_ms", integer("wait 最长等待毫秒数，默认 30000，最多 60000；等待超时不会停止命令。")),
                    "action",
                ),
            ),
        )
        tools.put(
            AgentToolSchema.function(
                name = "terminal_daemon",
                description = "管理需要跨 Agent 运行保留的后台服务，例如 Web 服务。start 启动并返回 task_id，" +
                    "list 查看任务，logs 读取日志，stop 停止任务。服务不会随当前运行结束自动回收，" +
                    "但仍可能因进程退出、系统回收、权限变化或设备重启而终止。",
                parameters = parameters(
                    executionProperties()
                        .put("action", action("start", "list", "logs", "stop"))
                        .put("command", text("start 要执行的命令，使用实际环境支持的 Shell 语法。"))
                        .put("task_id", text("start 或 list 返回的任务 ID；logs、stop 使用。")),
                    "action",
                ),
            ),
        )
    }

    private fun executionProperties(): JSONObject = JSONObject()
        .put("environment", text("执行环境（默认 android）：操作 Android 系统/应用/设备状态用 android；Git、写代码、运行 Python/Node 脚本、jq/rg/curl、包管理与 APK 分析请显式传 linux。工作区与共享目录在两端映射同一物理文件夹，按命令性质选环境并使用对应路径。")
            .put("enum", JSONArray(listOf("android", "linux"))))
        .put("identity", text("可选的宿主执行身份；Android 已确认 Root 授权时默认 root，否则 user。已授权时无需先尝试 user 或重复检测；显式 user 使用 Eta App UID。Linux 内模拟 root 不提供 Android 特权。")
            .put("enum", JSONArray(listOf("user", "root"))))
        .put("cwd", text("可选工作目录；省略时使用当前环境默认工作区，相对路径也以默认工作区为基准。"))

    private fun commandProperties(): JSONObject = executionProperties()
        .put("command", text("要执行的命令，使用实际环境支持的 Shell 语法。"))
        .put("timeout_ms", integer("命令超时毫秒，默认 30000，最多 180000。超时会终止命令；会话命令超时也会关闭会话。"))
        .put("merge_stderr", bool("是否把 stderr 合并到 stdout，默认 false。"))

    private fun parameters(properties: JSONObject, vararg required: String): JSONObject = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", JSONArray(required.toList()))
        .put("additionalProperties", false)

    private fun action(vararg values: String): JSONObject = text("要执行的动作。")
        .put("enum", JSONArray(values.toList()))

    private fun text(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)
    private fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
}
