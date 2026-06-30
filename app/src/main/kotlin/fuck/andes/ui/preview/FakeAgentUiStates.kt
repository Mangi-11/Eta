package fuck.andes.ui.preview

import fuck.andes.ui.model.AgentChatHomeUiState
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentChatUiState
import fuck.andes.ui.model.AgentRunDetailUiState
import fuck.andes.ui.model.AgentRunsUiState
import fuck.andes.ui.model.AgentSystemEnhanceUiState
import fuck.andes.ui.model.AgentToolsUiState
import fuck.andes.ui.model.ConversationModeUi
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import fuck.andes.ui.model.PermissionHealthItemUi
import fuck.andes.ui.model.PermissionHealthUiState
import fuck.andes.ui.model.PermissionStatusUi
import fuck.andes.ui.model.RunStatusUi
import fuck.andes.ui.model.RunSummaryUi
import fuck.andes.ui.model.RunTimelineItemUi
import fuck.andes.ui.model.SystemEnhanceItemUi
import fuck.andes.ui.model.SystemEnhanceSectionUi
import fuck.andes.ui.model.SystemEnhanceStatusUi
import fuck.andes.ui.model.ToolGroupUi
import fuck.andes.ui.model.ToolItemUi
import fuck.andes.ui.model.ToolSummaryMessageUi
import fuck.andes.ui.model.UserMessageUi
import fuck.andes.ui.model.AgentMessageUi

object FakeAgentUiStates {

    val conversations = ConversationPaneUiState(
        selectedConversationId = "c-001",
        searchQuery = "",
        conversations = listOf(
            ConversationSummaryUi(
                id = "c-001",
                title = "让 FuckAndes 操作手机",
                preview = "准备接管屏幕与工具，等待下一步任务",
                timeLabel = "现在",
                mode = ConversationModeUi.PhoneAgent,
                isPinned = true,
                isActiveRun = true,
            ),
            ConversationSummaryUi(
                id = "c-002",
                title = "今天的安排和提醒",
                preview = "查询天气、同步日程，并设置出门提醒",
                timeLabel = "10:41",
                mode = ConversationModeUi.Chat,
                isPinned = true,
            ),
            ConversationSummaryUi(
                id = "c-003",
                title = "打开网易云音乐播放每日推荐",
                preview = "已完成 4 个工具调用，用时 8 秒",
                timeLabel = "10:23",
                mode = ConversationModeUi.PhoneAgent,
            ),
            ConversationSummaryUi(
                id = "c-004",
                title = "分析当前页面截图",
                preview = "总结页面结构，并指出按钮层级问题",
                timeLabel = "昨天",
                mode = ConversationModeUi.Chat,
            ),
            ConversationSummaryUi(
                id = "c-005",
                title = "检查 Alpine 终端环境",
                preview = "列出可用命令、Python/Node 版本和后台 job",
                timeLabel = "周五",
                mode = ConversationModeUi.Terminal,
            ),
            ConversationSummaryUi(
                id = "c-006",
                title = "每晚整理下载目录",
                preview = "定时扫描文件并归类图片、安装包和文档",
                timeLabel = "5-18",
                mode = ConversationModeUi.Automation,
            ),
        ),
    )

    val chatHome: AgentChatHomeUiState = AgentChatHomeUiState(
        messages = emptyList(),
        input = "",
        isStreaming = false,
    )

    val chat = AgentChatUiState(
        messages = listOf(
            UserMessageUi(
                id = "m-01",
                content = "打开设置里的电池优化",
            ),
            AgentMessageUi(
                id = "m-02",
                content = "好的，我会打开系统设置中的电池优化页面。",
            ),
            ToolSummaryMessageUi(
                id = "tools-01",
                tools = listOf("open_app", "click_text", "screenshot"),
            ),
        ),
        input = "",
        isStreaming = false,
    )

    val permissionHealth = PermissionHealthUiState(
        items = listOf(
            PermissionHealthItemUi(
                id = "accessibility",
                title = "无障碍服务",
                summary = "已启用，Agent 可操作 UI",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "overlay",
                title = "悬浮窗权限",
                summary = "已授权，可显示运行浮窗",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "notification",
                title = "通知权限",
                summary = "用于后台任务完成提醒",
                status = PermissionStatusUi.Available,
                primaryActionLabel = null,
            ),
            PermissionHealthItemUi(
                id = "root",
                title = "Root 权限",
                summary = "未获得，部分终端命令受限",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "检查",
            ),
            PermissionHealthItemUi(
                id = "shizuku",
                title = "Shizuku",
                summary = "未配置，ADB 级能力不可用",
                status = PermissionStatusUi.Disabled,
                primaryActionLabel = "配置",
            ),
            PermissionHealthItemUi(
                id = "xposed",
                title = "Hook / Xposed",
                summary = "框架未激活，系统增强能力不可用",
                status = PermissionStatusUi.Missing,
                primaryActionLabel = "查看",
            ),
            PermissionHealthItemUi(
                id = "background",
                title = "后台保活",
                summary = "电池优化未关闭，长任务可能被中断",
                status = PermissionStatusUi.Warning,
                primaryActionLabel = "设置",
            ),
        ),
    )

    val runDetail = AgentRunDetailUiState(
        runId = "run-002",
        status = RunStatusUi.Success,
        title = "打开网易云音乐播放每日推荐",
        startedAt = "10:23:04",
        finishedAt = "10:23:12",
        durationLabel = "8 秒",
        timeline = listOf(
            RunTimelineItemUi.UserRequest(
                id = "t-01",
                content = "打开网易云音乐播放每日推荐",
            ),
            RunTimelineItemUi.ModelThinking(
                id = "t-02",
                content = "用户想听音乐。我需要：1）打开网易云音乐；2）找到每日推荐；3）点击播放。",
            ),
            RunTimelineItemUi.ToolCall(
                id = "t-03",
                toolName = "open_app",
                argumentsSummary = "packageName: com.netease.cloudmusic",
            ),
            RunTimelineItemUi.ToolResult(
                id = "t-04",
                success = true,
                summary = "应用已启动",
            ),
            RunTimelineItemUi.Screenshot(
                id = "t-05",
                description = "已截取网易云音乐首页，识别到“每日推荐”入口",
            ),
            RunTimelineItemUi.ToolCall(
                id = "t-06",
                toolName = "click_text",
                argumentsSummary = "text: 每日推荐",
            ),
            RunTimelineItemUi.ToolResult(
                id = "t-07",
                success = true,
                summary = "点击进入每日推荐页面",
            ),
            RunTimelineItemUi.ToolCall(
                id = "t-08",
                toolName = "click",
                argumentsSummary = "播放按钮",
            ),
            RunTimelineItemUi.ToolResult(
                id = "t-09",
                success = true,
                summary = "开始播放",
            ),
            RunTimelineItemUi.FinalResult(
                id = "t-10",
                content = "已为你打开网易云音乐并开始播放每日推荐。",
            ),
        ),
    )

    val runs = AgentRunsUiState(
        runs = listOf(
            RunSummaryUi(
                runId = "run-002",
                status = RunStatusUi.Success,
                title = "打开网易云音乐播放每日推荐",
                timeLabel = "10:23",
                toolCount = 4,
                durationLabel = "8 秒",
            ),
            RunSummaryUi(
                runId = "run-003",
                status = RunStatusUi.Failed,
                title = "在 Chrome 中搜索 Pixel 9 评测",
                timeLabel = "昨天",
                toolCount = 2,
                durationLabel = "15 秒",
            ),
            RunSummaryUi(
                runId = "run-004",
                status = RunStatusUi.Success,
                title = "截图并总结当前聊天内容",
                timeLabel = "昨天",
                toolCount = 6,
                durationLabel = "22 秒",
            ),
        ),
    )

    val tools = AgentToolsUiState(
        groups = listOf(
            ToolGroupUi(
                id = "screen",
                title = "屏幕操作",
                tools = listOf(
                    ToolItemUi("observe", "观察屏幕", "截取并描述当前界面"),
                    ToolItemUi("click", "点击", "点击指定坐标或元素"),
                    ToolItemUi("long_press", "长按", "长按指定元素"),
                    ToolItemUi("swipe", "滑动", "滑动、滚动、返回等手势"),
                ),
            ),
            ToolGroupUi(
                id = "input",
                title = "输入与按键",
                tools = listOf(
                    ToolItemUi("input_text", "输入文字", "在焦点处输入文本"),
                    ToolItemUi("clipboard", "剪贴板", "读取或写入剪贴板"),
                    ToolItemUi("wait_text", "等待文本", "等待界面出现指定文字"),
                ),
            ),
            ToolGroupUi(
                id = "app",
                title = "App 与 URI",
                tools = listOf(
                    ToolItemUi("open_app", "打开 App", "通过包名启动应用"),
                    ToolItemUi("open_uri", "打开 URI", "启动链接或 Intent"),
                ),
            ),
            ToolGroupUi(
                id = "terminal",
                title = "终端与文件",
                tools = listOf(
                    ToolItemUi("terminal", "终端命令", "user/root 权限执行 shell"),
                    ToolItemUi("terminal_job", "后台任务", "异步 job 与读取输出"),
                ),
            ),
        ),
    )

    val systemEnhance = AgentSystemEnhanceUiState(
        sections = listOf(
            SystemEnhanceSectionUi(
                id = "status",
                title = "状态",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "hook",
                        title = "Hook 状态",
                        summary = "框架未激活",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                    SystemEnhanceItemUi(
                        id = "root",
                        title = "Root 状态",
                        summary = "未获得 root 权限",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                    SystemEnhanceItemUi(
                        id = "shizuku",
                        title = "Shizuku 状态",
                        summary = "未配对",
                        status = SystemEnhanceStatusUi.Unsupported,
                    ),
                ),
            ),
            SystemEnhanceSectionUi(
                id = "capabilities",
                title = "可增强的能力",
                items = listOf(
                    SystemEnhanceItemUi(
                        id = "power_key",
                        title = "长按电源键唤起",
                        summary = "需要 Hook 框架支持",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                    SystemEnhanceItemUi(
                        id = "assistant_replace",
                        title = "替换默认助理",
                        summary = "需要 Root 或 Hook 支持",
                        status = SystemEnhanceStatusUi.Inactive,
                    ),
                ),
            ),
        ),
    )
}
