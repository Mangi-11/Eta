package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTraceFormatterTest {
    private val formatter = AgentTraceFormatter()

    @Test
    fun currentContextIsPresentedAsTimeAndLocationWithoutRequiringAnOkField() {
        assertEquals("读取时间与位置", formatter.summarizeArguments(
            AgentModelClient.ToolCall("context", "get_current_context", "{}"),
        ))
        assertEquals("2026-10-28 12:30 +08:00 · 系统定位已关闭", formatter.summarizeResult(
            "get_current_context",
            AgentModelClient.ToolResult("""{"datetime":"2026-10-28T12:30:00+08:00","location":{"status":"location_disabled"}}"""),
        ))
    }

    @Test
    fun terminalLifecyclesHaveDistinctLabelsAndKeepCommandRedaction() {
        listOf(
            "terminal" to """{"command":"echo secret-marker"}""",
            "terminal_job" to """{"action":"start","command":"echo secret-marker"}""",
            "terminal_daemon" to """{"action":"start","command":"echo secret-marker"}""",
        ).forEach { (name, args) ->
            val call = AgentModelClient.ToolCall("task", name, args)
            val summary = formatter.summarizeArguments(call)
            assertFalse(summary.contains("secret-marker"))
            val label = when (name) { "terminal_job" -> "异步命令"; "terminal_daemon" -> "后台服务"; else -> "终端" }
            assertTrue(summary, summary.contains(label))
            assertEquals("echo secret-marker", formatter.displayCommand(call))
        }
        val result = formatter.summarizeResult("terminal_job", AgentModelClient.ToolResult(
            """{"ok":true,"tool":"terminal_job","action":"read","stdout":"done","exit_code":0}"""))
        assertTrue(result, result.contains("done"))
    }

    @Test
    fun publicWebArgumentsExposeOnlyActionAndHost() {
        val search = formatter.summarizeArguments(AgentModelClient.ToolCall(
            "search", "web_search", """{"query":"private-query","max_results":3}""",
        ))
        assertEquals("搜索网页", search)
        val fetch = formatter.summarizeArguments(AgentModelClient.ToolCall(
            "fetch", "fetch_url", """{"url":"https://alice:password@example.com/private-path?token=secret-query#private-fragment"}""",
        ))
        assertEquals("读取网页 · example.com", fetch)
        val next = formatter.summarizeArguments(AgentModelClient.ToolCall(
            "next", "fetch_url", """{"document_id":"private-document-id","offset_chars":12000}""",
        ))
        assertEquals("继续读取网页", next)
    }

    @Test
    fun publicWebResultsNeverEchoQueryTitlesBodyOrCompleteUrl() {
        val search = formatter.summarizeResult("web_search", AgentModelClient.ToolResult(
            """{"ok":true,"query":"private-query","results":[{"title":"private-title","url":"https://example.com/private-path","snippet":"private-body"}]}""",
        ))
        assertEquals("找到 1 条搜索结果", search)
        val fetch = formatter.summarizeResult("fetch_url", AgentModelClient.ToolResult(
            """{"ok":true,"final_url":"https://alice:password@example.com/private-path?token=secret-query","title":"private-title","text":"private-body","has_more":true}""",
        ))
        assertEquals("已读前 12 字", fetch)
        listOf("private-query", "private-title", "private-body", "private-path", "password", "secret-query").forEach {
            assertFalse(search.contains(it))
            assertFalse(fetch.contains(it))
        }
    }

    @Test
    fun publicWebFailureSummaryDoesNotEchoServerMessages() {
        listOf("web_search", "fetch_url").forEach { name ->
            val failure = formatter.summarizeResult(name, AgentModelClient.ToolResult(
                """{"ok":false,"code":"HTTP_ERROR","message":"private-query https://alice:password@example.com/private-path?token=secret-query"}""",
            ))
            assertTrue(failure.contains("code=HTTP_ERROR"))
            assertFalse(failure.contains("private"))
            assertFalse(failure.contains("password"))
            assertFalse(failure.contains("secret-query"))
        }
    }

    @Test
    fun sensitiveToolArgumentsAreSummarizedWithoutRawValues() {
        val cases = listOf(
            RedactionCase(
                toolName = "terminal",
                argumentsJson =
                    """{"action":"open_and_exec","identity":"root","command":"echo bearer-secret"}""",
                expectedParts = listOf("终端", "单次执行", "Android", "root"),
                sensitiveParts = listOf("echo bearer-secret", "bearer-secret"),
            ),
            RedactionCase(
                toolName = "run_command",
                argumentsJson = """{"command":"cat /data/local/tmp/private-token"}""",
                expectedParts = listOf("执行命令", "Android"),
                sensitiveParts = listOf("cat ", "/data/local/tmp/private-token", "private-token"),
            ),
            RedactionCase(
                toolName = "write_file",
                argumentsJson =
                    """{"path":"/data/local/tmp/secret.txt","content":"api-key-value"}""",
                expectedParts = listOf("写入文件", "字符"),
                sensitiveParts = listOf("/data/local/tmp/secret.txt", "api-key-value"),
            ),
            RedactionCase(
                toolName = "input_text",
                argumentsJson = """{"text":"one-time-password-123456"}""",
                expectedParts = listOf("输入文本", "字符"),
                sensitiveParts = listOf("one-time-password-123456", "123456"),
            ),
            RedactionCase(
                toolName = "read_file",
                argumentsJson = """{"path":"/data/user/0/example/private.xml"}""",
                expectedParts = listOf("读取文件"),
                sensitiveParts = listOf("/data/user/0/example/private.xml", "private.xml"),
            ),
            RedactionCase(
                toolName = "list_directory",
                argumentsJson = """{"path":"/storage/emulated/0/Private"}""",
                expectedParts = listOf("列出目录"),
                sensitiveParts = listOf("/storage/emulated/0/Private"),
            ),
            RedactionCase(
                toolName = "memory_get",
                argumentsJson = """{"query":"private relationship"}""",
                expectedParts = listOf("检索记忆"),
                sensitiveParts = listOf("private relationship"),
            ),
            RedactionCase(
                toolName = "memory_write",
                argumentsJson = """{"mode":"append","revision":"secret-revision","content":"private memory"}""",
                expectedParts = listOf("追加记忆"),
                sensitiveParts = listOf("secret-revision", "private memory"),
            ),
        )

        cases.forEach { case ->
            val summary = formatter.summarizeArguments(
                AgentModelClient.ToolCall(
                    id = "call-test",
                    name = case.toolName,
                    argumentsJson = case.argumentsJson,
                )
            )

            case.expectedParts.forEach { expected ->
                assertTrue(
                    "${case.toolName} summary must contain '$expected': $summary",
                    summary.contains(expected),
                )
            }
            case.sensitiveParts.forEach { sensitive ->
                assertFalse(
                    "${case.toolName} summary leaked '$sensitive': $summary",
                    summary.contains(sensitive),
                )
            }
        }
    }

    @Test
    fun stateChangeWithoutReadbackIsShownAsUnconfirmed() {
        val result = AgentModelClient.ToolResult(
            content = """{"ok":false,"status":"unconfirmed","verified":false,"code":"STATE_CHANGE_UNCONFIRMED"}""",
        )
        assertEquals("操作已提交，效果未确认", formatter.summarizeResult("set_device_state", result))
        assertFalse(formatter.isSuccessResult(result))
    }

    @Test
    fun deviceStateResultShowsObservedChangesWithoutInventingPreviousState() {
        listOf(
            """{"target":"auto_rotate","before":false,"after":true}""" to "自动旋转：关闭 → 开启",
            """{"target":"auto_brightness","before":true,"after":false}""" to "自动亮度：开启 → 关闭",
            """{"target":"wifi","before":true,"after":true}""" to "Wi-Fi：保持开启",
            """{"target":"auto_rotate","before":false,"after":false}""" to "自动旋转：保持关闭",
            """{"target":"auto_rotate","before":null,"after":true}""" to "自动旋转：已开启",
        ).forEach { (observed, expectedSummary) ->
            val result = JSONObject(observed).put("ok", true).put("verified", true)
            assertEquals(expectedSummary, formatter.summarizeResult("set_device_state", AgentModelClient.ToolResult(result.toString())))
        }
    }

    @Test
    fun deviceStateResultRequiresVerifiedBooleanReadback() {
        listOf(
            """{"ok":true,"verified":true,"target":"auto_rotate","expected":true}""" to "完成",
            """{"ok":true,"verified":true,"target":"auto_rotate","after":"false"}""" to "完成",
            """{"ok":true,"verified":true,"target":"private-target","after":true}""" to "完成",
            """{"ok":true,"verified":false,"target":"auto_rotate","before":false,"after":true}""" to "已执行，效果未确认",
            """{"ok":false,"status":"unconfirmed","verified":false,"target":"auto_rotate","before":false,"after":false,"expected":true}""" to "操作已提交，效果未确认",
            """{"ok":false,"code":"ROOT_REQUIRED","target":"auto_rotate","before":false,"after":true}""" to "失败 · code=ROOT_REQUIRED",
        ).forEach { (content, expectedSummary) ->
            assertEquals(expectedSummary, formatter.summarizeResult("set_device_state", AgentModelClient.ToolResult(content)))
        }
    }

    @Test
    fun skillResultsShowReturnedCountsAndWhenInstalledSkillsBecomeAvailable() {
        val listed = AgentModelClient.ToolResult(
            """{"ok":true,"count":99,"items":[{"name":"private-name","installed":true},{"path":"private-path","installed":false}]}""",
        )
        assertEquals("找到 2 个技能 · 1 个已安装", formatter.summarizeResult("skills_inspect_github", listed))
        val installed = AgentModelClient.ToolResult(
            """{"ok":true,"installed":[{"name":"private-name"}],"available":"next_turn","message":"private-message"}""",
        )
        assertEquals("已安装 1 个技能 · 下一轮可用", formatter.summarizeResult("skills_install_from_github", installed))
    }

    @Test
    fun genericQueryResultsShowPageSizeWithoutExposingRecords() {
        val result = AgentModelClient.ToolResult(
            """{"ok":true,"count":99,"items":[{"content":"private-record"},{"value":"private-value"}],"has_more":true,"partial":true}""",
            sensitive = true,
        )
        assertEquals("找到 2 条短信 · 还有更多 · 采集范围有限", formatter.summarizeResult("search_messages", result))

        val generic = AgentModelClient.ToolResult(
            """{"ok":true,"items":[{"content":"one"},{"content":"two"}],"has_more":true,"partial":true}""",
        )
        assertEquals("找到 2 条结果 · 还有更多 · 采集范围有限", formatter.summarizeResult("custom_search", generic))
    }

    @Test
    fun specializedResultsKeepFailureAndSubmissionSemantics() {
        assertEquals("自动息屏：30 秒 → 2 分钟", formatter.summarizeResult("set_screen_timeout", AgentModelClient.ToolResult(
            """{"ok":true,"verified":true,"before":30000,"after":120000}""",
        )))
        assertEquals("已提交计时请求 · 5 分钟", formatter.summarizeResult("set_timer", AgentModelClient.ToolResult(
            """{"ok":true,"status":"dispatched","verified":false,"duration_seconds":300}""",
        )))
        assertEquals("已追加 512 字节", formatter.summarizeResult("write_file", AgentModelClient.ToolResult(
            """{"ok":true,"mode":"append","bytes_written":512}""",
        )))
        assertEquals("失败 · code=FILE_INPUT_FAILED", formatter.summarizeResult("write_file", AgentModelClient.ToolResult(
            """{"ok":false,"code":"FILE_INPUT_FAILED","mode":"append","bytes_written":512}""",
        )))
    }

    @Test
    fun terminalCommandsAreExposedOnlyThroughDisplayField() {
        val terminal = AgentModelClient.ToolCall(
            id = "terminal-call",
            name = "terminal",
            argumentsJson =
                """{"action":"open_and_exec","environment":"linux","command":"git status --short"}""",
        )
        val runCommand = AgentModelClient.ToolCall(
            id = "run-command-call",
            name = "run_command",
            argumentsJson = """{"command":"pm list packages | head"}""",
        )

        assertEquals("git status --short", formatter.displayCommand(terminal))
        assertEquals("pm list packages | head", formatter.displayCommand(runCommand))
        assertEquals("终端 · 单次执行 · Linux", formatter.summarizeArguments(terminal))
        assertFalse(formatter.summarizeArguments(terminal).contains("git status"))
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall("observe", "observe_screen", "{}")
            )
        )
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall(
                    "oversized",
                    "run_command",
                    """{"command":"${"x".repeat(4_001)}"}""",
                )
            )
        )
    }

    @Test
    fun terminalSummaryPreservesExplicitEnvironmentNames() {
        listOf("android" to "Android", "alpine" to "Alpine", "debian" to "Debian")
            .forEach { (environment, label) ->
                val summary = formatter.summarizeArguments(
                    AgentModelClient.ToolCall(
                        id = "terminal-environment",
                        name = "terminal",
                        argumentsJson =
                            """{"action":"open_and_exec","environment":"$environment"}""",
                    ),
                )

                assertEquals("终端 · 单次执行 · $label", summary)
            }
    }

    @Test
    fun displayedTerminalCommandsRedactCommonCredentialForms() {
        val command = """export API_KEY='sk-secret'; curl -H 'Authorization: Bearer token-value' --password hunter2 https://example.com?access_token=query-secret"""
        val displayed = formatter.displayCommand(
            AgentModelClient.ToolCall(
                id = "secret-command",
                name = "run_command",
                argumentsJson = JSONObject().put("command", command).toString(),
            )
        )!!

        assertTrue(displayed.contains("API_KEY=<已隐藏>"))
        assertTrue(displayed.contains("Authorization: <已隐藏>"))
        assertTrue(displayed.contains("--password <已隐藏>"))
        assertTrue(displayed.contains("access_token=<已隐藏>"))
        assertFalse(displayed.contains("sk-secret"))
        assertFalse(displayed.contains("token-value"))
        assertFalse(displayed.contains("hunter2"))
        assertFalse(displayed.contains("query-secret"))
    }

    @Test
    fun malformedSensitiveArgumentsUseSafeFallback() {
        val summary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "call-test",
                name = "terminal",
                argumentsJson = "{secret-command",
            )
        )

        assertTrue(summary.contains("终端"))
        assertFalse(summary.contains("secret-command"))
        assertNull(
            formatter.displayCommand(
                AgentModelClient.ToolCall("call-test", "terminal", "{secret-command")
            )
        )
    }

    @Test
    fun screenObservationSummaryUsesTreeFirstDefaults() {
        val defaultSummary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "observe-default",
                name = "observe_screen",
                argumentsJson = "{}",
            ),
        )
        val screenshotSummary = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "observe-image",
                name = "observe_screen",
                argumentsJson = """{"include_screenshot":true}""",
            ),
        )

        assertTrue(defaultSummary.contains("观察屏幕"))
        assertTrue(defaultSummary.contains("含界面树"))
        assertFalse(defaultSummary.contains("含截图"))
        assertTrue(screenshotSummary.contains("含截图"))
        assertTrue(screenshotSummary.contains("含界面树"))
    }

    @Test
    fun browserResultSummaryIsHumanReadable() {
        val success = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"action":"navigate","page":{"url":"https://example.com/path?token=secret-query","title":"Example Domain"}}""",
            ),
        )

        assertTrue(success.contains("已打开"))
        assertTrue(success.contains("example.com"))
        assertTrue(success.contains("《Example Domain》"))
        assertFalse(success.contains("ok="))
        assertFalse(success.contains("token=secret-query"))

        val failure = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"USER_CONTROL_ACTIVE","action":"click"}""",
            ),
        )

        assertTrue(failure.startsWith("失败"))
        assertTrue(failure.contains("code=USER_CONTROL_ACTIVE"))

        val readable = formatter.summarizeResult(
            "browser_use",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"action":"get_readable","text_chars":23000,"truncated":true,"page":{"url":"https://example.com"}}""",
            ),
        )

        assertTrue(readable.contains("已提取正文"))
        assertTrue(readable.contains("约 2.3 万字"))
        assertTrue(readable.contains("已截断"))
    }

    @Test
    fun resultSuccessComesFromOkFlagNotSummaryText() {
        val ok = AgentModelClient.ToolResult(content = """{"ok":true}""")
        val failed = AgentModelClient.ToolResult(content = """{"ok":false,"code":"X"}""")
        val noFlag = AgentModelClient.ToolResult(content = """{"data":1}""")
        val malformed = AgentModelClient.ToolResult(content = "{broken")

        assertTrue(formatter.isSuccessResult(ok))
        assertFalse(formatter.isSuccessResult(failed))
        assertTrue(formatter.isSuccessResult(noFlag))
        assertTrue(formatter.isSuccessResult(malformed))
        assertEquals("已点击", formatter.summarizeResult("tap", ok))
        assertEquals("失败 · code=X", formatter.summarizeResult("tap", failed))
    }

    @Test
    fun memoryResultSummaryPresentsNaturalStatusWithoutByteOrLineCounts() {
        val summary = formatter.summarizeResult(
            "memory_get",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"bytes":321,"line_count":9,"content":"private memory"}""",
                sensitive = true,
            ),
        )

        assertEquals("已读取记忆", summary)
        assertFalse(summary.contains("9 行"))
        assertFalse(summary.contains("321 字节"))
        assertFalse(summary.contains("private memory"))

        val querySummary = formatter.summarizeResult(
            "memory_get",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"matched_lines":3,"line_count":200,"bytes":8000}""",
            ),
        )
        assertEquals("找到 3 条相关记忆", querySummary)
        assertFalse(querySummary.contains("200"))
        assertFalse(querySummary.contains("8000"))
    }

    @Test
    fun searchQueryIsShownInSummary() {
        val searchApps = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-apps",
                name = "search_apps",
                argumentsJson = """{"query":"抖音"}""",
            ),
        )
        assertEquals("搜索应用 · 抖音", searchApps)

        val searchFiles = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-files",
                name = "search_files",
                argumentsJson = """{"query":"周报"}""",
            ),
        )
        assertEquals("搜索文件 · 周报", searchFiles)

        // 关键词单行化并截断，避免撑爆折叠行标题
        val longQuery = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "search-long",
                name = "search_apps",
                argumentsJson = """{"query":"${"很长的关键词".repeat(10)}\n第二行"}""",
            ),
        )
        assertFalse(longQuery.contains("\n"))
        assertTrue(longQuery.length <= "搜索应用 · ".length + 30 + 3)

        // 非搜索类设备工具不追加 query
        val deviceStatus = formatter.summarizeArguments(
            AgentModelClient.ToolCall(
                id = "device-status",
                name = "device_status",
                argumentsJson = """{"query":"不应出现"}""",
            ),
        )
        assertEquals("查看设备状态", deviceStatus)
    }

    @Test
    fun searchAppsResultListsAppNames() {
        val summary = formatter.summarizeResult(
            "search_apps",
            AgentModelClient.ToolResult(
                content = """
                    {"ok":true,"tool":"search_apps","query":"抖音","apps":[
                      {"app_name":"抖音","package_name":"com.ss.android.ugc.aweme","is_system_app":false},
                      {"app_name":"抖音极速版","package_name":"com.ss.android.ugc.aweme.lite","is_system_app":false},
                      {"app_name":"抖音商城","package_name":"com.ss.android.ugc.livelite","is_system_app":false},
                      {"app_name":"抖音火山版","package_name":"com.ss.android.ugc.live","is_system_app":false}
                    ]}
                """.trimIndent(),
            ),
        )

        assertTrue(summary.contains("已找到 4 个应用"))
        assertTrue(summary.contains("抖音、抖音极速版、抖音商城"))
        assertTrue(summary.contains("等"))
        assertFalse(summary.contains("com.ss.android"))

        val empty = formatter.summarizeResult(
            "search_apps",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"search_apps","query":"不存在的应用","apps":[]}""",
            ),
        )
        assertEquals("未找到匹配应用", empty)
    }

    @Test
    fun launchAppResultShowsAppName() {
        val summary = formatter.summarizeResult(
            "launch_app",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"launch_app","app_name":"抖音","package_name":"com.ss.android.ugc.aweme"}""",
            ),
        )

        assertEquals("已打开 · 抖音", summary)
    }

    @Test
    fun unconfirmedLaunchDoesNotClaimTheAppHasOpened() {
        val summary = formatter.summarizeResult(
            "launch_app",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"app_name":"示例应用","canvas":"virtual","launch_status":"unconfirmed"}""",
            ),
        )

        assertEquals("已请求打开，待确认 · 示例应用 · 虚拟屏", summary)
    }

    @Test
    fun terminalResultShowsExitCodeAndOutputPreview() {
        val success = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"run_command","exit_code":0,"stdout":"hello\nworld","stderr":"","timed_out":false}""",
            ),
        )
        assertTrue(success.startsWith("执行完成"))
        assertTrue(success.contains("hello\nworld"))

        val failure = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"tool":"terminal","action":"exec","exit_code":1,"stdout":"","stderr":"Permission denied"}""",
            ),
        )
        assertTrue(failure.startsWith("失败 · 退出码 1"))
        assertTrue(failure.contains("Permission denied"))

        val timedOut = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"tool":"run_command","exit_code":-2,"timed_out":true,"stdout":""}""",
            ),
        )
        assertEquals("失败 · 执行超时", timedOut)

        // 协议错误仍保留 code= 标记
        val coded = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"JOB_NOT_FOUND"}""",
            ),
        )
        assertEquals("失败 · code=JOB_NOT_FOUND", coded)

        // 带中文原因的错误：原因面向用户，code= 留给日志提取
        val codedWithMessage = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"TERMINAL_TOOLS_DISABLED","message":"请先启用终端/文件工具"}""",
            ),
        )
        assertEquals("失败 · 请先启用终端/文件工具 · code=TERMINAL_TOOLS_DISABLED", codedWithMessage)

        // 无输出的会话动作
        val closed = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = """{"ok":true,"tool":"terminal","action":"close","closed_session":true}""",
            ),
        )
        assertEquals("终端 · 关闭终端", closed)

        val multiLineOutput = (1..12).joinToString("\n") { "line-$it" }
        val multiLinePreview = formatter.summarizeResult(
            "terminal",
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "terminal")
                    .put("exit_code", 0)
                    .put("stdout", multiLineOutput)
                    .toString(),
            ),
        )
        assertTrue(multiLinePreview.contains("line-10"))
        assertFalse(multiLinePreview.contains("line-11"))
        assertTrue(multiLinePreview.endsWith("\n…"))
    }

    @Test
    fun failureSummaryShowsHumanReasonAndKeepsCodeMarker() {
        val summary = formatter.summarizeResult(
            "wait_for_text",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"TIMEOUT","message":"等待文本超时：抖音"}""",
            ),
        )
        assertEquals("失败 · 等待文本超时：抖音 · code=TIMEOUT", summary)

        val codeOnly = formatter.summarizeResult(
            "tap_element",
            AgentModelClient.ToolResult(
                content = """{"ok":false,"code":"INVALID_NODE_INDEX"}""",
            ),
        )
        assertEquals("失败 · code=INVALID_NODE_INDEX", codeOnly)
    }

    @Test
    fun terminalResultPreviewIsBounded() {
        val longOutput = (1..15).joinToString("\n") { "line-$it-${"x".repeat(100)}" }
        val summary = formatter.summarizeResult(
            "run_command",
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", true)
                    .put("tool", "run_command")
                    .put("exit_code", 0)
                    .put("stdout", longOutput)
                    .put("stdout_truncated", true)
                    .toString(),
            ),
        )

        val previewLines = summary.lines()
        // 状态行 + 最多 10 行预览 + 省略标记（与 ToolActivityDetail 的 MAX_RESULT_LINES = 12 对齐）
        assertTrue(previewLines.size <= 12)
        assertTrue(summary.endsWith("…"))
        assertTrue(summary.length < longOutput.length)
    }

    private data class RedactionCase(
        val toolName: String,
        val argumentsJson: String,
        val expectedParts: List<String>,
        val sensitiveParts: List<String>,
    )
}
