# Eta

<p align="center"><strong>AI Agent for Android</strong></p>

<p align="center">一个第三方 Android 系统级 AI Agent，能操作手机界面，也能像 Coding Agent 一样读文件、跑命令。</p>

> 底层基于 [libxposed API 102](https://github.com/libxposed/api) 的 Xposed 模块，面向 ColorOS 16。Hook 小布进程拦截对话请求，接入同一套 Agent Runtime，支持 BYOK 自定义模型；**App 本体是主工作台**。此外保留了 system_server、SystemUI、ColorDirectService、Google App 等进程中的早期 Hook 功能（电源键唤醒 Gemini、手势条/双指识屏触发一圈即搜），当前不是重点，后续仍会维护。

## 界面预览

|                     GUI Agent 演示                     |                   小布助手 BYOK：电源键启动                   |
| :----------------------------------------------------: | :-----------------------------------------------------------: |
| ![GUI Agent 演示](docs/Screenshots/demo_gui_agent.gif) | ![小布助手 BYOK：电源键启动](docs/Screenshots/demo_tools.gif) |

|              App 本体聊天首页              |                        小布 BYOK：系统内存分析                        |                    命令执行                    |
| :-----------------------------------------: | :-------------------------------------------------------------------: | :--------------------------------------------: |
| ![聊天首页](docs/Screenshots/chat_home.jpg) | ![小布 BYOK：系统内存分析](docs/Screenshots/chat_breeno_analysis.jpg) | ![命令执行](docs/Screenshots/chat_command.jpg) |

|                  设置                  |                工具能力                |                 Skills                 |
| :------------------------------------: | :-------------------------------------: | :------------------------------------: |
| ![设置](docs/Screenshots/settings.jpg) | ![工具能力](docs/Screenshots/tools.jpg) | ![Skills](docs/Screenshots/skills.jpg) |

## 核心能力

Agent 不会问一句答一句就结束。它在一个 loop 里运转：模型发指令，系统执行，结果写回上下文，模型再决定下一步。如此往复，直到做完。

作为 Android AI Agent，Eta 同时具备 GUI 操作和终端执行能力，这和主流 Coding Agent 的逻辑一致——屏幕是表层，shell 才是完整计算环境。

**GUI 操作**

- **屏幕与控件**：截图、读取带快照标识的无障碍节点、按节点或坐标点击，以及带位移验证的四向滚动
- **应用与系统**：启动 App、把链接显式交给外部应用、模拟按键、下拉通知栏、搜索应用
- **文本与剪贴板**：输入文字、设置剪贴板、粘贴、等待特定文本出现
- **运行可视化**：前台操作时显示浮层与手势反馈，让你知道它正在点哪里、怎么滑

**网页浏览**

`browser_use` 是运行在 Eta 内的 Agent 浏览器，不是简单调用系统 `ACTION_VIEW`。它可以在不抢占前台的情况下加载 JavaScript 网页、提取保留标题/段落/列表/链接等结构的正文、查找并操作页面元素、滚动和截图；需要人工验证或用户想查看过程时，可在 App 中挂载同一个 WebView 直接接管。外部打开链接仍由独立的 `open_uri` 工具负责，两种能力不会混淆。

浏览器只接受 HTTPS，SSL 错误不会被忽略，并对主页面与子资源执行 URL、DNS、主机数量和 Service Worker 限制；这些检查属于纵深防护，WebView 本身并不是可绑定实际连接 IP 的独立网络沙箱，因此不能把它当作访问内网或承载高敏感凭据的安全边界。网页内容会作为不可信数据交给模型，正文按偏移分页并以 UTF-8 字节严格限长；Cookie 不会作为工具结果暴露给模型。网页工具可在设置中关闭。

**终端与文件**

在用户授权下执行 `user` 或 `root` shell 命令，读写文件、列目录、跑脚本、查日志、改配置。会话式 shell 保持 cwd 和环境变量，异步任务后台执行并分段读取输出。默认关闭，需手动开启。

**通用**

- **Skills**：Skills 索引与按需读取，扩展 Agent 的能力边界
- **结果归档**：外部入口触发的运行结果会归档到 App 会话，即使 App 进程被杀也会尝试恢复

## 使用场景

- **跨 App GUI 操作** — "帮我把微信未读消息都点了"，Agent 自己看屏幕、找按钮、执行
- **跨 App 比价** — 截图分析淘宝商品，自动打开京东搜索同款并返回结果
- **网页研究** — 在后台阅读 JavaScript 渲染的文档或资讯页面，保留同一浏览会话；遇到验证码时由用户直接接管
- **终端任务** — "清一下后台，查 LSPosed 日志看 Hook 有没有异常，再看看 Magisk 模块生效了没"——Agent 可以执行 shell 命令、读系统日志、查模块状态、改配置，把意图转化为终端操作
- **小布入口触发复杂任务** — 按电源键唤醒小布，用自然语言让 Agent 执行多步流程

## 边界说明

第三方 Xposed 模块永远做不到系统内置助手那种动画丝滑和入口一致性。但原厂做得烂的时候，Hack 就是用户唯一的选择。

- **这不是聊天机器人换皮。** 普通 AI App 只能输出文字。本项目通过 Xposed Hook、无障碍服务、系统浮层和 ==root== 权限，让 Agent 同时掌握 GUI 和终端——前者操作屏幕，后者进入本机命令层。两个入口叠加，意味着 Agent 拥有接近完整手机环境的操作能力。
- **目标系统为 ColorOS 16。** Hook 点强依赖 OPPO / Google App 当前实现，系统或 App 大版本更新后可能需要重新适配。

## 与豆包手机助手的区别

豆包手机助手证明了手机 AI 的方向：从聊天框走向系统级操作。但它是超级 App，有平台资源，也有平台约束——跨 App 接管会撞上微信登录异常、淘宝人机验证、银行 App 风险提示。厂商要维护商业关系、支付安全和监管合规，天然被生态绑住手脚。

本项目走第三方开发者路线：不代表手机厂商，不需要维护预装合作。用户愿意解锁、==root==、启用 Xposed 和无障碍，就应该能把自己的手机入口接给自己选择的 Agent。风险边界由用户决定，工具必须透明可见，敏感操作必须能随时停止和接管。

更重要的是，我们把终端执行能力放进了 Agent Runtime。普通手机 AI 只会点屏幕，但 Agent 一旦能在用户授权下执行 shell 命令、读写文件、跑脚本、改配置，它就具备了和主流 Coding Agent 同类的"把意图转化为操作"的能力。GUI 是手机表层，终端才是完整计算环境。

## 系统入口接管

项目早期做了大量 ColorOS 系统入口的 Hook 工作，保留至今，当前不是重点：

- **小布接管**：接管小布对话入口，解析图片上下文，交给同一套 Agent Runtime 处理。支持 BYOK，默认只在 `/agent` 前缀下触发
- **Gemini 解锁**：电源键长按唤起 Gemini、锁屏自动语音输入、息屏维持 Hey Google 检测
- **一圈即搜**：手势条长按和双指识屏触发 Android `contextual_search`，不改系统文件

## 模型与 BYOK

Agent 的能力取决于你用什么模型。

- **OpenAI-compatible** 与 **Anthropic** 双协议，支持 SSE 流式传输、流式工具调用、图片输入、推理内容
- **内置提供商**：OpenAI、Anthropic、阿里百炼、DeepSeek、Kimi、MiMo、MiniMax、StepFun、硅基流动、OpenRouter
- **自定义提供商**：自定义 Base URL、API Key、请求头、body JSON
- **模型管理**：内置官方目录、远程拉取列表、自定义模型、启停管理

BYOK（Bring Your Own Key）意味着 Agent 能力跟随你选择的模型，而不是被内置供应限制。

## 安装

<details>
<summary><b>展开安装步骤</b></summary>

1. 在支持 libxposed API 102 的 LSPosed 环境中安装 APK
2. 作用域包含 `system`、`SystemUI`、Google App、小布识屏 和小布助手
3. 重启手机
4. 打开 App，配置模型提供商、API Key 和当前模型
5. 按需授予悬浮窗、无障碍、应用列表读取、位置、后台运行等权限；位置仅在 Agent 调用时间与位置工具时读取，小布等后台入口需要“始终允许”；终端/文件工具以 root 身份执行 shell 命令。用户主动执行 GUI Agent 操作时，如果 Eta 无障碍服务尚未连接，Runtime 会通过 Root 在保留其他服务的前提下启用 Eta，等待服务真实连接后再执行工具；开机或升级广播仍只审计状态
6. 按需开启小布接管、终端/文件工具等

</details>

## 当前限制

- 第三方模块无法获得原厂系统组件的全部私有权限，交互 UI、动画衔接和系统级一致性会弱于厂商内置方案

## 项目结构

核心代码在 `app/src/main/kotlin/`：

```
ModuleMain.kt              Xposed 模块入口
Application 层             App 初始化

hook/system/               system_server Hook
hook/google/               Google App 进程 Hook
hook/colordirect/          ColorDirectService Hook
hook/breeno/               小布入口接管

agent/runtime/             Agent Runtime、跨进程协议、结果归档
agent/model/               模型提供商抽象、SSE 解析
agent/tool/                本机工具执行器
agent/browser/             共享离屏浏览器、网页读取与安全边界
agent/device/              root / 无障碍 / input 设备控制
agent/terminal/            会话式 shell 与文件工具
agent/overlay/             运行浮层与手势反馈
agent/skill/               Skills 解析与索引
agent/accessibility/       无障碍服务、节点快照与授权状态检查

data/db/                   Room：会话、模型提供商、运行归档
data/repository/           仓库层
data/provider/             内置模型提供商与官方模型目录

ui/app/                    App 根状态、导航
ui/screens/                各功能页面
ui.pages/providers/        模型提供商管理
ui/components/             通用组件
systemizer/                Google App 系统化安装器
config/Prefs.kt            RemotePreferences 配置
```

Agent Loop、工具批次、steering 与 transcript 语义见 [docs/AGENT_RUNTIME.md](docs/AGENT_RUNTIME.md)。Gemini、一圈即搜和 RemotePreferences 链路见 [docs/TECHNICAL.md](docs/TECHNICAL.md)。

## 参考与致谢

Eta 的开发过程中参考或关注过以下开源项目：

- [Pi Coding Agent](https://github.com/earendil-works/pi)：Eta Agent Runtime 的核心参考，包括 Agent Loop、工具调用、steering 与 transcript 状态管理
- [OpenOmniBot](https://github.com/omnimind-ai/OpenOmniBot)：Android 端 AI Agent 方向的参考项目

Eta 结合自身的 Xposed 系统入口、Android Runtime、IPC 与模型协议边界进行了独立实现。

<sub>Community: <a href="https://linux.do">LINUX DO</a></sub>
