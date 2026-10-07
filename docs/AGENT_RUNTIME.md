# Agent Runtime

Eta 的 Agent Runtime 负责把一次用户输入组织为模型回合、工具执行和可持久化的增量 transcript。它运行在模块自身进程；Hook 进程只负责识别入口、发送请求和接收结果。

## 代码边界

- `AgentModelClient`：稳定门面、配置与跨进程会话 DTO。
- `AgentLoop`：单次 run 的状态机，不依赖 Android Service、Room 或 Compose。
- `AgentPromptBuilder`：系统约束、Skill 索引、历史和当前用户输入。
- `AgentConversationCodec`：Provider JSON 与稳定会话 DTO 的转换。
- `AgentToolCatalog` 及分组目录：模型可见的工具 schema，不执行工具。
- `AgentTraceFormatter`：只生成可展示、可记录的脱敏摘要。
- `AgentWebTools`：公开网页搜索、匿名 HTTP 正文读取与当前 run 的分页快照；不占用共享 WebView 会话。
- `AgentProviderClient`：OpenAI-compatible、Anthropic 等协议边界。
- `AgentRunController`：取消、暂停和 steering 队列。
- `AgentRuntimeSession`：每个 run 自持 reply channel，并保证唯一最终结果。
- `AgentRuntimeRunExecutor`：从 Skill/工具初始化到模型执行、资源清理和终态提交的统一异常边界。
- `AgentRuntimeService`：Android 生命周期、入口 IPC 和浮层宿主；不再内联 Agent 执行循环。
- `ShellProcessSupervisor`：Android/Alpine/Debian Shell 进程的接纳、独立进程组、取消和回收；终端协议不承担进程所有权细节。

## Loop 语义

一个 turn 是“一次 assistant 响应 + 该响应提交的完整工具批次”。循环遵守以下顺序：

```text
pending steering
→ provider response
→ assistant history
→ tool batch（按模型顺序串行执行）
→ contiguous tool results
→ optional image observations
→ next turn / final result
```

关键不变量：

- steering 默认逐条排队，只在当前 turn 完整结束后注入；它不会取消当前 HTTP 请求或关闭工具资源。
- 同一 assistant 消息中的全部 tool result 必须连续写入，再追加不受 Provider 原生 tool-result image 支持的图片观察。
- `finish_reason=length` 或 `max_tokens` 且包含工具调用时，不执行任何可能被截断的参数；为每个调用写入结构化错误结果，让模型重新规划。
- 只有明确的 `tool_calls` / `tool_use` 终止原因才允许执行工具；`stop`、内容过滤或未知终止原因中夹带的调用一律作为协议矛盾拒绝。
- 工具参数在执行前按本轮实际下发的 JSON Schema 校验，支持本地 `$ref`、组合 Schema、条件 Schema 与常用对象、数组、字符串、数值约束；这只检查调用合同，不承担权限确认或额外安全策略。
- transcript 只返回本次 run 新增的 assistant、tool 和运行中 steering 消息，不重复旧 history 或本轮初始用户消息。
- GUI/终端工具保持串行。Android 前台状态和会话式 Shell 都不具备可安全并行的通用语义。
- 单次 run 不设置固定回合数或总时限，由模型自然结束、用户取消或不可恢复错误终止。
- cancel 是终止信号；pause 是检查点阻塞；steering 是下一回合输入。三者不能互相模拟。
- cancel 的主线程路径只做原子终态与资源关闭：共享浏览器按 runId 校验归属；终端立即封闭新的进程接纳，并在后台按独立进程组终止同步命令、会话和 async job，再完成线程与流回收。Android 上 `setsid` 或 PID/PGID ownership 握手不可用时会 fail closed；非 Android 测试环境才允许父子树快照回退。终止前还会核验随机 ownership token，避免陈旧 PGID 复用后误杀无关进程。
- 最终 steering 检查会原子关闭接收入口；Loop 返回后不会再把无人消费的补充指令误报为已接收。补充指令也不会解除 pause。
- 新 run 替换旧 run、用户取消和正常完成都通过 `AgentRuntimeSession` 的 `RUNNING → COMMITTING → TERMINAL` 状态机竞争唯一终态；提交胜者独占 outbox、归档和最终发布，客户端等待最终结果或 Binder 断连，不按等待时长取消任务。
- 入口请求只能缩小工具能力，不能自行授权。Runtime 在开始 run 时裁剪配置，在每次浏览器、终端和设备工具执行前重新读取用户开关，并在 thinking 关闭时移除自定义请求体中的 reasoning/thinking 覆盖字段。
- 设备工具分为直达工具、敏感读取工具和敏感操作工具，当前均默认开启。Runtime 在每次执行前重新读取用户开关；开关允许且参数符合工具 Schema 后即可执行，不再匹配用户原话，也不维护关键包、系统应用或 Settings key 黑名单。
- 微信发送不提供专用工具、参数协议或额外策略层，完全使用通用 GUI 工具观察和操作微信界面。
- 通知、短信验证码、Wi‑Fi 凭据、日志及设置读写返回的原始值属于瞬时敏感工具数据。当前模型回合可以使用原始值，但持久 transcript 会同时替换对应工具参数和结果，避免进入会话数据库或后续 IPC。

助理入口的 `assistant_screen_context` 是有界、可选的单次运行字段；旧入口缺失时按空内容处理。应用原始内容只保留在当前用户消息的临时元数据中，并在发给 Provider 时投影为数据文本。稳定会话编码和压缩摘要输入不包含该元数据，用户原话保持不变。截图仍使用既有图片传输协议。

截图图片携带可选的 `preserve_original` 标记：Runtime 接收文件描述符或内联图片后保留原始字节、编码和尺寸，不重新转为 JPEG。旧入口缺失该标记时继续使用附件兼容编码。系统仅提供 Bitmap 时，在截图采集端一次性编码为原尺寸 PNG；后续只进行传输所需的 Base64 封装，不解码重绘、缩放或铺底。容量超限时返回错误，不通过降低截图质量绕过上限。

## 可选角色上下文

角色按 App 会话绑定；Runtime 从该会话的持久记录读取角色 ID 和备用快照，再冻结本次使用的最新卡片。普通会话及系统助手入口使用默认人格。角色设定、世界书和剧情记忆由独立上下文投影提供，工具规则和执行器继续沿用 Agent 链路。

角色会话的现实记忆工具只读，剧情记忆工具隐式绑定当前角色。世界书和深度备注只出现在当前模型请求中，不写入执行 transcript；角色压缩摘要同时保留虚构剧情与真实任务事实。

`rewrite_reply` 是独立操作，使用 `REPLY_REWRITE` 请求目的禁用本地及托管工具和自定义 body 覆盖，也不接收 steering。它仅产生正文候选；临时上下文和合成改写指令不提交到主会话。消息通过稳定标识关联正文修订，原始 journal、工具结果和运行归档独立保留，候选提交和恢复按 run ID 幂等处理。详细用户边界见[角色功能](CHARACTERS.md)。

## Provider 协议

Provider 默认基础提示词将 Eta 定义为运行在 Android 设备上的 AI 助手，可以回答问题、与用户交流，也可以通过工具了解设备情况并执行操作；回答使用用户的语言，简洁、直接、自然。默认正文以 `BuiltinProviders.DEFAULT_SYSTEM_PROMPT` 为准；Provider 提示词为空时使用该默认值，已有非空配置保持原值。

Runtime 独立于 Provider 自定义提示词注入 Eta 身份，以“当前配置的模型”标注 `ModelConfig.model` 的实际值，随本次运行配置更新，不使用模型显示名或历史消息推断当前模型，也不据此推断部署版本、知识截止日期或能力。通用交流规则要求日常问答直接回答、仅在缺少关键参数时澄清、按用户需求调整详略，并如实交代工具操作结果；个性化分析区分事实与推测，不根据零散记录断言性格、动机或心理状态。工具、记忆与 Skills 等系统规则仍按运行时条件追加。

OpenAI-compatible Provider 可在配置页选择 `Chat Completions` 或 `Responses API`。新安装和重置后的内置 OpenAI 默认使用 Responses；数据库中已有 Provider 不会被默认值覆盖。自定义 Provider 和其他内置 Provider 默认仍使用 Chat Completions。

提供商目录从固定的 `models.dev/api.json` 读取公开元数据，应用内另有随 APK 打包的压缩快照；在线目录通过大小限制、协议和 HTTPS 地址校验后原子缓存，读取失败时使用缓存或快照。目录只列出 Eta 可按 Chat Completions 接入且具有文本输出与工具调用能力的候选模型；读取目录不发送已保存的 API Key。用户选择模型并核对完整 Base URL 后，导入为默认停用、无 API Key 的自定义提供商；后续密钥由用户为该地址填写，已保存的模型和当前选择不随目录刷新改写。

Chat Completions 在协议边界把当前上下文中的全部 `system` 内容按原顺序合并为首条唯一系统消息，兼容要求系统消息只能位于开头的模型 Chat Template。Responses 则把完整的 `system`/`developer` 上下文投影到 `instructions`，并将持久历史重建为带 `type: "message"` 的 input Items。

Chat Completions 流式工具调用按 `index` 聚合，后续空 ID 不覆盖已经收到的有效 ID。响应结束时为缺失或冲突的 ID 分配响应级唯一值，并避让原历史、实际出站消息和本响应的既有 ID；工具结束事件与最终调用使用同一 ID。旧历史中的重复 ID 仅在出站副本中修复：调用批次和紧邻的工具结果必须完整且能一一配对，调用与结果同步改名，同一输入重复发送时保持一致。配对有歧义或跨消息边界时保留原记录；出站修复不改写持久历史，也不删除结果或重新执行工具。

Responses 请求固定使用 `stream:true`、`store:false`，不发送 `previous_response_id`。Runtime 在同一次 run 的工具回合之间精确回放 Provider 返回的完整 output Items；因此 encrypted reasoning、服务端工具状态等 opaque 数据只存在于内存，不进入 IPC transcript、Room、日志或运行归档。持久会话只保留规范化回答、可见推理内容和 Eta 工具记录，后续 run 由这些稳定数据重新构建上下文。

兼容接口若在 `response.completed` 中省略 `output` 或返回空数组，Runtime 只使用同一 SSE 流中已经收到的标准文本、推理摘要和函数调用增量完成当前轮次；非空终态始终是权威结果，且本地恢复结果不会冒充 Provider 的 opaque output Items。

推理界面展示 Provider 返回的可见推理内容，不由 Eta 生成或补写。Responses 支持 `reasoning_summary_text.delta` 和 `reasoning_text.delta`；终态读取 reasoning item 的 `summary[]` 与 `content[].reasoning_text`，并兼容旧接口的单字段 `reasoning_text`。标准内容与旧字段同时存在时不重复追加，终态仍按 item 和内容块身份校准流式结果。Responses 只对精确命中官方目录且未被远端显式标记为 `reasoning:false` 的模型补齐推理能力，不会因 Endpoint 类型而假定所有模型支持推理。

Chat Completions 消费 `reasoning_content`，并兼容 `reasoning` 和 `reasoning_details` 中的可见文本或摘要；同一分片同时包含多种表示时只显示一次。Anthropic 消费 `content_block_start` 中已有的文字及后续 `thinking_delta` / `text_delta`，思考签名和加密内容不作为文字展示。三种协议共用 SSE 分帧，支持多行 `data:`、注释心跳和 UTF-8；正文、思考、工具的解释仍由各自 Provider 负责。Chat 在 `finish_reason` 到达时结束可见块，再接收用量与 `[DONE]`；Responses 和 Anthropic 收到各自终态事件后立即收尾，不等待连接关闭。缺少合法终态或 Anthropic 可见/工具块未闭合时返回未完成错误。

Anthropic 工具回合会在当前 run 的模型上下文中按原顺序回传思考块、签名及加密块，供工具结果继续使用；这些 Provider 专用块不进入持久会话。签名待回传时沿用上一轮的系统提示与工具目录并保持原始上下文，自动压缩延后到签名回合完成，强制压缩则明确报错；工具执行仍按实时权限校验。工具回合完成后的上下文压缩会清除旧签名。

Chat Completions、Responses 与 Anthropic Messages 在 Provider 边界统一投影为带 `round + block index` 身份的正文、思考和工具块。Responses 额外使用 `item_id/output_index/content_index` 区分同一轮中的多个 output item；Chat Completions 在 delta 类型切换时创建新块；Anthropic 直接保留 `content_block.index`。正文、思考或工具类型一旦切换，上一段可见块立即定稿，后续同类型内容也不会跨过工具卡片回填到旧块。终态只在 Provider 的权威内容与已流式内容不一致时携带一次替换，不用整轮聚合正文覆盖最后一个块。

服务端网页搜索是 Responses Provider 的独立开关，默认关闭。开启后请求只增加 `web_search` 托管工具；搜索开始和结束作为独立运行事件投影到 UI，不进入 Eta 本地工具执行器。当前配置为 OpenAI-compatible Responses 且开启此开关时，模型目录只保留 Provider 托管搜索，不再公开同名的本地 `web_search` 函数；`fetch_url` 与 `browser_use` 仍按本地 `browserTools` 开关提供。托管搜索不受本地 `browserTools` 开关控制。该开关在其他协议下不会隐藏本地搜索，也不会把 Responses 的工具字段注入其他 Provider 协议。最终回答中的 `url_citation` 会去重并转换为可点击 Markdown 引用；偏移无效时降级为回答末尾的来源列表。当前不接入 file search、code interpreter、Provider 托管 MCP 或其他托管工具。

### 模型等待与重试

模型流使用独立的 HTTP 配置：连接等待 15 秒、写入等待 30 秒、读取等待 5 分钟；读取限制针对等待新数据，不是整个任务的总时限。MCP、模型列表与下载继续沿用各自配置。模型 HTTP 客户端关闭底层连接自动重试，模型回合的有限重试统一由 Loop 编排。

连接中断、超时、提前 EOF、暂时限流和部分服务端错误最多重试 3 次，依次等待 2、4、8 秒；每个成功的模型回合重新获得独立预算。认证、额度、计费、证书、协议格式等非暂时性失败不自动重试。重试等待可取消，并遵守暂停检查点；排队的 steering 留到当前回合及工具批次完成后处理。

失败尝试不提交 assistant history，不执行其中的本地工具调用；前面完成的工具结果、当前回合的工具 schema 和截图在重试期间保持不变。重试使用新的展示轮次，失败的半截输出留在运行轨迹并标注重试，后续输出不会拼接到旧块；最终推理摘要不包含被替换的失败尝试。重试事件通过既有 IPC、checkpoint 和归档编码保存，恢复回放不会重新执行工具。若 Provider 已报告托管工具开始执行，本次失败不自动重试，避免重复触发服务端操作。

## MCP 工具

Eta 直接作为 MCP 客户端连接远程 Streamable HTTP 服务器，不把协议能力绑定到某个模型 Provider。当前优先使用 `2026-07-28` 无状态协议，并兼容需要 `initialize` 与 session 的 `2025-11-25` 服务；只接入 `tools/list` 和 `tools/call`，暂不支持 Resources、Prompts、Tasks、stdio、OAuth、交互式补充输入或 Provider 托管 MCP。

工具默认关闭，服务器也可整体停用。添加服务器时先发现并缓存工具目录，用户再逐项启用；未标记只读的工具需要额外确认。现代服务的目录按 `ttlMs` 到期并在下次 run 前刷新，legacy 目录由用户手动刷新。每次 run 开始时一并冻结启用目录与 Bearer Token，并生成带服务器命名空间的模型工具名，因此后续设置变化不会改变正在执行的 schema 或账户。Eta 不因 `$ref`、组合关键字、条件关键字等复杂 Schema 禁用工具，而是原样投影给模型并在调用前按同一份 Schema 校验；现代 Streamable HTTP 的 `x-mcp-header` 参数会同步映射为请求头。

MCP 地址由用户直接配置，HTTP、HTTPS、局域网与本机地址使用同一条连接链路，并沿用共享 OkHttp 客户端的默认重定向和超时行为；HTTP 会明文传输 Token、工具参数和结果。Bearer Token 通过 Android Keystore 加密后保存在本机。MCP 原始参数与结果只在当前回合使用，持久 transcript、运行 checkpoint 和归档只保留脱敏记录；文本、结构化结果、图片、分页次数和单次 run 工具数仍有独立预算，不支持或超出预算的结果会携带明确标记。取消 run 会立即封闭新调用并关闭在途 HTTP 请求，legacy session 的释放只做异步 best-effort，不阻塞取消线程。

## 长期记忆

默认助手的现实长期记忆保存在 App 私有目录的 `MEMORY.md` 中。文件使用 UTF-8，安全上限为 1 MiB；仓库在进程内锁中应用变更，并通过 `AtomicFile` 覆盖完整文件。模型写入携带当前内容的 SHA-256 revision，revision 不一致时返回 `MEMORY_CONFLICT`，不会覆盖并发更新。角色剧情使用独立文件，并复用容量、原子写入和冲突检查规则。

每次 run 只把 `# 核心记忆` 的预算内内容、一级/二级标题索引和 revision 放入系统背景。核心预算为 `min(32000, max(4000, contextWindow / 16))` 个字符；模型窗口未知时按 128K 计算。没有 `# 核心记忆` 标题时不自动注入正文。其余内容由 `memory_get` 按行分页或按文本检索，单次最多返回 32000 字符。

`memory_write` 支持行区间替换、独立章节追加与清空；单次模型生成内容最多 3500 字符，设置页的用户手动编辑不受此单次工具限制。关闭记忆不会删除文件，后续 run 不再注入或暴露工具；已开始的 run 在每次执行记忆工具前也会重新检查开关。

记忆内容只作为可编辑背景，不具有指令优先级。记忆工具原始参数与结果可供当前 Agent Loop 使用，但对应工具调用在持久 transcript 中整体脱敏；运行事件只保存操作类型、行数、字节数和错误码，不保存正文或查询词。

## 本地工具能力合同

`AgentToolRequirements` 为每个本地工具声明 `NONE / PARTIAL / REQUIRED` Root 要求与无障碍、普通系统授权、ROM 条件；工具未登记元数据时不能进入模型目录。`AgentToolCapabilities` 每轮捕获设备条件，同一份投影后的 Schema 同时用于 Provider 声明与参数校验。元数据属于 Eta 内部，不扩展 Provider 协议。UI 聚合卡关联真实工具 ID，“全部能力”只改变展示。

没有 Root 时，专属工具彻底移除；混合终端仅公开 `identity=user`，设备默认路径与模型提示同步调整。执行器再次核查当前 Root 与参数，旧调用返回 `ROOT_REQUIRED`。普通前台 Intent 不要求无障碍；截图、节点、手势、输入和条件等待需要真实服务连接，已开启系统保护时保留有限修复链路。当前通知来自已连接的通知监听服务，断连返回明确错误，不以历史记录替代。用户选择保存在原有本地 Agent 配置与 RemotePreferences 协调链路中，能力变化不改写保存的开关。

Root 探测在 IO 线程执行：存在 `su` 时首次自动请求一次，最多等待 30 秒，仅 UID 0 视为可用；拒绝和超时不会反复弹出请求，用户可在“系统增强”手动重试。LSPosed 连接独立判断，不代替 Root 授权。

## 网页搜索与正文读取

本地 `web_search`、`fetch_url` 与 `browser_use` 共用既有 `browserTools` 权限；Responses 托管搜索启用时，本地同名搜索不再向模型公开，正文读取与浏览器仍可用。设置文案为“启用网页搜索、读取与浏览器”，持久化 key 仍是 `agent_browser_tools`，不新增搜索服务配置、凭据或 IPC 字段。关闭后目录不公开这三个本地工具，执行入口也逐次复查开关。它们无需 Root 或无障碍服务；模型请求、MCP 和 Provider 托管搜索仍走各自的配置与授权链路。

`web_search` 通过 `PublicWebSearch` 请求 DuckDuckGo 官方公开 HTML 搜索入口，不需要额外 API Key。当前只解析首屏结果，`max_results` 默认为 5、最多 10；返回目标站点的标题、原始链接和摘要，去掉搜索跳转包装并去重。结果携带 `scope=first_page`、采集时间、截断原因和跳过数量，不能把首屏结果当作完整搜索范围。验证挑战、HTTP 限流、编码失败、过大响应或无法识别的页面结构返回明确错误；只有识别到明确的无结果提示时才返回成功的空列表。公开站点可用性及页面结构会变化，工具不绕过人工验证。

`fetch_url` 使用匿名 HTTP GET，HTML 解析仅处理已下载文本，不执行 JavaScript，也不加载页面子资源。除 HTML/XHTML 外，还接受 `text/*`、Markdown、JSON 与 `application/*+json`；二进制或不支持的类型明确失败。字符编码按 BOM、响应头及可用的 HTML 声明判断；正文发生替换解码时标记 `decoding_lossy`，搜索结果则拒绝不可靠的解码。HTML 会移除脚本等非正文内容并提取有限链接；它不继承共享 WebView 的 Cookie 或登录状态，需要动态渲染、登录、表单或其他网页交互时使用 `browser_use`。

首次读取传 `url`，续页传返回的 `document_id` 与 `next_offset_chars`，两种来源必须且只能选择一个。分页读取同一份缓存正文，不重复 HTTP 请求，也不会混入网站随后更新的内容。快照只属于当前 run，最多保留 4 份文档且正文合计不超过 600000 字符，按最近访问情况淘汰；run 关闭会清空，失效或跨 run 的 ID 返回 `WEB_DOCUMENT_EXPIRED`。字符偏移须使用工具返回值，不能落在 Unicode 代理项对中间。`has_more` 表示缓存正文仍有下一页，`source_truncated` 表示下载或提取本身已丢失内容；读完所有页也不能消除来源截断。

网络与正文预算分别生效：

| 边界 | 当前限制与结果 |
| --- | --- |
| HTTP 请求链 | 总计最多 30 秒，最多跟随 5 次重定向；循环、超时和无效跳转明确失败，不自动重试。 |
| 响应正文 | 最多读取 2 MiB；搜索响应超限失败，网页正文读取保留截断标记。 |
| 文本解析 | 最多处理 512000 字符输入；搜索超限失败，网页提取标记 `input_limit`。 |
| 单文档正文 | 最多保留 200000 字符，超过则标记 `content_limit`。 |
| 单页正文 | 默认 12000、最多 16000 字符；链接与标题另有独立数量、文本预算和截断标记。 |

`WebHttpTransport` 只接受 HTTP(S)，拒绝带用户名或密码的 URL，并去掉片段部分。每一跳均检查协议、URL 凭据和重定向预算；允许访问当前网络可达的地址，不额外拦截 DNS、IP、局域网或本机地址，因此不能描述为网络安全沙箱。请求仍受系统网络授权和连接条件约束。取消或关闭运行会取消当前拥有的 HTTP 请求，正文提取和缓存读取也检查取消状态。参数、状态码及预算的事实源为 [AgentWebToolCatalog](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentWebToolCatalog.kt)、[WebHttpTransport](../app/src/main/kotlin/io/github/mangi/eta/agent/web/WebHttpTransport.kt) 与 [WebPageContent](../app/src/main/kotlin/io/github/mangi/eta/agent/web/WebPageContent.kt)。

搜索摘要、网页正文和链接均标记为不可信外部数据，不能修改工具权限或覆盖 Runtime 指令。回答引用搜索结果时使用标题与返回的原始目标 URL；引用已读取页面时使用标题与 `final_url`，不能把内部文档 ID 当作来源，也不能把搜索摘要冒充完整阅读证据。运行摘要仅显示动作、主机与计数，不回显查询词、完整 URL、正文或服务端错误原文；原始工具交换仍按普通工具结果进入模型上下文与会话记录。

## 结构化文件工具

文件工具统一由 `AgentFileToolCatalog` 声明，经 `FileToolDispatcher` 分派，`AgentFileOperations` 负责文本与编辑合同，后端负责当前环境中的路径、元数据和 I/O。模型可以直接调用以下工具，不需要为常规文件操作拼接 Shell 命令：

| 工具 | 当前合同 |
| --- | --- |
| `read_file` | 有界读取 UTF-8 文本，按字节或起始行定位，返回实际内容范围、`revision` 和 `next_offset_bytes`。 |
| `write_file` | 创建、完整覆盖或追加 UTF-8 文件，必要时创建父目录；支持 `expected_revision` 前置检查。 |
| `edit_file` | 精确替换 `old_text`；默认必须唯一匹配，只有显式 `replace_all=true` 才替换多处。 |
| `stat_file` | 查询规范路径、类型、大小与版本，不读取正文。 |
| `list_directory` | 返回直接子项的结构化分页，使用 `next_offset` 与目录版本继续列举。 |
| `glob_files` | 以 `*`、`?`、`**` 路径模式递归查找文件，支持游标续查。 |
| `grep_files` | 在 UTF-8 文本中搜索单行字面文本，返回文件路径、行号与有界片段；默认区分大小写，不使用正则表达式，也不依赖设备安装 `rg`。 |

文件操作整体受 20 秒时间预算和 Runtime 取消约束。所有文件工具共享 `environment`、`identity`、`cwd`。`environment=android` 默认 `identity=user`，以 Eta App UID 访问普通工作区与当前已授权的共享存储；Root 文件操作必须显式传 `identity=root`。这与终端保留的默认身份规则不同。`environment=linux` 使用用户选定的发行版及 PRoot/chroot 后端，路径和符号链接在该 Linux 环境中解释，默认工作目录为 `/workspace`；不能用宿主 rootfs 路径代替 Linux 内路径，也不会在失败后自动切换环境或升级身份。PRoot 内显示 UID 0 不意味着拥有 Android Root 权限。

`read_file` 单次可见文本最多 16000 字节，游标只跨过已完整解码的 UTF-8 字符。继续读取时使用返回的 `next_offset_bytes` 并携带 `expected_revision`，不能按请求的 `max_bytes` 推算下一段。非 UTF-8、二进制内容或落在字符中间的字节偏移返回明确错误。`max_lines` 与字节预算同时生效，超长单行可能分段并标记 `line_truncated`；`start_line` 定位也有扫描预算，`start_line_reached=false` 不能被当作已读到目标行或文件末尾。读取期间检测到版本变化时返回 `FILE_CHANGED`。

单次写入内容、精确编辑的原文件及替换后文件均受 512 KiB 上限约束。`edit_file` 对未匹配、多处歧义或版本变化明确失败，不写入猜测结果；提交前还核对读到的完整旧内容摘要。`revision` 是后端生成的不透明元数据版本，只能在同一环境与身份中使用，不是跨进程文件锁，也不能代替编辑时的内容检查。

写入返回 `atomic`，其含义取决于后端：

- Android 普通身份的 Java 文件后端在同目录暂存、同步内容后以原子移动覆盖；不支持原子移动时返回 `ATOMIC_WRITE_UNSUPPORTED`，不退回普通覆盖。追加写返回 `atomic=false`。覆盖前仅在权限确有差异时尝试保留原 POSIX 模式；必要的权限复制失败会保留原文件并明确报错。`atomic=true` 只描述目标路径替换的可见性，不承诺与外部写入者互斥，也不承诺保留原 inode、硬链接关系或全部文件属性。
- Android Root 与 Linux 的 Shell 文件后端通过既有 inode 写入，保留既有文件的属主、模式与 SELinux 标签，返回 `atomic=false`；中断可能留下部分写入。版本和内容检查不会把这种写入变成原子事务。

目录续页使用 `next_offset` 和 `expected_revision`；偏移指向原始目录枚举位置，隐藏项过滤可能使一页返回较少条目，不能据此认定结束。`has_more=false` 才表示列举完成。目录变化使游标失效。整份目录分页 JSON 最多 16000 字符，达到输出预算时停在尚未返回的条目前并保留续页位置；`stop_reason` 区分 `eof`、`entry_limit` 和 `output_limit`。底层目录枚举、单条路径或元数据超限则返回明确失败。

递归搜索默认跳过隐藏目录，不追踪符号链接；条目数、读取字节数、结果数量、输出文本及递归深度分别有预算。搜索另有约 5 秒的软时间预算，检查点达到预算时以 `time_limit` 返回已取得结果和续查游标；单次阻塞操作仍由整次 20 秒上限终止，硬超时、取消或执行进程失败不会伪装成普通文件跳过。继续搜索时原样传回 `next_cursor` 并保持查询条件、环境、身份不变。`partial`、`complete`、`stop_reason` 和跳过项共同说明覆盖范围；达到本轮扫描或结果预算并返回有效游标时可以续查，跳过二进制、不可访问项或过深目录等则保留不完整标记，空匹配不代表所有文件均不存在该内容。完整参数及限制以 [AgentFileToolCatalog](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentFileToolCatalog.kt) 与对应后端实现为准。

这些工具只接受文件系统路径，不直接接受或写回 `content://` 等文档 URI。选择器导入返回的是工作区副本，编辑副本不代表修改来源文档；App 文件页面的导入、导出与模型文件工具是不同入口。当前没有专用的文件复制、移动或文档导出工具。

## 结构化设备查询与操作

`inspect_app` 属于设备直达工具，优先通过 PackageManager 查询精确包名，不要求目标应用具有桌面入口。结果包含版本、UID、安装来源及路径、启用/停止状态与分页权限列表。查询仅针对 Eta 所属 Android 用户；`user_id` 缺失时使用该用户，指定其他用户或工作资料时返回 `USER_SCOPE_UNSUPPORTED`。缺少包可见性时不会把查询不到直接断言成未安装，安装来源不可读也会单独标记。

`get_logcat` 仍属于需要 Root 的敏感读取工具。它先按 PID、tag、最低 level 与 buffer 采集最近 `scan_lines` 条记录，再在样本中按 ISO 8601 的 `since` 和字面 `query` 筛选，最多返回 `max_lines` 条及有限文本。日志作用域为设备，不能用 `user_id` 冒充用户隔离。结果的采集起止时间、`scan_limited`、`capture_truncated`、`has_more` 与 `complete_within_scan` 分别说明采集和返回范围；`has_more` 只表示样本内有未返回匹配。日志源是环形缓冲区，`history_complete` 始终为 false，无匹配不代表更早记录不存在。

`get_setting` 与系统写操作统一指定或报告 `user_id`，不再把 Root 命令中的前台用户当作 Eta 所属用户；当前不提供跨资料后端。`global` 设置和 Wi-Fi/蓝牙属于设备级状态。`set_setting`、`set_device_state`、`app_state_control`、`set_volume` 返回 `before`、`after`、`expected`、`changed` 与 `verified`，命令退出 0 或 API 接受请求不再等同于目标状态生效。读回未达到目标或过渡状态尚未结束时返回 `STATE_CHANGE_UNCONFIRMED`；无法比较前后值时 `changed` 为 null。已有目标状态可以验证成功而 `changed=false`。设置原始值按敏感工具数据处理。

## 个人上下文与一方应用操作

工具页将“个人上下文”和“系统与应用操作”分组展示：前者查找和理解用户信息，后者修改日历、闹钟、便签和系统状态。分组描述产品职责，权限仍沿用设备直达、敏感读取、敏感操作等独立开关。模型直接调用领域工具，基本应用操作不依赖 Skills。

### 个人上下文

模型目录使用 `search_notes`、`search_system_memories`、`search_bills`、`search_flights` 等明确入口，不公开 `personal_context(action, source)` 万能工具。旧 `search_coloros_notes`、`search_coloros_memories` 名称只在执行层兼容，不重复出现在模型目录中。合同分别见 [AgentPersonalSearchToolCatalog](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentPersonalSearchToolCatalog.kt) 和 [AgentDeviceToolCatalog](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentDeviceToolCatalog.kt)。

- 媒体、文件、联系人、短信、通话、录音和便签等原始来源使用类型化 Provider 查询；日程读取使用 Calendar Provider。固定 URI、字段与参数由执行器管理，不向模型开放 SQL 或任意 Provider。
- `search_media`、`search_files` 默认按原始来源检索名称，`match=content` 查询 ColorOS 内容索引。`search_notes` 优先读取原始便签；来源不可用时可返回明确标记的历史索引，`current_only=true` 禁止这种回退。空游标接口与零条记录分别处理。
- 账单、待办线索、日历待办、记忆合集、生活事件和行程工具使用 ColorOS DMP 索引，需要 Root 与兼容的系统来源。来源可用性、字段覆盖与结果新鲜度以本次结果为准。索引可能延迟、遗漏或保留旧记录，不能证明“刚创建”“当前仍存在”；推断事件也不是已核实事实。
- 索引查询返回 `ref`，由 `read_personal_item` 读取详情。该引用不能作为日历 event_id、闹钟 alarm_id 或便签 UUID；操作前必须查找并核对原始对象身份。

索引查询关键词最多 200 字，单页默认 10 条、最多 30 条，偏移范围为 0 到 10000。时间使用带偏移的 ISO 8601，范围为包含起点、不包含终点的 `[start_time, end_time)`。记忆合集没有已确认的业务时间字段，不支持时间筛选。分页面对变化中的索引，不承诺跨页快照；结果分别报告续页、截断、字段缺失与时间语义。录音转写路径不等于转写正文，历史通知也不代表当前通知栏。

`summarize_bills` 最多完整处理 1000 条匹配记录，使用 `BigDecimal` 按元、CNY 汇总；超过上限、金额无效或输入截断时失败，不返回部分总额。收入、支出、转账和未知类型分列；负金额按原值累加，不推断退款净额。最多展示 20 个分类，其余合并进 `other_categories`。结果只覆盖匹配的系统索引，不承诺银行账本完整性。

原始个人数据参数与结果只供当前模型回合使用，不进入持久 transcript；模型组织的最终答复仍按普通会话保存。

### 一方应用操作

[AgentPhoneToolCatalog](../app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentPhoneToolCatalog.kt) 集中声明新增入口及其权限元数据。`agent/phone/` 按日历、时钟、便签和系统操作划分执行器；Root 通道以标准输入传输类型化请求，通过 `app_process` 调用 Eta 的固定入口，参数不拼入 Shell 命令。Root 身份使用真实调用归属，不冒充系统助手包名。

| 领域 | 能力与边界 |
| --- | --- |
| 日历 | 列出可写日历、读取日程、创建单条或批量日程、修改及删除。可使用普通日历读写权限或 Root；多日历时必须明确选择。事件和提醒在事务中写入，批量最多 30 条。当前不编辑重复事件，删除整组须显式指定 `scope=series`。全天时间使用 UTC 零点与排他的结束日期。 |
| 闹钟 | 保留标准 Intent 创建与计时器入口；兼容 ColorOS 的 Root 通道支持创建、读取、修改时间、启停和精确删除。修改时间保留原启用状态；显式震动参数使用标准 Intent，避免厂商接口忽略该参数。 |
| 便签 | 兼容 ColorOS 的 Root 接口支持创建、按 UUID 读取、移入回收站；尚不提供富文本修改。创建前检查读取接口是否就绪，解析业务返回值并读回核对，不能凭非空 URI 宣称成功。 |
| 系统 | 手电筒开关及状态；亮度、自动亮度、旋转、息屏时间、深色和护眼模式；音量、响铃和勿扰；Wi-Fi、蓝牙、移动数据、飞行模式、定位、NFC、省电和个人热点。具体 Root、权限和 ROM 条件由工具元数据与执行结果报告。 |

手电筒使用 CameraManager 回调核对状态；系统开关读取实际状态，应用操作读取原始记录。请求被接受、命令退出成功与状态已确认分别处理。写入后超时、响应不完整或读回失败返回 `unconfirmed`，应先查状态，不能自动重试创建。系统接口不可用时返回具体失败，不写未经确认的数据库字段冒充业务操作完成。

## 终端环境

模型目录中的命令执行统一使用 `terminal` 的 `action=exec`；`open` 创建会话后仍以 `exec/session_id` 复用。旧 `run_command` 与 `open_and_exec` 不再向模型公开，执行层保留旧入口供既有调用方兼容；新模型调用仍须通过本轮工具目录校验。`TerminalToolContract` 同时定义模型 Schema 和执行前校验，动作只接受其相关字段；使用 `session_id` 时不能再传 `cwd`、`identity`、`environment`，`async=true` 也不能复用持久会话。

`terminal` 的 `environment` 明确区分设备控制与通用 Linux 工具，默认值为 `android`：

- `android` 继续使用系统 Shell。终端未指定身份时保留当前可用身份规则：Root 可用则默认 `root`，否则 `user`；新文件工具则始终默认 `user`。`user` 是 Eta App UID，不等同于 ADB Shell，也不会因某条命令失败而升级权限。`root` 身份在 `su` 内探测 Magisk、KernelSU、APatch 或系统 BusyBox，并优先进入 standalone `ash`，因此 BusyBox applet 不要求预先加入 PATH。
- `linux` 解析用户选择的发行版和后端。chroot 保持原有 rootfs、独立 mount namespace、`/data/local/tmp/eta` 工作区与特权挂载。新建 PRoot 环境和普通工作区使用 App UID 独占的 `filesDir/terminal-user` 目录，避开旧 Root 目录的属主限制；已有普通环境继续使用原位置，路径统一由 `TerminalPrivateStorage` 解析，`/workspace` 映射该私有工作区。仅映射有权访问的共享目录，拒绝“所有文件访问”后仍可导入导出。Linux 内的模拟 root 不意味着 Android Root，两个后端都不构成隔离安全沙箱。
- 已建立会话和任务保存后端与实际 rootfs/工作区，不因 Root 变化自动切换。持久任务记录的后端与宿主工作区字段为可选，兼容旧记录。获得 Root 不迁移 PRoot，失去 Root 不删除 chroot 或改变文件属主。
- 普通 Android Shell 与文件后端使用 App UID；显式 Root 文件操作使用特权路径。图片读取沿用自身的授权文件引用规则。无法直接访问的选择器文件经有界复制导入工作区；目录选择不能冒充可实时访问的路径。

终端在实际命令 Shell 中采样 `runtime` 元信息，包括 `host_identity`、`uid`、`uid_scope`、`shell_provider`、`shell_executable` 与一组命令的解析结果。Linux 的 UID 属于 guest 环境，不能据此推断宿主权限；可解析到 `cmd`、`pm` 或 `dumpsys` 也不代表当前身份获准访问对应系统服务。采样不完整时报告缺失，不根据设备版本臆测 Bash、GNU 工具或 `rg`。Android 原生进程仍受 [App UID 沙箱](https://source.android.com/docs/security/app-sandbox) 约束，Root 进程也受 [SELinux](https://source.android.com/docs/security/features/selinux) 策略影响。

同步命令超时会终止执行，会话内超时同时关闭会话。异步命令通过 `read_async_result` 的 `next_offset_chars` 继续读取；`truncated` 表示保留输出尚未读完，`output_truncated` 表示采集或展示额度导致内容已丢失，两者不能混淆。`close_if_done` 只在任务已结束且当前页达到保留输出末尾时释放任务。取消 run 会封闭新调用并回收其同步进程、持久会话和异步命令；守护任务另按后台生命周期管理。

用户在 Alpine 与 Debian 中选择一个当前 Linux 发行版，模型与终端统一通过 `environment=linux` 使用该选择。基础环境安装与基础工具安装是两个独立步骤：安装器先下载固定版本、大小和 SHA-256 的 rootfs，在临时目录解压，运行检查成功后才写入基础完成标记；PRoot 的流式解包校验归档路径和链接，支持取消与失败清理；用户随后安装只含通用命令的基础工具集。Python profile 只安装 uv，随后由 uv 把最新正式版 Python 安装到 `/opt/eta/python` 并把全局命令链接到 `/usr/local/bin`。Node.js profile 在 Debian 安装上游最新正式版 ARM64/x64 制品，在 Alpine 安装稳定分支提供的 `nodejs-current`；SSH 使用所选发行版的最新稳定包。App 侧只读取安装器完成标记，不再重复检查 rootfs 内的符号链接、二进制或执行权限。中国大陆网络下，Alpine 使用阿里云镜像，Debian 主仓库使用清华 TUNA、安全更新使用 Debian 官方源，各自只保留官方主仓库作为失败出口；APT 还启用重试并关闭 HTTP pipelining。

APK 分析在 Alpine 与 Debian 中都作为可选档案显示。JADX、Apktool、smali 与 baksmali 使用当前最新正式版的固定官方 Release URL、大小和 SHA-256，下载完整校验后才进入 App 可写的 cache staging；不能把下载或解包暂存目录放进由 Root 创建的 Linux 管理目录。GitHub 制品先尝试一个 HTTPS 下载入口，再回到官方地址，但仍只接受与官方清单 SHA-256 完全一致的字节。JADX 只解出 CLI 脚本、运行库与许可证，成功验证全部命令后再原子切换当前版本。档案在 Alpine 安装 `openjdk25-jdk`，在 Debian 安装 `openjdk-25-jdk-headless`，但不安装全局 Gradle、Android SDK 或 NDK。由于 Google 的 Linux SDK、AAPT2 与 NDK 主机工具只提供 x86_64 构建，手机 ARM64 chroot 无法原生组成受官方支持的完整 Android 编译链；`apktool build` 因而稳定拒绝，解码、代码查看和独立 Smali 汇编/反汇编不受影响。

## 后台执行生命周期

`AgentExecutionService` 使用 `specialUse` 前台类型，为当前 Agent 运行、普通终端和 PRoot 后台进程持有任务引用。用户退出页面只断开 UI；最后一个任务结束时服务释放，通知中的停止操作回收它实际持有的任务。普通后台任务保持宿主 tracer 与输出读取，不能像 Root daemon 那样脱离 App 生命周期。Root daemon 保持原有独立生命周期，普通任务清理不会批量停止 Root daemon。Root 用户的原有 Runtime 绑定链路在新增前台服务启动受限时仍可继续，不因新增服务阻断厂商助手入口。

`daemon_start` 表达跨 Agent run 的服务生命周期，不是永久存活保证；任务可能自行退出，也可能受 Android 后台限制、宿主进程回收、权限变化或设备重启影响。`daemon_list`、`daemon_logs` 与 `daemon_stop` 用于查询和管理实际任务，不以启动时拿到 `task_id` 代替后续存活检查。

Kimi 使用 `kimi web --no-open`，按发行版及后端复用活跃实例。启动失败或取消只清理本次新建的进程；复用实例保留。服务使用 `START_NOT_STICKY`，系统强停或重启后不自动重放命令。通知授权被拒绝不会直接阻止合法前台启动，但系统后台启动限制与厂商进程回收策略仍然生效。

## 上下文与续接

App 在发起请求前已经把当前用户消息写入会话 history，因此 Runtime 返回的 transcript 必须保持“增量”语义。已完成 run 的补充请求由 `AgentContinuationBuilder` 使用以下顺序重建上下文：

```text
旧 history
→ 原始用户消息
→ 完整增量 transcript
→ 新补充消息
```

图片只在需要它的当前模型回合中传递；持久 transcript 会删除图片正文并写入稳定的省略说明。外部入口归档可另外保存小预览用于还原用户消息 UI，预览不会重新进入模型历史。敏感工具及 MCP 的原始参数、结果仍只在当前运行内存中使用；普通用户文本、模型回复、工具调用与结果不因长度被截断。

完整脱敏历史 `journal`、可替换的模型上下文 `history` 和展示消息分别保存。Room 的大文本按小行分块存储，主记录仅保存分块引用；DAO 在同一事务中更新主记录与分块，读取时验证顺序与完整长度，删除所属记录时清理分块。分块大小限制单行，不限制会话总长度。数据库迁移完整搬迁现存历史，不能恢复已被旧版本丢弃的内容。

App 按上次成功提交的内容比较会话变更，只更新变化的元数据、上下文字段和展示消息。追加消息保留已有记录；编辑、重排或删除时替换变化位置之后的消息，同一事务清理对应分块并更新已应用标记及会话选择。保存失败不推进比较基线，后续可以重试。流式占位等未入库消息会在排序中留下空洞，重启加载后列表下标与库内排序不一致的会话不进入基线，首次保存整段重写并重排。导入备份期间暂停会话保存，未保存的结果不回执；导入成功后按库内数据整体重载，失败时数据库已回滚，恢复保存并补写暂停期间的变更。加载逐会话读取和转换消息，避免同时保留所有会话的数据库行副本；当前工作台仍会持有已加载的会话状态。

整体备份沿用 JSON 格式，兼容既有备份。导出在一致的数据库事务中分批读取主记录，逐条恢复分块文本并编码到临时文件；完整生成后再复制到用户选择的目标。导入先在 App 缓存中暂存记录数组，逐条校验标识、所属会话和角色关联，再在事务中恢复数据库，并保留记忆文件失败补偿。导出与导入共用大小限制，以 `EtaBackupJsonStreams` 为准；备份不再依赖整份 JSON 字符串或字节数组。临时文件在成功、失败和取消时清理，设备需要足够的临时磁盘空间。保存与备份失败日志记录操作步骤、异常类型及有界调用位置，不记录异常消息、聊天正文或凭据。

新客户端通过只读文件描述符传递大段请求历史及完整结果，在后台校验并物化；临时文件打开后取消目录链接，发送端与接收端分别管理描述符所有权。同进程 Messenger 也显式复制描述符，不能依赖跨进程 Parcel 的自动复制。Binder 保留实际 Parcel 预算，文件传输另有单次内存预算；超限或传输不完整时明确失败，不能截断后冒充成功。完整结果仍在持久存储中。旧协议内联字段仅提供带缺失提示的兼容投影，新客户端优先读取完整载荷。

浮层在已完成结果后发起的 continuation 会在 handoff 中只携带本次新增的 prompt supplement，不累计复制旧补充。App 回到前台时 drain outbox，把该用户消息和增量 transcript 一起写回 history。

### 上下文摘要

模型窗口只采用用户在设置中填写并保存的值。官方目录和远端窗口元数据不参与运行窗口解析，不自动补齐缺失配置。未填写窗口时照常对话，只关闭自动压缩；手动压缩仍可用，服务商返回上下文溢出时按 `CONTEXT_OVERFLOW` 报告。记忆、世界书等按窗口比例分配的注入预算在窗口未知时以 128K 为基准。

设置中的“上下文与扩展”提供“自动压缩上下文”开关，默认开启；配置保存在 App 本地，不依赖 Xposed，聊天与系统助手入口共用，从下一次运行生效。关闭后保留手动压缩。Runtime 会核对本地设置，入口请求不能重新开启已关闭的自动压缩。

自动压缩只依据当前成功模型请求返回的实际输入 usage，达到用户填写窗口的 85% 时，在完整工具批次结束后的下一次请求前或任务完成后触发。首次请求前不估算文本、工具或图片的 token 数；缺少实际输入 usage 时不猜测容量，也不采用累计用量或输出用量代替输入。失败重试的 usage 不沿用；压缩成功后旧用量失效，等待下一次模型响应更新。

压缩使用当前会话模型，额外请求会计费。摘要请求禁止本地及托管工具，也不接受自定义正文覆盖其输入；输入移除敏感工具原始参数、结果、图片正文与 opaque reasoning。自动与手动压缩均覆盖可安全替换的已完成历史，保留当前用户指令与未消费图片。主会话切分只能发生在完整工具批次之间。

摘要一次发送全部待整理的脱敏历史，不按估算大小分片，不进行细分或网络重试。请求设置覆盖连接、服务端处理和流式读取的总超时，SSE 心跳不能无限延长等待。只有完整、有界且实际序列化文本缩小的摘要才提交；溢出、空摘要、截断、过滤、工具调用、超时或取消均保留原文，不提交半成品。

摘要作为带有明确说明的 assistant 历史保存，不提升为系统指令。成功后重建模型上下文，保留系统约束及近期规范化消息；被压缩的原文始终保留在完整脱敏历史中，不用摘要覆盖。Responses 的旧 opaque output Items 不跨越压缩边界，也不跨 run、跨 Provider 持久化。

上下文替换快照保存版本、操作标识、用户轮次和覆盖的 transcript 边界。完整工具批次的脱敏 transcript 独立写入在途检查点；快照先落盘，再替换运行上下文。快照后产生的增量在恢复时按边界接回，避免崩溃后遗漏已完成步骤。终态结果和归档保留完整 transcript 与快照；批量 drain 每批只列出有限条结果和引用，客户端校验 run 与 handoff 归属后单独读取完整结果。App 串行提交历史、模型投影及已应用标记，成功落盘后才 ACK。未确认结果和待导入归档不按年龄或数量淘汰。

App 会话提供 `conversation_history` 工具，搜索或分页读取当前会话的完整脱敏原文。工具由 Runtime 绑定会话身份，模型不能指定其他会话；单次输出有界并返回续读游标，大消息可按字符偏移继续读取。它不依赖 MEMORY.md 开关，也不向模型暴露数据库路径。摘要仍然有损，历史工具让模型能按需核对摘要省略的细节；完整存储不代表每次请求都把所有历史塞入模型窗口。编辑或删除旧轮次时从完整历史重建对应前缀，旧版本已缺失的前缀沿用明确提示。

空闲会话可从上下文用量提示框手动压缩，执行期间本会话禁止发送、模型切换和历史编辑，可停止或浏览其他会话。手动操作不生成虚构用户消息或模型回答；摘要正文不进入思考流、事件或日志，只展示压缩状态。压缩后不显示估算用量，下一次模型响应返回实际 usage 后更新。

Provider 明确返回上下文溢出时直接报告失败，不自动压缩后重放原请求。空正文以长度上限结束时报告输出额度耗尽；内容过滤和普通空响应分别明确失败，不猜成输入溢出。已开始托管工具的请求不自动重放。失败或取消不提交半成品摘要，已经提交的安全快照随取消或失败结果保留。任务已完成时，压缩失败不改变任务成功状态，原始上下文完整保存；实际持久化失败仍报告失败，不用删头方式掩盖。用户停止时先取消网络与工具，收束运行并保存已完成的安全历史，再交付取消终态。

设计依据：[Android Binder 事务限制](https://developer.android.com/reference/android/os/TransactionTooLargeException)、[ParcelFileDescriptor](https://developer.android.com/reference/android/os/ParcelFileDescriptor)、[CursorWindow](https://developer.android.com/reference/android/database/CursorWindow)。参考的会话模式见 [pi 的追加式压缩记录与上下文重建](https://github.com/earendil-works/pi/blob/b215884021491772a1eb7a9f92c6653a2a52a69d/packages/coding-agent/docs/compaction.md) 和 [Kimi Code 的历史恢复指针](https://github.com/MoonshotAI/kimi-code/blob/b1807253c34e12b0ecf60c9b4da3890d0c80ce72/packages/agent-core-v2/src/agent/fullCompaction/contextRecovery.ts)。Eta 使用 Room 分块及当前会话读取工具适配 Android，不依赖桌面文件路径。

## Skills 安装边界

Skill 安装工具始终向模型提供，不再根据顶层用户输入的固定关键词决定是否暴露或执行。网页、仓库 README 和已安装 Skill 仍只是数据，不能改变工具参数或执行边界。

- AI 安装只访问公开 GitHub HTTPS 地址；curated 默认来自 `openai/skills` 的 `skills/.curated`。安装路径必须来自当前 run 对同一仓库与 ref 的检查结果，最多 20 个。
- 本地 ZIP 由 Skills 页面通过系统文件选择器读取，不申请共享存储权限，也不把归档复制到公开目录；每个 ZIP 只允许包含一个 Skill。
- GitHub 下载与本地 ZIP 共用受限解包和校验流程：拒绝路径穿越、绝对路径、重复条目、嵌套 Skill、非法 frontmatter，以及超过条目数、单文件、归档或总解压预算的输入。
- 安装先在 App 私有临时目录完整验证，再提交到正式 Skills 目录。文件系统与 Room 变更由持久事务日志协调，进程异常退出后会在下次变更前恢复；批量安装任一步失败都会回滚。同名用户 Skill 默认保持不变；GitHub 单冲突替换绑定仓库、提交、路径和 Skill ID，可在同一 run 精确重试；内置 Skill 永远不能被导入包覆盖。
- 安装只保存文件、登记索引并默认启用，不执行 `scripts/`，也不改变终端/文件工具开关。本轮 Skill 索引在模型调用前已经冻结，因此新 Skill 从下一轮对话开始可用。

已安装 Skill 的附属文本资源通过独立的有界读取工具访问，读取时再次做相对路径、canonical root、UTF-8 与大小检查；脚本和二进制 asset 不会借此被执行或当作无限文本送入上下文。

待确认结果和外部入口归档会把完整脱敏 transcript 一并写入 Room。显式迁移为旧记录补默认字段并搬迁大文本；分块读写集中在 DAO，协议投影集中在 Runtime，Provider 不负责持久化。恢复幂等性依据已应用 run 标记，不能靠比较 history 尾部文本猜测。旧结果缺少 transcript 时仍可用已有 assistant 内容合成兼容历史。

主界面的 `AgentAppState` 由 Activity 级 ViewModel 持有，配置变更只重建 Compose UI，不替换正在等待 Runtime 的客户端。用户消息先提交到 Room，再启动可能产生设备副作用的 run。Runtime 为 App 会话维护追加式在途 checkpoint：文本增量有界合并，结构化边界先落盘再发布；块结束通常只保存边界和字符数，仅在终态修正流式内容时保存替换正文。工具调用的原始参数增量和原始结果不进入 UI 事件日志，UI 已展示的参数摘要、脱敏终端命令与结果摘要会随工具状态保存；完整普通工具交换另存于脱敏 transcript。终态先封存 checkpoint 再提交 outbox，只有会话成功落盘并 ACK 结果后才同时删除 outbox 与 checkpoint。

App 恢复时以 `checkpoint + outbox + active session` 统一对账，不再用进程是否变化推断 run 状态。有 outbox 时先恢复工具轨迹，再用终态结果定稿；Runtime 仍 active 时，新 UI 会先整体恢复内存中的安全事件，再订阅实时事件与最终结果；只有既无终态又不 active 的 run 才标记为中断。恢复不会自动重放任何工具，也不会把半截助手回复加入后续模型 history。

重新订阅沿用已有的 attach 响应作为历史回放结束边界：Runtime 在同一会话锁内依次发送安全历史、成功响应，再加入实时订阅，实时事件与终态不能越过此边界。App 客户端在响应前缓冲历史并一次性交付 UI，UI 在一个状态快照中重建该 run 的消息投影；只有边界后的新增内容进入实时更新。旧服务若先发送终态，客户端先交付已缓冲历史再交付结果。恢复前清理可重建的旧投影，保留原始用户请求和没有对应回放事件的补充内容，避免重复追加或丢失用户输入。任务终态独立于文字显现状态，最终结果会收口尚未结束的文字标记；缺少结果的工具记录显示未知状态，不伪造成功。

## 验证

核心回归测试位于：

- `AgentModelClientLoopTest`
- `AgentConversationCodecTest`
- `AgentRunControllerTest`
- `AgentContinuationBuilderTest`
- `AgentRuntimePolicyTest`
- `AgentRuntimeSessionTest`
- `AgentRunCheckpointStoreTest`
- `AgentRunMessageProjectorTest`
- `AgentToolCatalogTest`
- `McpProtocolValidationTest`
- `McpRunContextTest`
- `AgentMemoryStoreTest`
- `AgentMemoryContextBuilderTest`
- `EtaDatabaseMigrationTest`

最终验证仍运行项目统一命令：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```
