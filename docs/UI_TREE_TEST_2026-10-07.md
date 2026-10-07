# 2026-10-07 虚拟屏 UI 树应用对比

## 结论

在这台 vivo 上，所测真实应用中只有微信当前页面持续没有可用子树；QQ、Via、ZArchiver、酷安、Kimi 和系统计算器均能在虚拟屏读到有效节点。微信在主屏也没有有效子树，测试用 `UiAutomation` 在两块屏幕都只能取得没有文字及动作节点的根容器，不能将问题直接归因于虚拟屏。

测试同时发现启动时序问题：新应用刚启动时窗口尚未登记，一次空结果就会让原工具在整个运行中停止取树。首轮中六个本来有树的应用都触发了这种误判。现已改为按应用窗口判断：空窗口/服务未就绪返回 `ui_tree_pending`；同一窗口至少三次间隔 500ms、跨越 1.5 秒的空树观察，才暂时隐藏节点工具。每次请求 UI 树的观察仍会重新探测，节点恢复立即解除限制；应用、窗口、会话或手动操作变化重置判定，恢复后必须使用新观察的节点索引。

## 环境与方法

- vivo V2419A，Android 16 / API 36，OriginOS ROM `PD2419B_A_16.1.12.38.W10`，KernelSU，LSPosed 已安装。
- 使用生产 Root 虚拟屏启动与取树代码；每个应用创建新 display，避免上一应用的停用状态影响比较。
- 在启动后约 0、0.5、1.5、4 秒分别直接查询 Eta 无障碍服务，并比较测试用 `UiAutomation.getWindowsOnAllDisplays()` 的结果。微信额外等待到 10 秒，并在主屏再等待 10 秒复核。
- 始终校验 display 与目标应用焦点，`observe_screen` 明确关闭截图。只输出应用版本、数量、是否有根及路由状态，不保存文本、描述、聊天、文件名或截图。
- Eta 直接查询上限为 120 个有效节点；工具默认上限为 60 个。`UiAutomation` 包含布局容器，遍历上限为 240，因此两列数量不是同一口径。

## 实测数据

| 应用 | 版本 | Eta 有效节点（首轮 / 复测） | UiAutomation 节点（复测） | 结果 |
| --- | --- | --- | --- | --- |
| 构造页面 | 测试 APK | 20 / 0 | 72 | 控制页面有完整子树；复测中 Eta 窗口未就绪，保留 pending，没有永久停用 |
| 微信 | 8.0.78 | 0 / 0 | 1 | 等待 10 秒后仍无文字或动作子节点；主屏相同 |
| QQ | 9.3.70 | 53 / 54 | 至少 240 | 可用，工具复测返回 54 个节点 |
| Via | 7.3.4 | 12 / 12 | 34 | 可用，工具复测返回 12 个节点 |
| ZArchiver Pro | 1.0.9 | 78 / 78 | 95 | 可用，工具按默认上限返回 60 个节点 |
| 酷安 | 16.6.2 | 8 / 8 | 至少 240 | 可用；Eta 与测试通道的节点过滤存在差异 |
| Kimi | 3.1.3 | 23 / 23 | 52 | 可用，工具复测返回 23 个节点 |
| 系统计算器 | 16.0.1.2 | 未测 / 33 | 53，另有 10 节点窗口 | 可用，工具复测返回 33 个节点 |
| 系统设置 | 16.0 | 未进入 | 未测 | 主屏已有任务，禁止停止系统应用的规则返回 `APP_RESTART_UNSUPPORTED`，不算取树失败 |

修复前，QQ、Via、ZArchiver、酷安、Kimi 及构造页面在稍后已经能直接读到树，但工具仍返回 `tool_nodes=0, tool_disabled=true`。复测中上述真实应用的工具节点恢复，并且 `tool_disabled=false`。

主屏控制页面：Eta 18 个有效节点，`UiAutomation` 72 个节点；主屏微信：Eta 0 个有效节点，`UiAutomation` 1 个无文字、无动作的根节点。

原始统计：[修复前](validation/ui-tree-before-2026-10-07.json)、[复测](validation/ui-tree-after-2026-10-07.json)。

## 同一会话恢复验证

使用同一个 `AgentLocalTools`、同一个会话及 display 90，依次打开微信、QQ、微信、QQ。既验证普通 `launch_app`，也验证直接 `virtual_screen launch`，未为每个应用重建工具或虚拟屏。

| 阶段 | 工具节点数 | `ui_tree_disabled` | `ui_tree_pending` |
| --- | --- | --- | --- |
| 微信刚启动，窗口未就绪 | 0 | false | true |
| 微信多次确认空树 | 0 | true | false |
| 切到 QQ | 42 | false | false |
| 返回微信，多次确认空树 | 0 | true | false |
| 再次切到 QQ | 42 | false | false |

22 个真机断言全部通过，另记录 5 个数量样本：首次空树不禁用、仅隐藏节点工具、默认截图兜底、显式关闭截图生效、两种启动路径恢复能力、旧 QQ 节点不能作用于微信、全程保持同一虚拟屏。原始统计：[会话恢复](validation/ui-tree-recovery-2026-10-07.json)。

11 项可控时钟回归覆盖短时空树、密集重试、启动宽限、缺服务/窗口、同窗口节点恢复、应用/窗口/会话/手动操作变化。全量 JVM 检查为 1628 项、275 个测试类，0 失败、0 错误、2 项既有跳过；Lint 0 错误、381 条警告、1 条提示；Debug 与 Release 构建通过。

首次恢复验证因 instrumentation 重启目标进程后，ROM 将已启用的 Eta 无障碍服务留在未绑定状态而未达到“确认空树”的前提；工具正确保持 pending。测试器现仅重新连接原本已启用的 Eta 服务，保留其他服务设置；成功轮次中服务已连接。测试器也在修改虚拟屏设置前保存四项非敏感原值，正常退出恢复，意外中断后下一次运行会先恢复这些值。

## 复现

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e mode tree io.github.mangi.eta.test/io.github.mangi.eta.validation.DeviceValidationRunner
adb pull /sdcard/Android/data/io.github.mangi.eta/files/validation/ui-tree-matrix.json
adb shell am instrument -w -r -e mode tree_recovery io.github.mangi.eta.test/io.github.mangi.eta.validation.DeviceValidationRunner
adb pull /sdcard/Android/data/io.github.mangi.eta/files/validation/ui-tree-recovery.json
adb uninstall io.github.mangi.eta.test
```

测试会启动这些应用。其他 display 已有任务时，测试批准路径可能停止目标第三方应用再启动；系统应用的停止限制仍生效。成功退出时释放 display，恢复测试前设置。一次复测因前台执行服务启动超时而中断，该轮未写成完整结果；报告使用随后完成的两轮数据。

早期矩阵测试异常退出时未保留清理时间的原始值，该轮最终恢复为默认 20 分钟；最新恢复测试保留并恢复测试前设置。测试 APK 和设备上的临时统计文件已删除，检查没有残留的 Eta 虚拟 display 或 Root 辅助进程。修正后的 Debug 版已安装。

## 尚未确认

- 没有逐个测试手机上的全部应用，也没有遍历微信所有页面；不能推断其他微信版本或其他 ROM 同样缺树。
- 尚未确认微信空子树来自应用、ROM 策略还是已安装模块。系统测试通道也没有更多节点，独立 Root `app_process` 的 `UiAutomation` 后端仍未实现或验证。
- 同窗口恢复由可控时钟回归验证；真机恢复验证覆盖上述两应用切换，没有遍历微信每个页面。
