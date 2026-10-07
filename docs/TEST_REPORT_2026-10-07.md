# 2026-10-07 虚拟屏修复与最近三天审计

## 基线与环境

- 本报告记录从 GitHub `a9ab54992f2dfee09c3893e852a29a8bdfacf230` 开始的功能修复与真机验证，修改已提交至功能分支 `d840f06`。后续与上游 3.3.0 整合的检查见 [上游整合记录](UPSTREAM_INTEGRATION_2026-10-07.md)，下面的真机数据和截图仍对应整合前版本。
- 最近功能审计范围为 `11ee51d^..a9ab549`，覆盖 27 个提交、314 个变更文件，新增 26609 行、删除 2209 行，包括代码、测试、资源和文档。
- 构建环境：Temurin JDK 25.0.3，Android SDK `android-37.0`、Build Tools 37.0.0。
- 真机：vivo V2419A / PD2419，Android 16 / API 36，ROM `PD2419B_A_16.1.12.38.W10`，KernelSU Root，已安装 LSPosed；微信 8.0.78。
- 真机测试直接调用生产工具执行器，未调用付费模型。构造页面不含用户数据；微信验证只切换标签和滑动，不发送消息，不输出聊天或联系人内容。

## 已修复的问题

| 优先级 | 问题与证据 | 修复 |
| --- | --- | --- |
| P1 | 已在主屏运行的应用返回 `APP_ALREADY_RUNNING`，原路径只能申请回退或取消 | 新增自动停止并重启设置，默认关闭；关闭时提供「停止后在虚拟屏继续」「主屏继续」「取消任务」三个选项。通知与应用内弹窗共享一次性令牌，前两项通知动作要求解锁；取消会终止运行。仅停止已批准的目标应用，拒绝 Eta 与系统应用，重启后复核 display。 |
| P1 | 无障碍主屏查询直接使用 `rootInActiveWindow`，可能读取另一块屏幕；原滚动也有同一风险 | 主屏观察、窗口消失判断与滚动校验窗口的 display；虚拟屏缺窗时从不读取主屏节点。增加反向隔离回归。 |
| P1 | Unicode 输入依赖虚拟节点，vivo 的节点文本动作可能超时；Root 剪贴板调用存在 UID/包名不匹配和 OEM 参数差异 | 焦点文本通过指定 display 的键盘粘贴，支持中文、换行、emoji、替换与清空。剪贴板辅助进程从启动即使用 shell UID，采用固定的 AOSP/OriginOS Binder 签名；OriginOS 读取接口实际为 7 个参数。临时内容标记敏感，只有剪贴板仍由本次输入持有时才恢复原内容；正文经 stdin 传输，不进入命令参数或日志。指定节点的编辑仍要求节点校验。 |
| P1 | 微信虚拟屏未向 Eta 暴露 UI 树；按会话永久停用会误伤启动较慢及后续打开的应用 | 改为同一应用窗口多次空树后暂时隐藏节点工具；空服务/窗口保持 pending，每次观察仍会探测，节点恢复或应用/窗口/会话/手动操作变化解除限制。默认观察自动附该虚拟屏截图；截图失败且没有节点时不发布有效坐标观察。保留指定 display 的 Root 坐标与文本工具，恢复后只使用新观察的节点索引。输入命令显式使用 touchscreen/keyboard source。 |
| P1 | 网页搜索仅依赖 DuckDuckGo HTML；实测返回 HTTP 202 验证页 | 增加 Bing RSS 后备来源，每个来源限制 8 秒、保留安全的失败类型。RSS 使用 XML 解析，限制输出并验证 URL；两个来源都失败时明确返回失败，不伪装成零结果。RSS/XML MIME 支持仅用于搜索，不放宽普通网页读取。 |
| P2 | 原 helper 从创建起固定 15 分钟退出，会打断长任务，也无法提供「永不关闭」 | 改为空闲超时，提供 10/20/60 分钟及永不关闭，默认 20 分钟。运行期间不因空闲策略退出，结束后才计时；查看器帧轮询不延长空闲寿命，手动操作会刷新计时。熄屏许可下的唤醒锁跟随会话释放。 |
| P2 | 查看器缺少宿主输入框，IME 弹出时采用窗口平移，顶栏可能被挤出视野 | 顶栏移除标题，状态靠左，右侧加入默认折叠的输入框开关；使用本机 IME 编辑文字，发送前释放宿主编辑焦点。窗口采用 `adjustResize`，配合 IME insets；底部导航为 40 dp，图标为 16 dp；手动文本操作记录使用已有本地化名称。 |
| P2 | 真机截图发现新冲突弹窗的说明在深色主题下为黑色，难以阅读 | 明确采用 Miuix `onSurface` 文字色，重新渲染并检查说明、三个操作及底部安全间距。 |
| P2 | 基线 Android Lint 有 4 项错误 | 移动数据操作先检查实际 UID 必须为 Root，再调用受保护接口；仅在该方法抑制权限误报。日期型 versionCode 符合仓库规则且没有超过平台上限，针对构建脚本单独配置该提示，不整体关闭 Lint。 |

## 最近功能审计

检查变更清单、模块调用关系、权限与取消路径，并结合现有回归测试检查下列功能。测试数量为当前整个包的用例数，包含该包已有功能。

| 功能 | 重点检查 | 回归证据 |
| --- | --- | --- |
| 虚拟屏与原设备操作 | 会话独占、display/任务归属、坐标缩放、手动操作使观察失效、主屏授权及撤销、输入、关闭与子进程生命周期 | `agent.display` 59 项，`agent.accessibility` 35 项；新增主屏反向窗口隔离及可恢复缺树状态测试；真机构造页面、微信手势和同会话应用切换验证 |
| 自动任务与触发器 | 队列认领、网络约束、运行冲突、停止时不重放不确定操作、触发去重和限制；`jobRunning` 在 worker 收尾前阻止并行启动 | `agent.automation` 6 项，数据库迁移与 DAO 测试包含在 `data.db` 7 项中 |
| 自动经验复盘与技能编写 | 复盘工具白名单双层限制、取消和预算、内置/禁用技能禁止修改、revision 校验、保留资源、符号链接及体积限制 | `agent.runtime` 152 项，`agent.skill` 57 项，`agent.memory` 4 项 |
| vivo 小 V、流式续聊与原子岛 | 单次接管所有权、原生回复隔离、历史配对、桥接身份校验、流式事件顺序、通知令牌和来源 | `hook.vivo` 40 项及 runtime/UI 历史回归；云端助手和 ROM 钩子长期运行见下方验证边界 |
| 个人上下文与系统应用 | 固定数据源、选择参数、用户范围、查询预算、Root Provider 引用释放、权限拒绝、变更后核对与不确定结果 | `agent.context` 72 项，`agent.phone` 11 项，`agent.device` 47 项，`agent.tool` 77 项；新增非 Root 移动数据拒绝回归 |
| 文件与终端 | Android/Linux 身份与命名空间、路径边界、链接、UTF-8 分页、revision/cursor、原子写入声明、取消及输出上限 | `agent.terminal` 149 项，包含 1 项既有跳过 |
| 会话增量保存与流式备份 | 事务后才推进基线、仅写入变化、导入期间暂停旧状态保存、失败回滚、大小上限和暂存资源释放 | `data.repository` 72 项，`ui.app` 120 项，相关 DAO 与备份测试 |
| 语音输入与连续播报 | 按住说话不提前发送、停顿设置、纠错失败交付原文、取消旧回调、播放预取限制、音频块上限、段间淡入淡出与音频焦点 | `agent.voice` 90 项，包含 1 项既有跳过 |
| Markdown/公式、工具 ID、统计与侧栏 | 定界符不改写代码及源码偏移、历史完整批次修复、工具 ID 不与历史冲突、流式 token/耗时、资源和布局 | `ui.markdown` 65 项，`agent.model` 215 项，`ui.components` 59 项 |
| Android 13 兼容 | API 34/36 功能按版本限制，旧系统不使用弱身份校验替代新广播身份接口；默认能力投影 | Robolectric 的 API 33/34/36 等配置、全量编译和 Android Lint |

## 验证结果

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 1628 项、275 个测试类，0 失败、0 错误、2 项既有跳过 |
| `:app:lintDebug` | 0 错误、381 条警告、1 条提示；警告不代表全部已经消除 |
| `:app:assembleDebug` / `:app:assembleDebugAndroidTest` | 成功 |
| `:app:assembleRelease` | R8 优化构建成功；签名安装仍遵循仓库发布配置 |
| `git diff --check` | 通过 |
| 前轮微信操作验证 | 14 项通过：虚拟启动、默认观察自动附图、缺树工具过滤、标签实际切换、AI 滑动实际移动内容、手动滑动、旧坐标拒绝及重新观察 |
| 最新缺树恢复验证 | 22 个真机断言通过，另记录 5 个数量样本：同一 display 中微信空树后 QQ 两次恢复 42 个节点；正常及直接启动均解除前一应用的限制，旧节点跨应用被拒绝，截图兜底与显式关闭截图生效 |
| 构造页面、宿主 IME、设置及授权弹窗 | 82 条检查记录全部成功，含 UI 树和搜索来源两条诊断记录：Root Unicode/替换/清空、宿主 IME 输入、剪贴板恢复、四档滑条与持久化、三个授权选项、通知要求解锁、主屏隔离、续聊保留及释放 |
| 自动停止冲突应用 | 关闭设置时未经批准的停止被拒绝，批准后在指定 display 重启；开启设置后，实际停止并重启测试应用，不请求交互批准 |
| 真机网页工具 | `web_search` 经 `bing_rss` 返回有效来源链接（请求上限 3 条），`fetch_url` 成功读取 `https://example.com/` |

JVM 跳过来自 `DetachedTaskSupervisorTest` 与 `EtaWaveShaderTest`。构建后可查看 `app/build/reports/tests/testDebugUnitTest/index.html` 和 `app/build/reports/lint-results-debug.html`。

最终 Debug APK 已安装到上述手机；测试 APK 和手机上的临时验证截图已删除。检查未发现遗留的 `Eta virtual task` display 或 Root 显示/文本辅助进程。

## 界面截图

后续的多应用 UI 树对比和同会话恢复验证见 [UI 树专项测试](UI_TREE_TEST_2026-10-07.md)：多个真实应用可取树，微信在虚拟屏和主屏都只有空根；启动时空窗口保持 pending，缺树限制按应用窗口判定并可恢复。

所有截图均为上述 vivo 真机上的构造界面，不包含微信内容。

- [输入框默认折叠](Screenshots/virtual_screen/viewer-collapsed.png)
- [本机键盘与中文、emoji 草稿](Screenshots/virtual_screen/viewer-keyboard.png)
- [设置开关、四档清理滑条与风险提示](Screenshots/virtual_screen/virtual-settings.png)
- [深色主题的三个冲突选项](Screenshots/virtual_screen/app-conflict.png)

## 复现命令

在仓库根目录使用 JDK 25，并配置 Android SDK：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e mode core io.github.mangi.eta.test/io.github.mangi.eta.validation.DeviceValidationRunner
adb shell am instrument -w -e mode wechat io.github.mangi.eta.test/io.github.mangi.eta.validation.DeviceValidationRunner
adb shell am instrument -w -e mode tree_recovery io.github.mangi.eta.test/io.github.mangi.eta.validation.DeviceValidationRunner
adb uninstall io.github.mangi.eta.test
```

真机测试需要屏幕已解锁、Root 授权及网络连接。`wechat` 模式会启动微信；若主屏已有微信任务，会通过测试中的批准路径停止并在虚拟屏重启微信。`core` 模式只停止测试 APK 的构造应用。测试结束释放虚拟屏、撤销临时测试权限并恢复测试前的 Eta 设置。测试截图只针对构造界面；为检查布局，测试暂时移除该查看器窗口的安全标志，随后恢复，生产查看器仍保持安全标志。

## 验证边界

- JVM 模拟和协议回归不能替代不同 ROM、不同语音提供商及系统助手的完整实机行为。没有调用收费云端语音或模型，没有创建用户日历、闹钟或便签，也没有执行用户自动任务。
- 空闲超时使用可控时钟验证 10/20/60 分钟、运行中不关闭、永不关闭及设置变化；未逐一进行 60 分钟以上的实时时长/内存稳定性测试。永不关闭会持续持有资源，设置已显示内存泄漏风险提示。
- 仅验证上述 vivo ROM；原生 IME 的多屏支持由 ROM 决定，因此保留宿主输入框作为稳定入口。没有把主屏 `uiautomator dump` 冒充虚拟屏 UI 树。
- 临时系统剪贴板粘贴需要 Root/shell 权限。正常结束仅恢复仍属于本次输入的剪贴板；系统强制终止辅助进程等异常不能保证执行 `finally`，该限制不应被解释为永久剪贴板隔离。
- 搜索后备降低单一来源封锁的影响，但两个公共来源同时受限时仍会明确返回失败。

## 实现参考

- Android 官方 [多屏输入法支持](https://source.android.com/docs/core/display/multi_display/ime-support)、[多屏输入路由](https://source.android.com/docs/core/display/multi_display/input-routing)。
- AOSP [Android 16 剪贴板接口](https://github.com/aosp-mirror/platform_frameworks_base/blob/android16-release/core/java/android/content/IClipboard.aidl)与 scrcpy [v2.7 剪贴板 OEM 签名适配](https://github.com/Genymobile/scrcpy/blob/v2.7/server/src/main/java/com/genymobile/scrcpy/wrappers/ClipboardManager.java)。
- 网页工具策略参考 [Hermes web tools](https://github.com/NousResearch/hermes-agent/blob/main/tools/web_tools.py) 和 [OpenClaw web tools](https://docs.openclaw.ai/tools/web)：多来源、有限超时、取消及明确失败；没有引入其整套依赖或新的 API 密钥要求。
