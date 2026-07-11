# Agent Runtime

Eta 的 Agent Runtime 负责把一次用户输入组织为模型回合、工具执行和可持久化的增量 transcript。它运行在模块自身进程；Hook 进程只负责识别入口、发送请求和接收结果。

## 代码边界

- `AgentModelClient`：稳定门面、配置与跨进程会话 DTO。
- `AgentLoop`：单次 run 的状态机，不依赖 Android Service、Room 或 Compose。
- `AgentPromptBuilder`：系统约束、Skill 索引、历史和当前用户输入。
- `AgentConversationCodec`：Provider JSON 与稳定会话 DTO 的转换。
- `AgentToolCatalog` 及分组目录：模型可见的工具 schema，不执行工具。
- `AgentTraceFormatter`：只生成可展示、可记录的脱敏摘要。
- `AgentProviderClient`：OpenAI-compatible、Anthropic 等协议边界。
- `AgentRunController`：取消、暂停和 steering 队列。
- `AgentRuntimeSession`：每个 run 自持 reply channel，并保证唯一最终结果。
- `AgentRuntimeRunExecutor`：从 Skill/工具初始化到模型执行、资源清理和终态提交的统一异常边界。
- `AgentRuntimeService`：Android 生命周期、入口 IPC 和浮层宿主；不再内联 Agent 执行循环。
- `ShellProcessSupervisor`：Shell 进程的接纳、独立进程组、取消和回收；终端协议不承担进程所有权细节。

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
- 工具参数在执行前按本轮实际下发的 JSON Schema 重新校验。模型输出不是可信输入，缺少坐标等必填字段时不得调用设备执行器。
- transcript 只返回本次 run 新增的 assistant、tool 和运行中 steering 消息，不重复旧 history 或本轮初始用户消息。
- GUI/终端工具保持串行。Android 前台状态和会话式 Shell 都不具备可安全并行的通用语义。
- 单次 run 当前最多 64 个模型回合、256 个工具调用，防止异常模型形成无界循环。
- 最后一个允许回合不会再启动工具副作用，因为其结果已经没有下一回合可以消费。
- cancel 是终止信号；pause 是检查点阻塞；steering 是下一回合输入。三者不能互相模拟。
- cancel 的主线程路径只做原子终态与资源关闭：共享浏览器按 runId 校验归属；终端立即封闭新的进程接纳，并在后台按独立进程组终止同步命令、会话和 async job，再完成线程与流回收。Android 上 `setsid` 或 PID/PGID ownership 握手不可用时会 fail closed；非 Android 测试环境才允许父子树快照回退。终止前还会核验随机 ownership token，避免陈旧 PGID 复用后误杀无关进程。
- 最终 steering 检查会原子关闭接收入口；Loop 返回后不会再把无人消费的补充指令误报为已接收。补充指令也不会解除 pause。
- 新 run 替换旧 run、用户取消和正常完成都通过 `AgentRuntimeSession` 的 `RUNNING → COMMITTING → TERMINAL` 状态机竞争唯一终态；提交胜者独占 outbox、归档和最终发布，客户端另有 30 分钟兜底超时。
- 入口请求只能缩小工具能力，不能自行授权。Runtime 在开始 run 时裁剪配置，在每次浏览器/终端执行前重新读取用户开关，并在 thinking 关闭时移除自定义请求体中的 reasoning/thinking 覆盖字段。

状态机语义研究参考了 MIT 许可的 [earendil-works/pi（研究时固定提交）](https://github.com/earendil-works/pi/tree/4c1861033b63a04563547ccdb5ed2bf31d4fdcd3)，Eta 按 Android Runtime、既有 IPC 和 Provider 协议做 Kotlin clean rewrite，没有直接引入其 TypeScript 运行时。

## 上下文与续接

App 在发起请求前已经把当前用户消息写入会话 history，因此 Runtime 返回的 transcript 必须保持“增量”语义。已完成 run 的补充请求由 `AgentContinuationBuilder` 使用以下顺序重建上下文：

```text
旧 history
→ 原始用户消息
→ 完整增量 transcript
→ 新补充消息
```

图片只在需要它的当前模型回合中传递；持久 transcript 会删除 data URL，并写入稳定的省略说明，避免截图 base64 同时膨胀 Binder、Room 和后续上下文。启动请求在发送前按实际 `Parcel` 大小校验，超过 768 KiB 时会明确拒绝并提示减少图片数量或分辨率。持久 transcript 上限为 100 万字符，直接 IPC transcript 上限为 9.6 万字符；outbox 批量 drain 使用更紧的单项预算，确保最坏 8 条待交付结果仍处于 Binder 事务预算内。任何容量压缩都会在保留的 history 前插入明确的 Eta system notice，不会把删头后的 transcript 冒充成完整上下文。

浮层在已完成结果后发起的 continuation 会在 handoff 中只携带本次新增的 prompt supplement，不累计复制旧补充。App 回到前台时 drain outbox，把该用户消息和增量 transcript 一起写回 history。

自动重试、上下文压缩和跨 Provider 的 opaque reasoning 状态尚未由当前 Loop 冒充实现；它们应位于 Loop 之外的 session 编排层，并各自拥有明确的持久化与测试合同。

待确认结果和外部入口归档会把 transcript 一并写入 Room。数据库 6 → 7 使用显式非破坏迁移为旧记录补 transcript，7 → 8 为会话增加已应用 run 标记。恢复幂等性不再靠比较 history 尾部猜测；保存任务严格按调用顺序串行，只有包含对应标记的快照落盘后才 ACK outbox。旧 6.x 结果仍可用已有 assistant 内容合成兼容 history。

## 验证

核心回归测试位于：

- `AgentModelClientLoopTest`
- `AgentConversationCodecTest`
- `AgentRunControllerTest`
- `AgentContinuationBuilderTest`
- `AgentRuntimePolicyTest`
- `AgentRuntimeSessionTest`
- `AgentToolCatalogTest`
- `FuckAndesDatabaseMigrationTest`

最终验证仍运行项目统一命令：

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```
