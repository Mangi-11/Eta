# Root 虚拟屏（实验性）

原型为 AI 分配独立 Android display，所有启动、截图和输入都指定该 display。支持 Android 14+，需要 Root 及 ROM 的多屏接口；目前仅验证了 vivo X200 Pro mini、Android 16 / OriginOS 6。

## 使用方法

1. 在「设置 → 虚拟屏（实验性）」开启虚拟屏，并启用设备直达工具。
2. 正常告诉 Eta 打开应用并操作即可。启用开关后，`launch_app`、`open_uri`、`observe_screen`、点击、长按、滑动、节点和文本工具统一自动路由到虚拟屏；首次需要屏幕时自动创建会话。`virtual_screen` 保留显式尺寸与生命周期控制。
3. 运行通知显示当前工具；点击通知或设置页「查看虚拟屏」打开查看页。画面可直接点击、长按、滑动，底部「返回应用」只向虚拟屏发送返回键；「仅查看」关闭手动触控。蓝色落点和轨迹复用原有手势指示样式。

默认 720×1280、280 dpi，可在创建时调整到规定范围。仅同一个运行可控制会话；查看页读取同一会话的 JPEG 帧，将触控按画面的实际缩放与留白换算到虚拟屏像素；单指手势在松开时提交，留白和多指手势不发送输入。手动输入与 AI 工具串行执行；用户操作后旧节点和坐标观察会失效，AI 必须重新观察。创建失败或 display 消失时返回明确错误；默认不回退主屏。

## 与主屏的关系

固定 `app_process` Root 子进程持有 `ImageReader` Surface，采用 own-content、own-focus、禁止抢夺顶层焦点及移除时销毁内容等 display 标志；不镜像主屏、不捕获安全 Surface。输入命令始终携带 `input -d <displayId>`。

为减少任务迁移，启动会拒绝其他 display 上已经有任务的应用（同一虚拟屏可重新进入）、不支持缩放的 Activity，以及 `singleTask` / `singleInstance` Activity，并在启动后校验任务所在 display。这会限制很多实际应用；尚未验证的 ROM 不能视为已经支持。独立显示不等于独立应用数据，登录状态、应用服务、通知及进程仍可共享。

无障碍连接可用时，UI 树、节点动作与 Unicode 文本输入只查询该 display 的应用窗口；节点缺失时使用截图与坐标，不读取主屏窗口。文本通过节点 SET_TEXT 写入，不切换用户输入法；剪贴板工具使用本次运行的临时内容。原型没有独立 IME、任意应用克隆、多指手势或任务迁移。HOME、最近任务、通知栏、系统面板、闹钟/计时 Intent 和内置浏览器 GUI 等无法隔离的 UI 操作默认拒绝；纯网页读取等工具不受影响。关闭虚拟屏开关会终止本次运行的后续 UI 操作。

## 请求回退主屏

「允许请求回退主屏」默认关闭。开启后，应用不兼容、虚拟 display 不可用或某项 UI 操作无法隔离时，任务暂停并发出普通通知，说明当前操作需要改用主屏。只有点击「允许本次任务」才会授权当前运行；通知正文打开 Eta，不代表同意。允许动作要求解锁，通知权限或该通知渠道关闭时不会执行回退。

授权后释放本次运行的虚拟屏，清除旧节点和坐标，返回 `UI_DISPLAY_SWITCHED`；不会重放刚才失败的动作。AI 必须重新启动目标应用（如需要）并调用 `observe_screen` 获取主屏观察，再继续本次任务。许可不写入设置、不跨任务复用，且沿用原有设备工具权限和无障碍边界。

拒绝、移除通知、取消任务、等待超过三分钟或关闭开关，均不操作主屏，同一运行不会重复弹出申请。旧通知与过期令牌不能授权新任务；关闭虚拟屏或回退开关后，本次运行已获准的主屏 UI 操作也会停止。参数错误或旧观察失效只要求重新观察，不触发回退。

## 熄屏许可与资源释放

「允许熄屏执行」是单独的用户开关，默认关闭；仅在该开关开启且本次创建指定 `allowScreenOff=true` 时，Root helper 才创建可保持解锁的虚拟 display 并持有最多 15 分钟的局部唤醒锁。它不会解锁或关闭物理屏幕。未获许可时，物理屏幕熄灭或锁定会拒绝后续操作。

关闭任一许可、取消任务、结束运行、管道断开或达到 15 分钟寿命，都释放会话、display、Surface 和唤醒锁。单次交互超时为 15 秒，取消不会等待正在读取的画面。**真实熄屏后的持续执行尚未验证**，该设置不能作为所有 ROM 上可熄屏工作的保证。

## 验证记录

2026-10-06 初始原型使用专用测试应用验证独立 display、启动、截图、计数点击、主屏焦点保持及释放。本轮交互验证另通过 27 项真机检查：普通 `launch_app` 自动创建并路由、同一虚拟屏重新启动、节点仅来自该 display、节点点击、Unicode 输入与等待文本、用户点击/长按/滑动、蓝色指示可见、手动操作使旧观察失效、临时剪贴板隔离、拒绝全局 HOME/面板、撤销许可不回退主屏及关闭释放。

本轮回退授权另外通过 29 项真机检查：关闭开关保持虚拟屏、通知动作要求解锁、任务等待授权、拒绝后不重复申请、旧通知不能授权新运行、允许后释放虚拟屏、不重放旧坐标、重新启动及观察主屏后节点点击、撤销停止操作、取消及待授权期间关闭开关，以及设置页开关与通知场景删除。实机截图确认卡片和查看按钮左右对齐，顶部与底部使用统一列表页安全间距。

全量 JVM 测试共 1544 项，0 失败、0 错误、2 项既有跳过；覆盖缩放与留白换算、手势取消/会话替换、自动路由、授权令牌和权限撤销，以及无障碍窗口与输入范围。真机仅操作构造界面，临时无障碍与虚拟屏设置在测试后恢复，测试 APK 删除；没有申请应用列表权限或调用付费模型。主屏回退授权使用 Android 普通通知，小 V 接管任务的原子岛支持见 [通知支持范围](VIVO_ATOMIC_NOTIFICATIONS.md)。

实现参考 Android [多屏 Activity 启动](https://source.android.com/docs/core/display/multi_display/activity-launch)、[多屏输入路由](https://source.android.com/docs/core/display/multi_display/input-routing)及 Android 16 的 [DisplayManager 标志](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/hardware/display/DisplayManager.java)。

界面路由与节点范围参考 Android 官方 [多 display 窗口](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindowsOnAllDisplays()) 和 [手势 display ID](https://developer.android.com/reference/android/accessibilityservice/GestureDescription.Builder#setDisplayId(int))。
