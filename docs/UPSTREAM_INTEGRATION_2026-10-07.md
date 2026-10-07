# 2026-10-07 上游整合记录

本分支将 vivo 功能分支 `d840f06` 与上游 `main` 的 `9de3a3f`（3.3.0）整合。功能修改和此前真机记录已同步到 `lejw0925/Eta-vivo`；上游提交通过正式 fork `lejw0925/Eta` 的 `feat/virtual-screen-automation-vivo` 分支提供。

## 用户需求与实现范围

| 上游需求 | 本次提供 | 边界 |
| --- | --- | --- |
| [#98 独立虚拟屏](https://github.com/Mangi-11/Eta/issues/98) | Root display、统一 GUI 路由、触控预览、Unicode 输入、续聊保留、空树截图后备、可恢复节点能力、独立熄屏许可与通知授权回退 | 实验性，要求 Android 14+ 和 Root；实机只验证 vivo Android 16，不能承诺所有应用或 ROM |
| [#104 定时任务与场景规则](https://github.com/Mangi-11/Eta/issues/104) | 一次、间隔、每日任务及通知、前台应用、屏幕、供电、连接等 23 种触发器；管理页面、权限状态、队列去重、执行次数和历史 | 后台调度可能延迟；事件按监控范围接收，不是任意脚本执行环境；终端、主屏 GUI 和递归创建任务受限制 |
| [#106 自动记忆与技能](https://github.com/Mangi-11/Eta/issues/106) | 成功任务后独立复盘，按设置追加记忆并创建或更新用户技能，下次任务按需加载 | 会额外请求模型；限额、工具白名单和版本校验；角色会话不参与，总结质量依赖模型与证据 |
| [#107 顶栏 tok/s](https://github.com/Mangi-11/Eta/issues/107) | 顶栏显示最近成功请求的平均输出速度及上下文占比，并持久化统计 | 输出 token / 请求耗时，包含首字等待，排除工具执行和失败重试；按请求结果刷新，不是逐 token 瞬时速度 |
| [#137 用量与缓存统计](https://github.com/Mangi-11/Eta/issues/137) | 补充请求耗时、平均输出速度与最近用量的持久化展示 | 部分覆盖；未新增完整的缓存命中率、缓存写入、首字耗时及每日/提供商累计面板 |

其他修改包括蓝心小 V 6.8.5.3 文字/语音接管、续聊与原子岛进度，vivo 便签/待办读取，蓝牙连接权限入口，侧栏和触发器目录，以及网页搜索 Bing RSS 后备来源。英文、简体和繁体资源同步。

## 与上游的兼容处理

- 保留上游会话模型绑定、未知上下文窗口聊天、统一消息投影、新运行胶囊与边缘流光，以及 CI 和 APK 命名规则。
- 将虚拟屏接入 `type_text`、显式坐标系、归一化坐标、小幅滚动、节点状态字段和动作后观察；这些路径保持指定 display 和现有授权边界。
- 记忆设置和蓝牙权限入口接入上游拆分后的 `AgentMemoryStore`、`PermissionHealthStore`。
- 数据库升级到 25：上游 22 新增任务表、请求耗时与最近用量；vivo 分支 24 补模型绑定字段。两种旧数据库均通过真实 Room 迁移及数据保留回归，不依赖破坏性迁移。
- 调度刷新、请求调度和执行开始使用同一把锁，避免并发刷新替换运行中的 Job。

## 整合后的验证

Temurin JDK 25.0.3，Android SDK 37。执行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease --continue
git diff --check
```

| 检查 | 结果 |
| --- | --- |
| JVM 回归 | 1655 项、278 个测试类，0 失败、0 错误、2 项既有跳过 |
| Android Lint | 0 错误、382 条警告、1 条提示 |
| Debug / AndroidTest / Release | 均构建成功，Release 通过 R8；不代表完成发布签名 |
| 新增迁移回归 | 上游 22 保留模型绑定，vivo 24 保留任务与用量；均升级至 25 |
| 新增坐标回归 | 虚拟尺寸、归一化边界、原始截图像素、缺失坐标系和越界拒绝 |

此前 vivo 真机验证、构造页面截图与微信/QQ 的同会话取树恢复见 [功能测试报告](TEST_REPORT_2026-10-07.md)、[UI 树专项记录](UI_TREE_TEST_2026-10-07.md) 和 [虚拟屏锁屏记录](VIRTUAL_SCREEN.md)。设备为 vivo V2419A，Android 16 / API 36，OriginOS `PD2419B_A_16.1.12.38.W10`，KernelSU，LSPosed；小 V 6.8.5.3。

上述真机记录来自整合前功能版本；整合后的上游分支本轮完成本地 JVM、Lint 和构建，没有重新安装到手机或重跑完整设备/云端模型任务。微信 8.0.78 被测页面在主屏和虚拟屏都没有有效子树，尚未实现独立 Root UiAutomation 后端。锁屏记录仅确认主屏 `UDfinger` 位于 display 0、虚拟屏计数点击成功，不代表所有锁屏场景均已解决。截图均为构造界面，统计文件不包含 UI 文本或私人数据。
