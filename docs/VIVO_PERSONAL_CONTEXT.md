# vivo 个人上下文

## 支持范围

在 vivo / OriginOS 上启用敏感读取工具并授予 Eta Root 后，可使用以下接口：

| 工具 | 来源 | 返回内容 |
| --- | --- | --- |
| `search_notes` | 原子笔记 `com.provider.notes/note` | 标题、无标签正文、创建及修改时间 |
| `search_todos` | 原子笔记 `com.provider.notes/todo` | 待办正文、原生状态、提醒及修改时间 |
| `read_personal_item` | 上述查询返回的 `eta-vivo:notes:<id>` / `eta-vivo:todos:<id>` | 重新查询当前记录的完整正文（有长度上限） |

检索支持关键词、分页、排序与带时区的 ISO 8601 时间范围。时间筛选为修改时间的左闭右开区间；待办的 `planned_time` 单独表示提醒时间。返回 `freshness=live`，不使用 ColorOS 索引或小 V 聊天记录。vivo 引用目前只用于读取，不能交给 ColorOS 修改工具。

日历、联系人、短信和媒体等现有 Android 原生工具继续按自身权限与设备能力工作。vivo 的 AI Engine 记忆、账单及录音摘要尚未接入；发现加密数据库不代表已具备可读取接口。

## 数据边界

入口复用 `PhoneCommandMain` 的 Root Provider 桥，并固定厂商、来源、字段和 Android 用户 ID。模型不能传入 SQL、数据库路径或任意 URI。关键词通过参数绑定，分页数值严格校验。

便签强制筛选 `dirty IN (0,1)`、`has_passwd IN (0,1)`、`isEncrypted=0`、`move_private=0`；待办强制筛选 `dirty IN (0,1)`、`move_private=0`。缺少保护字段、接口不兼容或权限被拒绝时返回错误，不通过移除筛选重试。每次详情读取重新筛选，旧引用不能读取后来转为私密的记录。

## 验证

实测：vivo X200 Pro mini / V2419A，Android 16，OriginOS 6（`PD2419B_A_16.1.12.38.W10`），KernelSU。

安装 Debug APK 后，通过其实际 `app_process` 入口执行专用标记的空检索：便签、待办均返回有效 live 响应；不存在的详情引用返回 `PERSONAL_CONTEXT_NOT_FOUND`。未读取现有私人正文或创建用户数据。

```sh
./gradlew :app:testDebugUnitTest --tests '*VivoPersonalContextQueryTest' \
  --tests '*AgentToolRequirementsTest' --tests '*ToolCapabilityProjectionTest' \
  --tests '*PersonalContext*Test' --tests '*RootlessDeviceToolsTest'
```

65 个测试通过，覆盖保护状态、状态变化、分页、时间边界、注入输入与厂商能力投影。ROM 更新后仍需重新验证字段兼容性。
