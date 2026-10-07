# 定时与事件任务

通过自然语言创建任务，例如「每个工作日早上九点整理今天的日程」或「充电时整理待办，最多运行十次」。AI 应先调用 `tasks_triggers` 核对本机能力，再保存结构化规则。「设置 → 自动任务」提供目标与规则查看、暂停、立即运行、取消和删除；规则修改可继续在聊天中完成。

## 模型工具

| 工具 | 用途 |
| --- | --- |
| `tasks_triggers` | 查询事件、权限状态、附加条件与调度限制 |
| `tasks_list` | 查看目标、规则、状态、次数与更新版本 |
| `tasks_create` / `tasks_update` | 保存规则；更新需要 `expectedUpdatedAt` |
| `tasks_run` / `tasks_cancel` | 手动排队或取消已有运行 |
| `tasks_delete` / `tasks_history` | 删除任务或查询执行记录 |

暂停任务也可手动运行，但仍受次数上限和已有运行限制。权限不足的已实现来源可用 `enabled=false` 保存草稿；尚未实现的触发器不能保存。

## 已实现的触发器

自动任务页按时间、设备、连接、应用与通知、Eta 事件分组展示全部 23 种已实现触发器，显示本机当前可用数量。未授权的来源仍会显示，点击查看所需权限与适用范围；返回页面后刷新状态。未实现来源不列入可用目录。

| 来源 | 类型与范围 |
| --- | --- |
| 时间 | `once`：带时区偏移的 ISO 时间；`interval`：至少 900 秒；`daily`：`HH:mm`、IANA 时区及可选周一至周日编号 |
| 通知 | `notification_posted` / `notification_removed`；需要通知读取授权，可按包名、标题或正文包含文本匹配；忽略 Eta 自身通知 |
| 前台应用 | `app_foreground`；需要无障碍连接，仅主屏窗口，可按包名匹配 |
| 屏幕 | `device_unlocked`、`screen_on`、`screen_off` |
| 供电 | `charging_connected` / `charging_disconnected`、`battery_low` / `battery_okay` |
| 连接 | 网络、蓝牙、耳机的 `*_connected` / `*_disconnected`；网络可按 `wifi`、`cellular`、`ethernet` 或 `other` 匹配，蓝牙需要连接授权 |
| Eta / 系统 | `device_boot`、`memory_updated`、`task_completed` / `task_failed`；任务结果必须指定来源 `filters.taskId`，不响应自身结果 |

所有规则可附加 `charging`、`unlocked`、`networkConnected` 布尔条件，按 AND 匹配。默认冷却 900 秒；可设 60–86400 秒与最多 1–10000 次运行。例如：

```json
{"name":"工作日日程","prompt":"整理今天的日程并给出简短摘要","trigger":{"type":"daily","time":"09:00","timeZone":"Asia/Shanghai","weekdays":[1,2,3,4,5],"conditions":{"networkConnected":true}},"cooldownSeconds":900,"maxRuns":30}
```

## 调度、隐私与执行

Room 25 保存任务和执行记录，以 `(taskId, fireKey)` 去重；同一任务只保留一个待运行或执行中的实例。AlarmManager 与持久化 JobScheduler 共同唤醒，到期后复核网络与执行条件，**可能延迟，不适合作为准点闹钟**。deadline 用于延后检查，不保证准点执行；离线时保留队列。条件未满足时延后检查；错过的周期不会逐次补跑。系统结束进程后，把执行中的记录标为 `interrupted`，计入运行次数，不重放不确定操作；排队任务可继续调度。缺少模型配置时标记 `configuration_required`，不消耗执行次数，完成配置后可手动运行。

系统事件监控服务只在相关事件任务启用时运行。网络与耳机注册可能报告当前状态；监控停止期间的事件不补发。ROM 的后台限制仍适用，能力查询返回监控状态。通知正文仅在本地内存中匹配，任务事件记录只保存来源类型、包名、任务 ID 或网络类别。

任务执行读取当前模型配置，复用 Runtime、权限检查和对话归档。后台任务不能自动执行主屏 GUI、浏览器 GUI、终端命令或再创建任务；开启并授权的 `virtual_screen` 可用。其他工具仍遵循 Eta 原有权限，不是通用隔离沙盒。前台用户任务优先，后台任务遇到忙碌时延后，取消或失败不会自动重试。

## 后续触发器设计

`tasks_triggers` 将位置进入/离开、日历临近、文件变化、共享内容和 vivo 系统记忆更新标为未实现。可继续考虑 Wi-Fi SSID、蓝牙设备过滤、电量区间、应用退出、快捷开关、NFC 标签、剪贴板变化与订单状态。扩展时应同时定义权限、首次状态、去重键、有效期和事件摘要；本地匹配通过后再调用模型。复杂 AND/OR 事件组合与任务依赖环检测尚未实现，当前使用精确来源、冷却和次数上限限制连锁运行。

真机使用本机模拟模型验证了手动执行、记忆更新事件和一次性到期规则；最大运行次数、测试数据清理及模型选择恢复均通过。OriginOS 冻结曾延后唤醒及测试观察，因此后台可靠性仍取决于 ROM 的运行限制。

参考 [Hermes 调度器](https://github.com/NousResearch/hermes-agent/blob/6590f13a1ba21b18224a0f53ef2ead004b5fa7d6/cron/scheduler.py)、Android [非精确定时](https://developer.android.com/develop/background-work/services/alarms)、[JobInfo.Builder](https://developer.android.com/reference/android/app/job/JobInfo.Builder) 与 [JobService](https://developer.android.com/reference/android/app/job/JobService)。
