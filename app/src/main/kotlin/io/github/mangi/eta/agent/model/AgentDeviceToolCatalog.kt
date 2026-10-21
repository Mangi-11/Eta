package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 常用设备能力的结构化 schema；按风险组决定是否向模型公开。 */
internal object AgentDeviceToolCatalog {
    fun appendTo(
        tools: JSONArray,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ) {
        AgentPhoneToolCatalog.appendTo(tools, directTools, sensitiveReadTools, sensitiveActionTools)
        if (directTools) appendDirectTools(tools)
        if (sensitiveReadTools) appendSensitiveReadTools(tools)
        if (sensitiveActionTools) appendSensitiveActionTools(tools)
    }

    private fun appendDirectTools(tools: JSONArray) {
        tools
            .put(function(
                "inspect_app",
                "读取当前 Android 用户中指定应用的版本、UID、安装来源与路径、启用状态和请求权限，不要求应用具有桌面入口。只支持 Eta 所属用户，不跨工作资料查询。权限列表按 permission_offset/permission_limit 分页。",
                properties(
                    "package_name" to string("精确 Android 包名", 255),
                    "user_id" to userId(),
                    "permission_offset" to integer("请求权限列表的起始索引，默认 0", 0, Int.MAX_VALUE),
                    "permission_limit" to integer("最多返回权限数量，默认 100", 1, 200),
                ),
                "package_name",
            ))
            .put(
                function(
                    "set_alarm",
                    "直接创建系统闹钟，不要用 GUI。涉及相对日期时先用 get_current_context 换算；hour/minute 使用设备本地时间。系统不接受直达操作时可能只打开时钟页面。",
                    properties(
                        "hour" to integer("0 到 23", 0, 23),
                        "minute" to integer("0 到 59", 0, 59),
                        "label" to string("闹钟标签，最多 100 字", 100),
                        "repeat_days" to stringArray(
                            "重复星期；不提供表示仅下一次",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                        "vibrate" to boolean("是否振动，默认 true"),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_timer",
                    "直接创建系统计时器，不要用 GUI。duration_seconds 必须是 1 到 86400 秒。",
                    properties(
                        "duration_seconds" to integer("计时秒数", 1, 86_400),
                        "label" to string("计时器标签，最多 100 字", 100),
                    ),
                    "duration_seconds",
                ),
            )
            .put(emptyFunction("device_status", "读取电池、内存、存储、系统版本与开机时长。"))
            .put(emptyFunction("network_info", "读取当前联网方式、联网验证状态和当前 Wi‑Fi 基本信息，不返回保存的密码。"))
            .put(limitFunction("top_memory_apps", "按当前 RSS 列出内存占用最高的进程。"))
            .put(limitFunction("top_storage_apps", "按应用、数据与缓存合计列出存储占用最高的应用。"))
            .put(
                function(
                    "media_control",
                    "直接控制当前媒体会话，不要操作播放器 GUI。",
                    properties(
                        "action" to enumString(
                            "媒体动作",
                            "play", "pause", "play_pause", "next", "previous", "stop",
                        ),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "set_volume",
                    "直接设置系统音量，不要操作音量 GUI。",
                    properties(
                        "stream" to enumString("音量通道", "media", "alarm", "ring", "notification", "voice_call", "system"),
                        "percent" to integer("0 到 100 的音量百分比", 0, 100),
                        "user_id" to userId(),
                    ),
                    "stream", "percent",
                ),
            )
    }

    private fun appendSensitiveReadTools(tools: JSONArray) {
        AgentPersonalSearchToolCatalog.appendTo(tools)
        tools
            .put(
                function(
                    "get_setting",
                    "读取一个 Android Settings 值。结果可能包含设备标识等敏感信息，原始结果不会持久化。",
                    properties(
                        "namespace" to enumString("设置命名空间", "system", "secure", "global"),
                        "key" to string("精确设置键", 200),
                        "user_id" to userId(),
                    ),
                    "namespace", "key",
                ),
            )
            .put(
                function(
                    "wifi_credentials",
                    "读取手机保存的 Wi‑Fi 名称与密码。原始结果不会持久化。",
                    properties(
                        "ssid" to string("可选的精确 Wi‑Fi 名称", 128),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_notifications",
                    "读取当前通知栏中的通知标题与正文。结果不写入持久会话。",
                    properties(
                        "package_name" to string("可选的精确应用包名过滤", 255),
                        "limit" to integer("最多返回数量，默认 10", 1, 20),
                    ),
                ),
            )
            .put(
                function(
                    "search_notification_history",
                    "检索 Eta 在用户授予通知使用权后记录的最近 7 天通知。原始结果不写入持久会话。",
                    properties(
                        "query" to string("可选标题或正文关键词", 200),
                        "package_name" to string("可选精确包名", 255),
                        "max_age_hours" to integer("回溯小时数，默认 24", 1, 168),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_app_activity",
                    "读取最近打开应用的时间顺序，需要系统使用情况访问权。",
                    properties(
                        "package_name" to string("可选精确包名", 255),
                        "max_age_hours" to integer("回溯小时数，默认 24", 1, 168),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "app_usage_summary",
                    "按前台时长汇总最近应用使用情况，需要系统使用情况访问权。",
                    properties(
                        "max_age_hours" to integer("统计小时数，默认 24", 1, 168),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(emptyFunction("get_current_location", "读取系统已有的最近位置，不持续监听或主动唤醒 GPS。"))
            .put(emptyFunction("get_device_environment", "读取锁屏、勿扰、铃声、音频输出和外接显示器状态。"))
            .put(
                function(
                    "list_alarms",
                    "读取时钟中的原始闹钟 ID、时间、重复规则和开关；可筛选并分页。",
                    properties(
                        "enabled_only" to boolean("是否只返回已启用闹钟，默认 true"),
                        "query" to string("可选标签关键词", 200),
                        "hour" to integer("指定小时", 0, 23),
                        "minute" to integer("指定分钟", 0, 59),
                        "offset" to integer("分页起点", 0, 10000),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "list_active_timers",
                    "读取 ColorOS 时钟中正在运行或暂停的计时器。",
                    properties("limit" to integer("最多返回数量，默认 20", 1, 50)),
                ),
            )
            .put(searchFunction("search_clipboard_history", "检索当前系统输入法保存的剪贴板历史。"))
            .put(
                function(
                    "get_health_summary",
                    "汇总系统健康数据中的步数、睡眠、运动、心率、体重和血氧；不返回原始测量序列。",
                    properties("days" to integer("汇总最近天数，默认 7", 1, 30)),
                ),
            )
            .put(
                function(
                    "read_sms_code",
                    "从最近短信中只提取 4 到 8 位验证码、发送方和时间，不返回完整短信正文。",
                    properties(
                        "max_age_minutes" to integer("只检查多少分钟内的短信，默认 10", 1, 1_440),
                    ),
                ),
            )
            .put(
                function(
                    "get_logcat",
                    "读取设备日志环形缓冲区的有界样本，仍需 Root。先按 PID/tag/level/buffer 采集最近 scan_lines 条，再按 since/query 过滤并返回最近 max_lines 条。无匹配不代表更早记录不存在；查看 scan_start/end、scan_limited、has_more 和 complete_within_scan。日志不是按 Android 用户隔离的。",
                    properties(
                        "query" to string("可选过滤文本", 200),
                        "max_lines" to integer("最多返回匹配行数，默认 200；另有总字符上限", 1, 500),
                        "scan_lines" to integer("最多采集的最近日志行数，默认 2000，与返回行数不同", 1, 10_000),
                        "pid" to integer("只采集此进程 PID", 1, Int.MAX_VALUE),
                        "tag" to string("精确日志 tag，仅字母、数字、下划线、点或连字符", 100)
                            .put("pattern", "^[A-Za-z0-9_.-]{1,100}$"),
                        "level" to enumString("最低优先级，默认 V", "V", "D", "I", "W", "E", "F"),
                        "buffer" to enumString("日志缓冲区，默认 default", "main", "system", "crash", "events", "radio", "all", "default"),
                        "since" to string("样本中的起始时间，带时区的 ISO 8601 格式，如 2026-10-05T08:00:00Z", 64),
                    ),
                ),
            )
            .put(searchFunction("search_media", "检索当前媒体库中的图片，可按文件名或相册路径筛选，按入库时间从新到旧返回元数据与 content URI。查询最新图片使用此工具；按图片文字、描述或标签查历史图片时设置 match=content。此工具不读取图片内容。"))
            .put(searchFunction("search_audio", "检索本机音乐和音频文件，可按标题或文件名筛选。"))
            .put(searchFunction("search_recordings", "检索本机录音文件。结果来自系统媒体库，不读取录音转写或音频内容。"))
            .put(searchFunction("search_files", "检索共享存储中的文档和下载文件，可按文件名筛选。不会遍历其他应用私有目录。"))
            .put(searchFunction("search_calendar_events", "检索实时日历。时间窗口须同时提供 start_time/end_time，最多366天，返回窗口内的实例；不带窗口时返回原始事件定义。"))
            .put(searchFunction("search_contacts", "检索系统通讯录联系人，返回姓名和 lookup URI。"))
            .put(searchFunction("search_call_history", "检索通话记录，可按号码或联系人缓存名筛选。"))
            .put(searchFunction("search_messages", "检索短信，可按发送方或正文关键词筛选。结果属于敏感个人内容。"))
            .put(searchFunction("search_downloads", "检索系统下载记录，可按文件名或说明筛选。"))
            .put(searchFunction("search_notes", "按标题、正文或时间查询便签，优先原始来源。原始接口暂不可读时返回明确标记的历史索引；需要最新状态时设置 current_only=true。"))
            .put(searchFunction("search_coloros_recordings", "检索 ColorOS 录音应用中的普通录音和通话录音，返回名称、时长、类型和文件路径。"))
            .put(searchFunction("search_recording_summaries", "检索 ColorOS 录音关联的转写摘要和便签内容。仅在录音应用生成过摘要时可用。"))
            .put(searchFunction("search_system_memories", "查询系统记忆的历史索引、摘要和原文。账单、行程等结构化信息使用对应领域查询；不读取 Agent 自己的 MEMORY.md。"))
            .put(searchFunction("search_saved_places", "检索系统记忆中保存或识别的地点。"))
            .put(searchFunction("search_personal_orders", "检索系统记忆中识别的外卖、购物、快递、票券和出行订单。"))
            .put(searchFunction("search_qq_chat_images", "检索 QQ 聊天图片缓存，返回最近文件的时间、大小、类型和私有路径。仅在安装 QQ 且缓存仍存在时可用。"))
            .put(searchFunction("search_wechat_chat_images", "检索微信聊天图片缓存，返回最近文件的时间、大小和私有路径。仅在安装微信且缓存仍存在时可用。"))
    }

    private fun appendSensitiveActionTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_setting",
                    "修改一个 Android Settings 值，并读回验证。user_id 默认 Eta 所属用户；global 命名空间影响设备。仅 verified=true 表示已确认目标值，未确认时不要直接重放。",
                    properties(
                        "namespace" to enumString("设置命名空间", "system", "secure", "global"),
                        "key" to string("精确设置键", 200),
                        "value" to string("新值", 2_000),
                        "user_id" to userId(),
                    ),
                    "namespace", "key", "value",
                ),
            )
            .put(
                function(
                    "set_device_state",
                    "直接设置 Wi‑Fi、蓝牙、移动数据、飞行模式、定位、NFC、省电和深色模式并读回状态。异步切换尚未完成时返回未确认，请检查状态，不要直接重放。",
                    properties(
                        "target" to JSONObject().put("type", "string").put("enum", JSONArray(io.github.mangi.eta.agent.tool.SystemStateControls.targets)),
                        "enabled" to boolean("true 启用，false 关闭"),
                        "user_id" to userId(),
                    ),
                    "target", "enabled",
                ),
            )
            .put(
                function(
                    "app_state_control",
                    "停止、冻结或解冻 Eta 所属 Android 用户中的一个精确包名，包括系统应用，并读回 stopped/enabled 状态。仅 verified=true 表示目标状态已确认；不跨用户或工作资料执行。",
                    properties(
                        "package_name" to string("精确 Android 包名", 255),
                        "action" to enumString("动作", "force_stop", "freeze", "unfreeze"),
                        "user_id" to userId(),
                    ),
                    "package_name", "action",
                ),
            )
    }

    private fun emptyFunction(name: String, description: String): JSONObject =
        function(name, description, properties())

    private fun limitFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties("limit" to integer("最多返回数量，默认 10", 1, 30)),
        )

    private fun searchFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            if (name in setOf("search_notes", "search_system_memories", "search_calendar_events", "search_media", "search_files")) {
                AgentPersonalSearchToolCatalog.searchProperties().also { properties ->
                    if (name == "search_notes") properties.put("current_only", boolean("仅使用当前原始便签来源，不回退历史索引"))
                    if (name in setOf("search_media", "search_files")) properties.put("match", enumString("name 查当前文件名；content 查历史内容索引", "name", "content"))
                }
            } else properties("query" to string("可选关键词，最多 200 字", 200), "limit" to integer("最多返回数量，默认 10", 1, 30)),
        )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name = name,
            description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .also { schema ->
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
                },
        )

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { target -> entries.forEach { (name, schema) -> target.put(name, schema) } }

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .also { schema -> maxLength?.let { schema.put("maxLength", it) } }

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun userId(): JSONObject =
        integer("Android 用户编号，默认 Eta 所属用户；其他用户或工作资料返回 USER_SCOPE_UNSUPPORTED", 0, Int.MAX_VALUE)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", minimum)
            .put("maximum", maximum)
            .put("description", description)

    private fun enumString(description: String, vararg values: String): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun stringArray(description: String, vararg values: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", enumString(description, *values))
            .put("uniqueItems", true)
            .put("maxItems", 7)
            .put("description", description)
}
