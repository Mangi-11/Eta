# 技术实现

模块按进程与功能域安装针对性的 Hook。入口只负责生命周期、进程筛选、配置注入与安装结果汇总；具体目标定位和拦截逻辑留在各自功能域中。

## Hook 安装与诊断

- 每个功能域通过 `HookRegistrar` 注册 Hook，并使用稳定 ID、`PROTECTIVE` 异常模式和统一优先级策略。
- 安装结果区分 `INSTALLED`、`MISSING`、`FAILED`、`SKIPPED`，保留 `HookHandle`，便于定位 ROM 或目标 App 升级后的签名漂移。
- 普通目标缺失与反射异常按功能域失败开放；`HookFailedError` 等框架级 `Error` 不会被普通异常隔离层吞掉。
- `ModuleMain` 会尽早过滤无关进程并调用 `detach()`，避免在不需要的进程中继续保留生命周期回调。

## 日志与 Release 裁剪

Eta 使用同一组四级日志语义，并按运行环境选择后端：App 与 Agent Runtime 通过 `AndroidAgentLogger` 写入 logcat，Hook 进程通过 `ModuleLogger` 写入 Xposed 日志。业务代码不得直接调用 `android.util.Log` 或 `XposedModule.log`。

| 级别 | 使用范围 | Release |
| --- | --- | --- |
| `DEBUG` | 高频正常流程、目标匹配、重试细节、尺寸与计数 | 全部裁剪 |
| `INFO` | 低频生命周期、Hook 安装汇总、特权动作的结构化摘要 | 保留 |
| `WARN` | 可恢复降级、fallback、目标签名漂移、重试耗尽 | 保留；高频事件必须节流 |
| `ERROR` | 当前请求或功能确定无法完成、关键不变量被破坏 | 保留 |

取消、功能关闭和可选目标缺失不记为 `ERROR`。`debug` 只接受惰性 supplier；supplier 必须是纯观察代码，不能执行 Hook、反射写入、状态变更或其他业务副作用，因为 Release 的 R8 会删除整次调用。

任何级别都禁止记录 Prompt、请求或响应正文、API Key、认证 Header、Cookie、工具参数与结果、原始命令、stdout/stderr、URI、文件路径、图片内容、应用清单和原始运行标识。异常默认只记录类型，不拼接 `Throwable.message`；外部或模型生成的名称必须先转成受长度和字符集约束的安全 token。只有确认不承载用户数据的框架或反射异常才允许附带完整堆栈。

Release 裁剪以 `app/proguard-rules.pro` 为唯一可执行事实来源，规则边界如下：

- `-maximumremovedandroidloglevel 3 class fuck.andes.** { *; }` 只删除 Eta 自有代码中的 Android `VERBOSE/DEBUG`，不影响依赖库。
- 对 `AgentLogger.debug(Function0)`、`AndroidAgentLogger.debug(Function0)` 和 `ModuleLogger.debug(Function0)` 使用精确的 `-assumenosideeffects`，覆盖 R8 无法识别的 Xposed 日志后端。
- 不为 `INFO/WARN/ERROR` 声明无副作用，不使用 `*Logger` 或全局 `android.util.Log` 通配裁剪规则。
- 每次修改规则后同时构建 Debug 与 Release，并检查 R8 configuration/usage、DEX 日志调用、代表性日志字符串和 Xposed 入口元数据。

上述策略依据 Android 官方的 [R8 附加规则](https://developer.android.com/topic/performance/app-optimization/additional-rule-types)、[日志信息泄露防护](https://developer.android.com/privacy-and-security/risks/log-info-disclosure)、AOSP [日志级别约定](https://source.android.com/docs/core/tests/debug/understanding-logging)、OWASP [运行时日志测试](https://mas.owasp.org/MASTG/tests/android/MASVS-STORAGE/MASTG-TEST-0203/) 与 [CWE-532](https://cwe.mitre.org/data/definitions/532.html)。

## system_server

- **电源键接管**：Hook `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage()` 拦截系统分发给小布的唤醒消息（`what == 0x3F3`），接管电源键长按的最终入口。
- **数字助理配置修复**：开机、解锁、切用户时，通过 `AssistantManager` 异步、低频地校正 `android.app.role.ASSISTANT` 及相关 secure settings。Google 已是 role holder 时不再执行清空后重加，避免制造不必要的助理空窗期。
- **唤起逻辑优化**：本次长按优先通过 `VoiceInteractionManagerService` 拉起 Google `voiceinteraction`，失败后依次尝试 `ACTION_ASSIST` 与 `ACTION_VOICE_COMMAND`。三条快速路径均失败时立即回退小布原逻辑；配置修复在后台进行，只影响后续触发，不阻塞当前系统回调。
- **息屏后维持 Hey Google 可用**：Hook `PhoneWindowManager.screenTurnedOff()`，在默认显示息屏后短延迟检查 Google 的 `SoftwareTrustedHotwordDetectorSession`。只有已有 `mSoftwareCallback` 且当前未 running 时，才恢复 `startListeningFromMicLocked()`；亮屏或恢复成功后会取消未执行任务。
- **一圈即搜支持**：强制启用 `ContextualSearchManagerService`，将包名指向 Google App，并放行 `SystemUI` 与 ColorDirectService 的调用权限。作为一圈即搜的底层依赖始终执行，不可关闭。

## SystemUI

拦截底部手势条长按触发的 OPPO OCR 识屏，通过 binder 直接调用 `contextual_search` 服务触发一圈即搜。

## ColorDirectService

拦截 `com.coloros.directui.ui.CollectInfoActivity.M(Intent)`，读取 `startInfo.directExt` 中的 `fingerTrigger` 与 `touchInfo.fingerCount`。确认是双指识屏后，直接调用 `contextual_search` 服务触发一圈即搜，并关闭小布识屏页面；调用失败才回退小布原逻辑。

## Google App

伪装设备为 Samsung S24 Ultra，使 Google 启用一圈即搜能力；同时拦截 `SystemProperties` 和 `PackageManager.hasSystemFeature()` 的关键查询，让 Google App 看到 `ro.opa.eligible_device=true`、`GOOGLE_BUILD` 与 `GOOGLE_EXPERIENCE`。这对应现成 Google App Magisk 模块和 OpenGApps 常用的 OPA eligibility 做法，但限定在 Google App 进程内，不改系统文件。机型伪装与资格补齐作为一圈即搜的底层依赖始终执行，不可关闭。

锁屏唤起 Gemini 浮窗后，Google 偶发只显示输入框、不启动录音。模块优先直接 Hook `FloatyActivity.onResume()`，找不到目标类时才回退到全局 `Activity.onResume()`；确认仍处于锁屏后，带去重地补发一次 `ACTION_VOICE_COMMAND`，避免用户还要手动点麦克风。亮屏（解锁态）唤起时同样存在该偶发问题，因此在同一 hook 点对称增加亮屏分支：确认仍处于解锁态后同样补发一次 `ACTION_VOICE_COMMAND`。去重粒度限定在同一个 `FloatyActivity` 实例，防止同一浮窗 `onResume` 短时间内重复补发，但关闭后立刻新开浮窗不会被上一次全局冷却挡住；两分支各自在延迟任务执行前复查对应开关与锁屏状态是否仍匹配。

## Google App 系统化

Google App 作为普通用户应用时，缺乏语音唤醒所需的系统权限，且容易 ColorOS 被自启管理杀掉。模块内置了 Magisk/KernelSU 模块，可将 Google App 安装为系统 priv-app。

安装流程由 `GoogleAppSystemizerInstaller` 负责：

- 检测 root 管理器类型（Magisk 或 KernelSU）
- KernelSU 需先安装 meta-overlayfs 模块，否则不支持模块安装
- 将内置的 Google App 系统化模块通过 root 执行安装
- 安装成功后提示用户重启生效

系统化安装是用户主动操作，不自动执行。安装入口位于设置页「高级」分组，点击后弹窗确认说明原因与操作方式，用户确认后才开始安装。

## 配置与实时生效

模块 UI 基于 Miuix 0.9.3。配置链路如下：

- **UI 进程**：`FuckAndesApp` 在 `Application.onCreate` 注册 `XposedServiceHelper`，框架通过 `XposedProvider` 推送 binder 后拿到 `XposedService`。设置页通过 `XposedService.getRemotePreferences()` 获取可写的 `SharedPreferences`，写入用 `commit()` 同步等待 binder 提交到 LSPosed 数据库；提交失败时保持原开关状态。
- **Hook 进程**：`ModuleMain.onModuleLoaded` 调用 `XposedInterface.getRemotePreferences()` 缓存只读 `SharedPreferences` 到 `Prefs`。各 Hook 拦截回调入口直接读 `Prefs.isEnabled(key)`，关闭则走原逻辑；因此正常使用时，配置切换后的下一次相关触发表现为实时生效。这里的实时生效来自 Hook 入口读取当前配置，不是 libxposed API 102 的 hot reload 特性。
- **延迟任务复查**：已排队的后台配置修复、`HotwordSelfHealHooks` retry 与 `GoogleAppHooks` 锁屏/亮屏语音命令会在执行前再次检查对应开关，避免用户在任务排队期间关闭开关后被旧任务绕过。

不可关闭的底层依赖（ContextualSearch 服务补齐、机型伪装、资格补齐）始终执行，不暴露开关。

## 聊天流式渲染

模型的 SSE 文本增量先在 App 状态层按 50 ms 合并，减少高频列表状态写入；思考、工具调用和块边界事件仍会立即刷新，事件顺序不变。聊天渲染使用增量 Markdown AST，但不会把尚未显示的大段网络 backlog 一次性交给布局：解析器每次最多追加 12 个 Unicode 字素，并在该批文字显现完成后继续下一批。

逐字效果由单个回答级帧时钟驱动。段落、标题、代码块和表格单元格先完成一次真实排版，再由 `DrawModifierNode` 按字素边界裁剪 `TextLayoutResult`；帧间推进只使绘制失效，不重建 Markdown AST、`AnnotatedString` 或文本布局。前缀路径按字素增量累计，只有显现跨入新行时才触发一次测量并增加消息高度，因此隐藏文本不会提前把当前回答顶出视口。Emoji ZWJ、肤色修饰、组合音标、国旗和代理对均作为完整字素显示，不会从 UTF-16 中间断开。

底部跟随只在用户真实拖动时解除，并仅在消息实际增高、底部哨兵离开视口时滚动；纯网络状态更新不会反复触发滚动。网络结束、解析追平且显现队列排空后，回答切换到稳定 Markdown 状态，恢复完整链接解析与文本选择。

## 功耗与开销

追求极简，绝不给系统增加额外负担：

- 不轮询、不保活 Google 进程、不持续写日志
- 热路径只保留当前机型实际验证有效的 `OplusSpeechHandler` hook
- 默认助理配置检查带 15 秒冷却，息屏后的 Hey Google 恢复路径不主动查写默认助理配置
- 高频成功路径使用 `DEBUG`；Debug 构建可诊断，Release 由 R8 确定性裁剪
- 电源键拦截路径不执行休眠、轮询或阻塞等待；本次触发只做快速启动尝试，失败即回退系统原逻辑
- 默认助理修复异步执行并按用户去重，完成时重新核验 role 与开关状态
- 息屏后的 Hey Google 恢复只响应系统息屏事件；最多串行尝试 3 次，失败才投递下一次，亮屏/成功/结束都会移除未执行 callback
- Google App 的锁屏/亮屏语音输入优先 Hook 固定 FloatyActivity，不常驻拦截 Google App 所有页面；语音补偿只按同一 FloatyActivity 实例去重，避免重复补发又不影响快速关闭后再次启动

## 预期行为

正常情况下，第一次长按电源键就能直接唤起 Gemini。

如果 Google 的 `voiceinteraction` 尚未就绪，模块会尝试 Google 暴露的助理 Activity；仍无法处理时立即回到小布原逻辑，同时在后台修复默认助理配置。这样不会为了追求一次触发成功而占住 `system_server` 回调，也不会出现长按后无反馈的空窗。

配置界面切换开关后会同步提交到 LSPosed 侧 RemotePreferences；Hook 回调和延迟任务执行前都会读取对应开关，所以后续触发按当前配置执行。
