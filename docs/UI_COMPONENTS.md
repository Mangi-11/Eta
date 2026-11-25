# Eta 设置与管理组件

Eta 的应用界面统一基于 **Miuix** 与 HyperOS 4（OS4）玻璃架构构建，所有页面共享同一套状态、Store、Repository、路由栈与页面骨架。

## 根主题与缩放不变式

- `AgentAppTheme` 通过 `MiuixTheme` 解析跟随系统、浅色和深色模式，支持 Monet 取色（含 `CMF`）、强调色与纯黑背景，并通过 `LocalAppearanceSettings` 向下暴露外观配置。
- `MainActivity` 与 `AgentBrowserActivity` 通过 `AgentAppTheme(provideGlassHost = true)` 在根部挂载 `Scaffold`、`LocalGlassSceneBackdrop` 与全窗口 `TopLayerHost`（非 Activity 系统浮窗不挂载该宿主），供 `GlassDialog`、`GlassBottomSheet`、`GlassPopup` 与 `GlassTransformPopup` 采样场景而不产生自采样；二级页通过 `ScaffoldPage` / `PageScaffold` 提供 `LocalGlassPageBackdrop` 与 `GlassTopAppBar`。
- 所有页面和弹层使用 Android 提供的显示密度和字体缩放。
- 聊天和会话侧栏的自定义顶栏只按前景内容占位，背景可延伸到下方形成渐进模糊或渐隐过渡，由 `TopBarBackdrop` 统一承载。

## 组件选择与页面组织

| 场景 | 统一组件（`ui/components/` 及 `top.yukonga.miuix.kmp.*`） |
| --- | --- |
| 二级列表页（LazyColumn + 回弹滚动） | `ScaffoldPage`（内置 `AdaptiveTopAppBar` / `GlassTopAppBar`） |
| 自定义容器二级页（分页、整页编辑器） | `PageScaffold`（内置 `AdaptiveTopAppBar` / `GlassTopAppBar`） |
| 顶栏返回与操作按钮 | `TopBarBackButton`、`TopBarActionButton`、`TextButton`（`GlassIconButton` + `MiuixIcons.Glass.*`） |
| 分组卡片、标题与页脚提示 | `SmallTitle` + `Card` + `FooterHint` |
| 懒加载连续分组条目 | `PreferenceGroupItem` |
| 跳转行 / 信息行 | `ArrowPreference` / `BasicComponent` |
| 开关行 | `SwitchPreference` |
| 下拉 / 弹窗选择行 | `GlassDropdownPreference` / `GlassSpinnerPreference`（基于 `GlassDropdownPopup` + `TopLayer`） |
| 复选 / 单选行 | `CheckboxPreference` / `Checkbox` |
| 搜索栏 | `SearchBar` + `InputField` |
| 分页 / 筛选标签 | `GlassSegmentedTabRow` / `ChoiceChip` |
| 行内与密码输入框 | `TextField`、`SettingsTextField`、`SecureTextField` |
| 页面胶囊按钮 / 悬浮工具栏 | `TextButton`、`GlassFloatingToolbar` |
| 确认 / 提示 / 底部弹框 | 持续组合的 `GlassDialog`（配 `DialogActions` / `GlassDialogButton`）与 `GlassBottomSheet` |
| 单行输入弹窗 | `GlassDialog` + `GlassTextField` / `TextField` |
| 上下文 / 溢出菜单 | `TopLayer` + `GlassPopup` / `GlassTransformPopup` + `GlassPopupItem` |

## 分组、滚动与面板边界

- 长列表使用稳定 key 的独立 Lazy 条目与 `PreferenceGroupItem`，仅在分组首尾绘制连续圆角，每条动态数据独立参与懒加载。
- 开关、复选、单选、输入框草稿与弹窗状态均由页面或 Store 持有，不把业务状态与持久化逻辑塞进底层通用组件。
- 模型选择、远程模型导入和子代理详情统一使用 `GlassBottomSheet`。可滚动内容在面板内持有状态，退出可通过下拉、点击遮罩或系统返回完成；远程模型导入的提交按钮固定在列表下方。
