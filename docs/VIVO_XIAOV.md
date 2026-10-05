# vivo 小 V 接管

Eta 可以在蓝心小 V 的原生聊天界面中接管普通文字请求，调用 Eta 中配置的模型与 Agent Runtime，并把最终回答写回小 V 的文字卡片。

## 适配范围

- 包名：`com.vivo.ai.copilot`。
- 小 V 版本：`6.8.5.3`，versionCode `68503`。版本不匹配时保留原生行为。
- 实测设备：vivo X200 Pro mini（V2419A），Android 16 / OriginOS 6，KernelSU + Zygisk Next + LSPosed 2.2.0（API 102）。
- 当前入口：聊天界面中经 `CopilotSpeechGptLinker.sendChat(MessageParams)` 派发的普通文字请求。

图片、文件、识屏、实时语音通话及厂商专用任务不在此适配范围内。该功能不改变系统默认助理，也不修改电源键或“小 V 小 V”唤醒配置。原有入口仍由 vivo 系统负责。

## 使用方法

1. 构建并安装 Eta：`./gradlew :app:assembleDebug`，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
2. 在 LSPosed 中启用 Eta，勾选 **蓝心小 V / `com.vivo.ai.copilot`**。此功能不需要勾选 `com.vivo.assistant`、`com.vivo.ai.gptagent` 或系统框架。
3. 在 Eta 的模型提供商设置中配置 API 地址、API Key、模型名和上下文窗口大小，选中该模型。
4. 在设置 → 厂商助手兼容入口中启用“小 V 使用 Eta 模型”。默认仅接管 `/agent` 前缀请求，例如 `/agent 用一句话解释什么是缓存`。
5. 首次启用模块后关闭并重新打开小 V。若 LSPosed 提示需要重启，按框架提示处理。

关闭“小 V 仅接管带 /agent 前缀的请求”后，所有符合范围的普通文字请求都交给 Eta。关闭“小 V 使用 Eta 模型”后立即恢复原生派发。

## 行为与限制

认领前检查版本、消息类型、附件、完整回答协议及后台队列；检查失败时调用原方法。认领成功后不再重复提交给 vivo 模型。缺少模型配置时，在小 V 中显示配置提示；Runtime 失败时显示错误提示，并结束等待。

新请求和原生 GPT 取消动作会中断旧任务。只把当前进程内同一小 V 会话最近六轮已完成的 Eta 对话作为上下文；不读取原生聊天数据库。任务同时通过独立 `vivo` handoff 归档到 Eta，重启小 V 后内存上下文重置。

目前显示最终回答，未接入逐字流式卡片或回答语音播报。测试接口仅用于开发验证；实际使用需要配置自己的模型提供商。

## 开发验证

```sh
./gradlew :app:testDebugUnitTest --tests '*Vivo*Test' --tests '*ModuleConfigEntryPackagesTest' --tests '*PrefsDefaultsTest'
./gradlew :app:assembleDebug :app:lintDebug
```

真机检查至少覆盖前缀接管、普通请求保留、关闭接管、模型配置缺失、取消，以及最终回答显示。其他 ROM 或小 V 版本需重新验证内部事件协议，不能仅放宽版本检查。

2026-10-05 实测：Debug APK 构建成功，12 项相关单元测试通过。本机 OpenAI-compatible 测试接口返回的回答已显示在小 V 原生卡片中，连续第二轮请求包含第一轮上下文；未调用付费模型接口。

| 真机项目 | 结果 |
| --- | --- |
| 缺少模型配置 | 原生卡片显示 Eta 配置提示 |
| `/agent` 前缀文字 | 到达 Eta Runtime，最终回答显示成功 |
| 默认模式下普通文字 | 原生小 V 回答，测试接口无新增请求 |
| 关闭前缀限制 | 普通文字进入 Eta，开关即时生效 |
| 关闭接管 | `/agent` 请求回到原生处理，测试接口无新增请求 |
| 停止执行 | 显示“已停止回答”，迟到的 Eta 响应未覆盖卡片 |
| 连续对话与归档 | 第二轮带上 Eta 历史，Eta 对话列表出现“小 V”记录 |

Android Lint 未通过：原有 `NativeSystemOperations.kt` 有 3 处 `MissingPermission`，`app/build.gradle.kts` 有 1 处 `HighAppVersionCode`；此次适配未修改这两处文件。测试提供商和 ADB 临时端口转发在验证后删除，使用时仍需配置自己的模型。
