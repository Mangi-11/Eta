# 语音识别与播报

Eta 的「设置 → 语音」分别配置语音识别（ASR）与语音播报（TTS）。云端识别由 Eta 自行录音并调用用户配置的服务，不依赖设备预装识别引擎，也不需要 Root。升级后仍默认使用系统识别，播报与助手自动播报默认关闭。

在「语音识别」页关闭「启用语音识别」并保存，即可停用 Eta 内置语音输入。聊天和助手浮窗隐藏麦克风入口，不再启动系统识别器或云端录音；原有服务、凭据和识别参数会保留，重新开启后继续使用。输入法自己的语音输入及 Eta 的语音播报不受此开关影响。

## 使用方式

- **聊天听写**：点击输入框麦克风开始，点击完成后把最终文字插入当前选区，可编辑后发送。预览文字不会覆盖已有草稿。系统服务提前返回时也等待用户确认。
- **助手浮窗**：识别结果直接发起提问。千问实时、豆包流式与系统识别在停顿后自动发送，停顿时长可选 1、1.5、2（默认）或 3 秒，也可关闭；千问短音频与文件转写需点击完成。长按麦克风进入按住说话，期间停顿不会发送，松手发送，手指移出按钮取消。回答下方提供朗读按钮。
- **文字唤醒**：关闭语音识别后，全局唤醒先播放入场光效，再让文字输入栏淡入并轻微上移，显现完成后才聚焦并请求键盘，可直接使用输入法语音输入。空输入时显示不可点击的发送按钮；输入法收起后也不会重新启动识别。各阶段通过动画完成回调衔接，键盘升降期间由布局阶段的 Insets 保持输入栏与键盘同步。用户提前点击输入框会接管输入，关闭浮窗会取消后续自动聚焦；键盘自身的动画速度由系统和输入法决定。
- **智能纠错**：开启后，识别结果在交付前用当前对话模型做一次不带工具的保守校对，只修正同音字和被识别成中文谐音的英文术语，不改写正确内容。纠错最多等待 8 秒；未配置对话模型、请求失败、超时或输出长度异常时直接使用原始识别结果。聊天听写与浮窗都生效，会增加少量延迟和模型费用。
- **回答播报**：聊天回答可手动朗读；朗读期间退到后台或锁屏会继续播放，通知栏显示「Eta 正在朗读」并可停止，离开聊天页才结束。在聊天页「更多」菜单中开启自动朗读后，每轮回答正常结束时自动朗读这一轮的最终回答；停止或失败的回合、切换会话与打开历史都不会补读，关闭开关会停止正在进行的朗读。开启「助手自动播报回答」后，仅播报浮窗中语音提问的成功最终回答，不读取思考、工具日志或代码块。
- **取消与退出**：点击取消、开始新的录音、隐藏或关闭所属页面会终止音频任务；耳机断开、熄屏或播放失去音频焦点也会停止。因 GUI 操作隐藏浮窗后完成的任务只保留文字，不补播，不自动继续监听。

聊天页「更多」菜单提供「开启自动朗读」或「关闭自动朗读」，通过扬声器与静音图标辅助区分当前状态；未配置播报服务时隐藏该菜单项。

助手浮窗在实际录音、播放期间分别声明麦克风、媒体播放前台服务，并显示可停止的通知；结束音频操作即撤下通知。它不延长浮窗隐藏后的音频任务，也不提供后台持续监听或播放。

Android 13 可向系统识别服务提交语言包下载请求，但系统没有下载进度与完成回调，需稍后重试识别；Android 14+ 在服务支持时显示下载回调状态。关闭语音入口会释放相关识别器。

单次云端录音最多 60 秒。实时接口可显示识别预览，短音频与文件转写在录音完成后返回文字。切换服务不会自动复制凭据或替换已保存的其他服务配置；失败不会自动回退到另一家供应商。

## 服务配置

| 服务 | 模型与模式 | 配置 |
| --- | --- | --- |
| 系统 ASR | 设备已有识别服务 | 麦克风权限；支持时可手动下载语言包 |
| 千问实时 ASR | `qwen3-asr-flash-realtime` | 百炼地域、API Key |
| 千问短音频 ASR | `qwen3-asr-flash` | 百炼地域、API Key |
| 千问文件转写 | `qwen3-asr-flash-filetrans` | 百炼地域、API Key、自有 OSS |
| 豆包流式 ASR | 流式识别 2.0 | API Key；默认资源 ID `volc.seedasr.sauc.duration` |
| 千问 TTS | `qwen3-tts-flash-realtime`，不可用时回落 `qwen3-tts-flash` | 百炼地域、独立播报 API Key、音色；默认 Cherry |
| 豆包 TTS | 语音合成 2.0，双向流式，不可用时回落单向流式 | 独立播报 API Key、音色；默认 VV，资源 ID `seed-tts-2.0` |
| Gemini TTS | Interactions 语音合成，分段流式 | Google AI Studio API Key、语音模型、音色；可选朗读风格 |
| MiniMax TTS | HTTP 语音合成，分段流式 | MiniMax API Key、语音模型、音色与语速 |

千问可选择北京或新加坡，API Key 必须匹配所选地域。高级设置可填写服务根地址，包括业务空间专属域名；不要附加 `/api/v1` 或 `/compatible-mode/v1`。模型与协议绑定，不使用聊天模型列表推断语音能力。千问识别语言留空表示自动识别；指定语言时使用服务支持的语言代码。

播报页提供各服务的常用音色，按名称选择，列表标注声音特点、男女声或方言。千问预设适配当前播报模型，豆包预设适配语音合成 2.0；Gemini 预设可朗读中文及其支持的其他语言，MiniMax 预设为普通话。已保存的音色 ID 会自动匹配名称，未匹配的 ID 保留在「自定义音色」中。试听文本可编辑，最多 500 字，使用当前草稿，不会自动保存。

Gemini 与 MiniMax 的模型选项及默认值以 `data/model/SpeechSettings.kt` 为准，各服务分别保存地址、凭据、模型和音色，切换后仍保留原配置。Gemini 的朗读风格与正文分开提交，可填写“自然、温和，语速稍慢”，留空使用默认风格；MiniMax 可调整 0.5～2 倍语速，并启用基础数字文本规范化。

Gemini 使用 Google AI Studio 的原生语音接口，需要账号、地区与模型权限支持；它负责朗读已有回答，不创建 Live 对话会话，请求关闭服务端交互存储。MiniMax 默认连接中国站，海外账号需改为对应站点。自定义地址填写服务根地址，可包含网关前缀，但必须支持所选服务的原生协议；聊天模型兼容地址不能直接作为语音地址。

豆包高级设置可修改资源 ID、服务根地址，或启用旧版 App ID＋Access Token 鉴权。旧版配置的 Access Token 填在对应的凭据输入框。自定义音色应填写所选模型支持的音色 ID，不会在 Eta 内创建或复刻音色。

表单需点击「保存」。识别测试与音色试听使用当前草稿调用正式链路，不自动保存，也不自动开始。测试和试听可能产生对应服务费用。

## 文件转写与 OSS

文件转写使用自有 OSS 私有对象，不使用百炼临时上传服务。录音存储页填写地域、标准地域 Endpoint、Bucket、目录前缀及专用 RAM 用户的 AccessKey。凭据应具备该目录下的 `PutObject`、`GetObject`、`DeleteObject` 权限，并允许将对象 ACL 设置为 private。

Eta 上传 WAV 后生成有效期 15 分钟的读取签名 URL，向百炼提交异步任务，每 1.5 秒查询一次，整个上传与转写流程最多等待 5 分钟。结果 JSON 下载不携带百炼认证头。

上传前先记录待清理对象；成功、失败及取消后尝试删除。删除失败保留记录，下次主进程启动时有界重试。上传结果不确定时，即使立即删除成功仍保留一次恢复清理记录，以处理取消与远端上传完成的竞争。恢复清理不删除当前进程仍在使用的录音。

建议为录音目录设置一天过期的 OSS 生命周期规则，处理设备不再启动、应用卸载或原凭据失效的情况。更换 OSS AccessKey 后，旧 Key 对应的待清理对象不会用新身份删除，需要在 OSS 控制台清理或由生命周期规则回收。取消本地等待不代表撤销已经提交的云端任务或费用。

## 实现边界

- `SpeechInputController` 统一管理 start、finish、cancel 和一次性结果交付。系统服务适配沿用 `EtaSpeechInput`；独立进程 `EtaRecognitionService` 仍只负责系统兼容桥接，不向其他应用开放云端凭据。
- `SpeechRecorder` 采集 16 kHz、单声道 PCM16。录音只暂存在内存中，文件接口封装 WAV；实时发送队列和响应解析均有限额，背压失败明确返回，不静默丢帧。
- 千问实时协议通过 `text + stash` 替换当前 item 预览，以 completed 为最终文字，等待 session.finished 后结束。自动发送的停顿时长同时作为千问 `silence_duration_ms` 与豆包 `end_window_size`，由服务端判定说完；系统识别通过 `RecognizerIntent` 静音时长提示传递，服务可以忽略。不自动发送时沿用 800 ms 断句，只影响预览分段。豆包使用专用二进制 codec，支持 Gzip、头扩展和最终包标记，不能套用 JSON WebSocket。
- `SpeechPlaybackController` 负责合成与播放，`SpeechAudioLease` 协调本进程录音、播放和音频焦点。TTS 正文使用 GFM AST 提取可读文字，按句分块，使用 24 kHz PCM16 顺序播放。千问与豆包优先把整篇放进一个流式合成会话（千问实时 WebSocket 的 server_commit 模式，或豆包双向流式的单个 session）；豆包使用 `DoubaoTtsCodec` 处理带事件号与会话 ID 的二进制帧。这两家的流式会话在出声前失败时回落到同服务的逐段请求；出声后再失败直接报错，不从头重读。
- Gemini 与 MiniMax 直接使用逐段 HTTP 流式合成，不尝试 WebSocket，也不自动重试或切换服务。合成最多领先播放两段，段首段尾各做 5 ms 淡入淡出；分段边界可能产生语调差异。Gemini 解码音频增量并检查最终交互状态，MiniMax 解码十六进制音频并跳过末包聚合音频，避免重复播放。解析器限制事件与音频大小，提前 EOF、空音频、业务错误和非成功终态都返回失败；停止会取消当前 HTTP 调用和后续预取。
- 合成解码层识别裸 PCM 与流式 WAV，校验 WAV 格式并剥离容器头，不把头信息作为音频播放；文件头和采样可以跨响应片段。流式 WAV 长度字段可能是占位值，仅成功收到终止事件才把音频视为完整。`SpeechPcmOutput` 先填充播放缓冲区再启动音轨；不足一个缓冲区的短音频在合成结束后按实际帧数启动。
- `SpeechTranscriptRefiner` 通过 `RuntimeConfigRepository` 读取当前对话模型，复用 Provider 层发出独立的 `TRANSCRIPT_REFINE` 请求：不带工具、托管搜索与自定义请求体，可关闭思考时关闭，否则使用最低档。识别文字只作为用户消息，取消或超时会取消底层 HTTP 调用。
- `SpeechSettingsRepository` 隔离持久化；普通配置进入现有 DataStore，凭据用 Android Keystore 的 AES-GCM 加密后保存。凭据不进入 RemotePreferences、会话、日志或数据导出；语音配置目前也不纳入手动备份。不可解密的凭据会要求重新填写，不静默当作空配置。
- 设置页、聊天与浮窗复用相同控制器；网络与音频循环不进入 Composable、Hook 或 Agent loop，Runtime IPC 与归档格式保持兼容。

## 官方资料

- [Compose Insets 布局时序](https://developer.android.com/develop/ui/compose/system/insets-ui)、[Android 输入法动画](https://developer.android.com/develop/ui/views/layout/sw-keyboard)
- [Material 动效系统](https://m3.material.io/styles/motion/overview/how-it-works)
- [千问 ASR HTTP API：短音频与异步文件转写](https://help.aliyun.com/zh/model-studio/qwen-asr-api-reference)
- [千问实时 ASR 客户端事件](https://help.aliyun.com/zh/model-studio/qwen-asr-realtime-client-events)、[服务端事件](https://help.aliyun.com/zh/model-studio/qwen-asr-realtime-server-events)
- [豆包流式 ASR 协议](https://www.volcengine.com/docs/6561/1354869)
- [千问 TTS API](https://help.aliyun.com/zh/model-studio/qwen-tts-api)、[音频播放示例](https://help.aliyun.com/zh/model-studio/non-realtime-tts-user-guide)
- [千问音色列表](https://help.aliyun.com/zh/model-studio/qwen-tts-voice-list)、[豆包音色列表](https://docs.volcengine.com/docs/DoubaoVoice/Tonelist-1?lang=zh)
- [豆包单向流式 TTS HTTP](https://docs.volcengine.com/docs/DoubaoVoice/unidirectional-streaming-text-to-speech-http?lang=zh)
- [Gemini 语音合成与音色](https://ai.google.dev/gemini-api/docs/speech-generation)、[Interactions 流式事件](https://ai.google.dev/gemini-api/docs/streaming)
- [MiniMax 同步语音合成](https://platform.minimax.cn/docs/api-reference/speech-t2a-http)、[系统音色](https://platform.minimax.cn/docs/faq/system-voice-id)
- [OSS V4 请求头签名](https://help.aliyun.com/zh/oss/developer-reference/recommend-to-use-signature-version-4)、[URL 签名](https://help.aliyun.com/zh/oss/developer-reference/add-signatures-to-urls)
