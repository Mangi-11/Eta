# Root 虚拟屏（实验性）

原型为 AI 分配独立 Android display，所有启动、截图和输入都指定该 display。支持 Android 14+，需要 Root 及 ROM 的多屏接口；目前仅验证了 vivo X200 Pro mini、Android 16 / OriginOS 6。

## 使用方法

1. 在「设置 → 虚拟屏（实验性）」开启虚拟屏，并启用设备直达工具。
2. 告诉模型使用 `virtual_screen` 完成操作，提供已知应用的 `包名/Activity`。模型依次调用 `create`、`launch`、`observe`，依据该画面的像素坐标调用 `tap`、`swipe` 或 `back`，最后 `close`。
3. 运行通知显示当前工具；虚拟屏存在时，点击通知打开只读查看页。也可从设置页「查看虚拟屏」进入。

默认 720×1280、280 dpi，可在创建时调整到规定范围。仅同一个运行可控制会话；查看页读取同一会话的 JPEG 帧，不迁移任务、不转发用户点击。创建失败或 display 消失时返回明确错误，不调用主屏工具补偿。

## 与主屏的关系

固定 `app_process` Root 子进程持有 `ImageReader` Surface，采用 own-content、own-focus、禁止抢夺顶层焦点及移除时销毁内容等 display 标志；不镜像主屏、不捕获安全 Surface。输入命令始终携带 `input -d <displayId>`。

为减少任务迁移，启动会拒绝已经有任务的应用、不支持缩放的 Activity，以及 `singleTask` / `singleInstance` Activity，并在启动后校验任务所在 display。这会限制很多实际应用；尚未验证的 ROM 不能视为已经支持。独立显示不等于独立应用数据，登录状态、应用服务、通知及进程仍可共享。

原型暂不提供虚拟屏无障碍树、Unicode 文字输入、独立 IME、任意应用克隆或从用户主屏迁移任务。常规 `observe_screen` / `tap` 等工具仍针对主屏；虚拟屏任务应始终使用 `virtual_screen`。

## 熄屏许可与资源释放

「允许熄屏执行」是单独的用户开关，默认关闭；仅在该开关开启且本次创建指定 `allowScreenOff=true` 时，Root helper 才创建可保持解锁的虚拟 display 并持有最多 15 分钟的局部唤醒锁。它不会解锁或关闭物理屏幕。未获许可时，物理屏幕熄灭或锁定会拒绝后续操作。

关闭任一许可、取消任务、结束运行、管道断开或达到 15 分钟寿命，都释放会话、display、Surface 和唤醒锁。单次交互超时为 15 秒，取消不会等待正在读取的画面。**真实熄屏后的持续执行尚未验证**，该设置不能作为所有 ROM 上可熄屏工作的保证。

## 验证记录

2026-10-06 真机使用专用测试应用验证：创建独立 display、启动 Activity、获取画面、点击后计数 0→1、主屏焦点保持不变、拒绝重复启动已有任务，以及关闭后 display 消失。测试只使用构造界面，没有操作用户应用数据。通知/原子岛接入范围见 [vivo 原子通知](VIVO_ATOMIC_NOTIFICATIONS.md)。

实现参考 Android [多屏 Activity 启动](https://source.android.com/docs/core/display/multi_display/activity-launch)、[多屏输入路由](https://source.android.com/docs/core/display/multi_display/input-routing)及 Android 16 的 [DisplayManager 标志](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/core/java/android/hardware/display/DisplayManager.java)。
