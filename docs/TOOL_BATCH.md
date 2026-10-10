# 批量工具调用

Eta 提供 `batch` 工具，在一次模型调用中执行最多八项工具。`auto` 模式用于独立只读查询，`sequential` 模式可按顺序执行当前目录里的任意工具。运行器也会对模型原生返回的多个工具调用使用同一套调度规则。已有客户端、会话 DTO 和 Provider 协议不变。

工具能力页在“应用与系统”分组展示“批量执行”，当前设备页不需要 Root 就会显示。每个子工具沿用自己的开关与权限。英文、简体中文和繁体中文名称与说明同步。

## Hermes 源码核对

2026-10-09 核对 NousResearch/hermes-agent 的主线，固定在提交 `1e0c7730d791f5ce855c5c78935cfea6fb1e43a9`。涉及三种不同机制：

- [工具批次调度器](https://github.com/NousResearch/hermes-agent/blob/1e0c7730d791f5ce855c5c78935cfea6fb1e43a9/agent/tool_dispatch_helpers.py)：把同一条模型响应拆为顺序执行的片段；允许独立读取并行，交互操作和冲突操作构成屏障。文件调用还按路径重叠判断冲突，MCP 并行需要服务端声明支持。
- [运行器入口](https://github.com/NousResearch/hermes-agent/blob/1e0c7730d791f5ce855c5c78935cfea6fb1e43a9/run_agent.py) 和 [工具执行器](https://github.com/NousResearch/hermes-agent/blob/1e0c7730d791f5ce855c5c78935cfea6fb1e43a9/agent/tool_executor.py)：执行多个原生 tool calls，并保留调用顺序、逐项执行策略及中断处理。
- [`execute_code`](https://github.com/NousResearch/hermes-agent/blob/1e0c7730d791f5ce855c5c78935cfea6fb1e43a9/tools/code_execution_tool.py)：Python 代码通过 `hermes_tools` 调用当前会话允许的工具子集，在一次调用内执行循环、分支和结果筛选；默认 50 次子调用、300 秒、50 KB stdout 预算。它需要 Python 执行环境及 RPC 权限桥。
- [Connector batch](https://github.com/NousResearch/hermes-agent/blob/1e0c7730d791f5ce855c5c78935cfea6fb1e43a9/tools/connectors/batch.py) 是连接器内部批处理，并不是任意 Android 工具的通用包装器。

该版本没有注册名为 `batch` 的通用工具。Eta 采用其中的独立调用调度思路，并提供显式 `batch` 包装，方便一次只发一个 tool call 的模型提交多个查询或顺序操作。实现直接复用 Kotlin 工具路由，不引入 Python 内核。

## 调用方式

```json
{
  "calls": [
    {"tool": "search_apps", "arguments": {"query": "微信"}},
    {"tool": "search_apps", "arguments": {"query": "地图"}}
  ],
  "mode": "auto"
}
```

- `calls` 必须有 1–8 项；每项的 `tool` 来自本轮实际公开的允许列表，`arguments` 使用该工具原有参数。
- `mode=auto` 仅接受下表的只读子集，默认启用安全并行，每组最多 4 项；`mode=sequential` 接受本轮目录里的任意工具，严格按输入顺序执行，包括 GUI、文件写入、终端和动态 MCP 工具。
- `on_error` 可为 `stop` 或 `continue`。顺序模式默认 `stop`，首项普通失败后其余项返回 `BATCH_STOPPED_ON_ERROR`，不调用处理器；只读自动模式默认 `continue`，收集所有查询结果。显式 `stop` 总是逐项执行，即使 `mode=auto`。
- 先验证整个包装和所有子调用的 Schema，任一参数不合法则整批不执行。
- 执行时仍逐项经过现有工具路由，检查工具开关、Root、系统权限和取消状态。运行中权限变化可能导致某一项失败。
- 返回顶层 `ok`、`total` 和按输入顺序排列的 `results`，每项包含 `index`、`tool`、`ok`、`result`。失败或跳过不会丢弃前序结果；只有所有项都成功时顶层 `ok=true`。
- 同一条模型响应原有的多个 tool calls 不改变协议形态，结果逐项顺序写入 transcript。`batch` 在协议上仍对应一条父调用及一条聚合结果，子调用使用独立的工具进度事件。

顺序执行示例，后一步参数均已确定：

```json
{
  "calls": [
    {"tool": "write_file", "arguments": {"path": "report.txt", "content": "任务完成"}},
    {"tool": "read_file", "arguments": {"path": "report.txt"}}
  ],
  "mode": "sequential"
}
```

若两步互相独立，可以显式使用 `on_error=continue`；依赖前一步成功的操作保留默认停止策略。

## 范围与顺序

| 工具 | `auto` | `sequential` |
| --- | --- | --- |
| `search_apps`、`web_search` | 已公开时独立查询可并行 | 已公开时按顺序执行 |
| `tasks_triggers`、`tasks_list`、`tasks_history` | 已公开时独立查询可并行 | 已公开时按顺序执行 |
| `read_file`、`stat_file`、`list_directory`、`glob_files`、`grep_files` | Android / user 可并行；Root 或 Linux 顺序执行 | 已公开时按顺序执行 |
| `fetch_url` | 按顺序执行，保留分页缓存语义 | 已公开时按顺序执行 |
| GUI、截图、写入、终端、共享浏览器、记忆、其他本地工具、MCP | 不支持 | 已公开时按顺序执行 |
| 未公开或被运行限制移除的工具、嵌套 `batch` | 不支持 | 不支持 |

原生多工具调用的运行器只并行相邻的安全读取，任何屏障两侧的读取都不会跨越屏障。例如 `read_file A → read_file B → write_file A → read_file A` 中，前两次读取可并行，写入等它们完成，末次读取等写入完成。Root/Linux 文件调用也保持屏障，因为它们涉及共享 Shell 环境和权限状态，保留顺序语义。

需要先知道文件列表、网页链接、分页位置、观察 ID 或点击后界面，才能确定下一步参数时，应等待本批结果后再调用工具。包装器不提供结果变量引用、循环或条件分支。两个查询是否在业务上依赖仍需模型判断；`auto` 的白名单只保证处理器不存在已知的共享写入。终端 `async=true`、任务启动等异步工具返回时只保证提交顺序，不能把提交成功当作后台任务已完成。需要新 session ID、job ID 或任务结果时，应分到下一轮调用。

虚拟屏点击、滑动和观察可进入顺序 batch。现有可信 `ToolStop` 始终停止后续执行，不受 `on_error=continue` 影响；剩余调用补 `UI_EXECUTION_PAUSED` 拒绝结果，再走现有的一次重启和重新观察逻辑。普通结果 JSON 中伪造的暂停字段不会成为控制信号。

## 取消、恢复和隐私

并行工具的参数校验、进度事件、敏感 ID 和 transcript 写入都在 Agent loop 线程执行；工作线程只调用已有的工具处理器。线程池和 future 注册到 `AgentRunController`，取消时关闭并行任务，后续分组不启动。底层 HTTP/文件工具保留各自的超时和资源清理规则。

原生多工具调用已按顺序发布的结果会保留；没有取得完整结果的调用用 `TOOL_INTERRUPTED` 补齐，恢复时不能自动重放。显式 batch 在整批完成后生成一条聚合结果，子调用不产生独立的协议 tool message。

顺序 batch 可能修改状态，因此执行前后均发布父调用的脱敏进度检查点，保存子工具名称、索引和固定状态，不保存参数与原始结果：

- `completed`：调用已返回成功，不能整批重放此前动作；异步提交和 GUI 工具成功仍需按原工具语义核实实际效果。
- `failed`：调用已返回失败，需结合现场核实是否有部分效果。
- `skipped`：因前序失败或保护性暂停而未执行。
- `unknown`：调用已进入，但未取得完成记录；中断恢复后先核实效果。
- `not_started`：尚未开始。

执行中检查点标记 `TOOL_INTERRUPTED`；最终持久工具结果也保留这些状态。当前模型上下文继续得到完整原始聚合结果。取消会留下最后的进度检查点，下一项不会启动，续跑不能自动重放未知或已完成动作。进度持久化失败也会先终止，再由现有中断恢复处理。

`batch` 参数可能包含多个查询词、私有路径或任务内容。父调用从首次 transcript 发布起按敏感工具处理，持久会话不保存原始参数和结果；当前模型上下文仍得到完整结果用于回答。工作过程为父调用展示模式与数量，为子调用复用各工具原有摘要规则。

## 收益与验证

收益来自两个方面：显式包装可以减少逐项查询的模型往返；已在同一条响应里的多个独立读取则可以减少串行等待。它不保证输出 token 减少，也不能省掉需要中间结果的模型回合。

`AgentBatchToolTest` 和 `AgentToolBatchPlannerTest` 覆盖并发进入、结果顺序、四线程上限、八项预算、修改屏障、Root/环境边界、目录限制、参数预检、单项失败、取消和敏感内容脱敏。顺序模式额外覆盖 GUI/文件/终端/MCP 执行顺序、默认失败停止与显式继续、未开始修改前的全批预检、受限工具不可越权、部分执行检查点、虚拟屏暂停后跳过剩余修改并恢复一次。`ToolCatalogUiTest` 验证无 Root 的当前设备页显示 batch 卡片。现有 `AgentModelClientLoopTest` 同时验证虚拟屏暂停、一次恢复、连续工具结果、steering、Provider 重试及有签名的工具目录保持规则。

2026-10-09 验证：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过；1715 项 JVM 测试，0 失败、0 错误、2 跳过。新增七项顺序模式回归与一项能力卡片回归，批量核心测试共 23 项。Lint 0 错误，397 条警告和 1 条提示。

随后补充 OpenAI 请求过滤本地批次检查点字段，重新验证 Batch、会话编解码、Agent loop 与 Chat Completions Provider 共 67 项测试，全部通过；Debug 构建和 Lint 再次通过。Anthropic 与 Responses 原有的逐字段请求投影不带本地检查点元数据。

已通过无线 ADB 覆盖安装到 vivo V2419A（Android 16 / OriginOS），校验手机安装包与本地 Debug APK 的 SHA-256 一致：

`58064a2044758fc4c08893f6ee49bb389581df9ca1846d0b73cc83775a3ce66a`

顺序工具执行和能力卡片使用 JVM/Robolectric 回归验证。本次未让真实模型执行手机上的 GUI、写入或 MCP 操作，也未重新测量真机批量性能。
