# 技术实现

模块在四个进程中分别作了针对性的 Hook：

## system_server

- **电源键接管**：Hook `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage()` 拦截系统分发给小布的唤醒消息（`what == 0x3F3`），接管电源键长按的最终入口。
- **数字助理配置修复**：开机、解锁、切用户时，通过 `AssistantManager` 低频校正 `android.app.role.ASSISTANT` 及相关 secure settings，自动校正并尽量维持 Google 为默认助理。
- **唤起逻辑优化**：优先使用 `VoiceInteractionManagerService` 拉起 Google `voiceinteraction`。如果助理配置刚恢复而服务还没 ready，会在系统 handler 上做有限次的延迟重试补偿。
- **息屏后维持 Hey Google 可用**：Hook `PhoneWindowManager.screenTurnedOff()`，在默认显示息屏后短延迟检查 Google 的 `SoftwareTrustedHotwordDetectorSession`。只有已有 `mSoftwareCallback` 且当前未 running 时，才恢复 `startListeningFromMicLocked()`；亮屏或恢复成功后会取消未执行任务。
- **一圈即搜支持**：强制启用 `ContextualSearchManagerService`，将包名指向 Google App，并放行 `SystemUI` 与 ColorDirectService 的调用权限。

## SystemUI

拦截底部手势条长按触发的 OPPO OCR 识屏，通过 binder 直接调用 `contextual_search` 服务触发一圈即搜。

## ColorDirectService

拦截 `com.coloros.directui.ui.CollectInfoActivity.M(Intent)`，读取 `startInfo.directExt` 中的 `fingerTrigger` 与 `touchInfo.fingerCount`。确认是双指识屏后，直接调用 `contextual_search` 服务触发一圈即搜，并关闭小布识屏页面；调用失败才回退小布原逻辑。

## Google App

伪装设备为 Samsung S24 Ultra，使 Google 启用一圈即搜能力；同时拦截 `SystemProperties` 和 `PackageManager.hasSystemFeature()` 的关键查询，让 Google App 看到 `ro.opa.eligible_device=true`、`GOOGLE_BUILD` 与 `GOOGLE_EXPERIENCE`。这对应现成 Google App Magisk 模块和 OpenGApps 常用的 OPA eligibility 做法，但限定在 Google App 进程内，不改系统文件。

锁屏唤起 Gemini 浮窗后，Google 偶发只显示输入框、不启动录音。模块优先直接 Hook `FloatyActivity.onResume()`，找不到目标类时才回退到全局 `Activity.onResume()`；确认仍处于锁屏后，带冷却地补发一次 `ACTION_VOICE_COMMAND`，避免用户还要手动点麦克风。

## 功耗与开销

追求极简，绝不给系统增加额外负担：

- 不轮询、不保活 Google 进程、不常驻额外线程、不持续写日志
- 热路径只保留当前机型实际验证有效的 `OplusSpeechHandler` hook
- 默认助理配置检查带 15 秒冷却，息屏后的 Hey Google 恢复路径不主动查写默认助理配置
- 成功路径默认静默（`ENABLE_VERBOSE_LOGS=false`）
- 只有在默认助理刚恢复但 `voiceinteraction` 尚未完成重建时，才会追加少量一次性延迟重试，成功后立即失效，不留后台负担
- 息屏后的 Hey Google 恢复只响应系统息屏事件；最多串行尝试 3 次，失败才投递下一次，亮屏/成功/结束都会移除未执行 callback
- Google App 的锁屏语音输入优先 Hook 固定 FloatyActivity，不常驻拦截 Google App 所有页面

## 预期行为

正常情况下，第一次长按电源键就能直接唤起 Gemini。

如果模块刚把"默认数字助理应用"切回 Google，系统还在异步重建相关服务，模块会尽量拦截掉这期间失败的调用流程（避免它傻傻地回退去打开 Google App 主界面），并在后台短时间内发起最多 3 次的延迟重试（从 1.2 秒起步）。实测即便在这种刚恢复配置的情况下，第一次长按通常也能顺利拉起 Gemini 浮窗。
