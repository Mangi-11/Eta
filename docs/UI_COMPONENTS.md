# Eta 设置与管理组件

Eta 支持全局界面样式切换，提供 **Miuix** 与 **COUI** 两种选择。新安装默认使用 Miuix。用户可在“设置 → 外观与主题 → 界面样式”切换，选择会保存并在后续升级时恢复。两套界面共享同一套业务状态、Store、Repository 与路由栈，切换样式时通过 `AgentAppTheme` 的 `movableContentOf` 移动同一组合树，不丢失导航历史、页面草稿或正在运行的任务。

## 根主题与缩放不变式

- `AgentAppTheme` 同时提供 `COUITheme`（COUI 模式）与 `MiuixTheme`，并通过 `LocalUiStyle` 暴露当前 `AppearanceUiStyle`（`COUI` / `MIUIX`）。
- COUI 模式使用 Activity 根部的 COUI `Scaffold` 托管弹层。页面按需录制背景，顶栏与弹层在录制范围之外采样。独立系统浮窗使用适合软件画布的实色和圆角。
- Miuix 模式采用 HyperOS 4（OS4）玻璃架构：`MainActivity` 与 `AgentBrowserActivity` 通过 `AgentAppTheme(provideGlassHost = true)` 在根部挂载 `Scaffold`、`LocalGlassSceneBackdrop` 与全窗口 `EtaTopLayerHost`（COUI 与非 Activity 浮窗不挂载该宿主），供 `GlassDialog`、`GlassBottomSheet`、`GlassPopup` 与 `GlassTransformPopup` 采样场景而不产生自采样；二级页通过 `MiuixScaffoldPage` / `MiuixScaffold` 提供 `LocalGlassPageBackdrop` 与 `GlassTopAppBar`。
- 两种样式的页面和弹层使用 Android 提供的显示密度和字体缩放。
- 聊天和会话侧栏的自定义顶栏只按前景内容占位，背景可以延伸到下方形成渐隐。两种样式各自使用组件库的模糊与回退样式。固定 COUI 顶栏使用完整凝光，可折叠 COUI 顶栏随滚动显现。

## 组件选择与双界面组织

页面层将 `rememberSaveable`、草稿状态、Store 与副作用声明在 `when (LocalUiStyle.current)` 分支之前，再分别组织 COUI 与 Miuix 的界面组合：

| 场景 | COUI 组合（`ui/components/coui/` 及 `io.github.suqi8.coui.kmp.*`） | Miuix 组合（`ui/components/` 及 `top.yukonga.miuix.kmp.*`） |
| --- | --- | --- |
| 二级页（Column + 回弹滚动） | `CouiScaffoldPage`（`Scaffold` + `TopAppBar` / `SmallTopAppBar` + `overScrollVertical`） | `MiuixScaffoldPage`（`GlassTopAppBar`） |
| 二级页（长动态列表） | `CouiLazyScaffoldPage` | `MiuixScaffoldPage`（`GlassTopAppBar`） |
| 自定义容器二级页（分页、整页编辑器） | `CouiScaffold` | `MiuixScaffold`（`GlassTopAppBar`） |
| 顶栏返回与操作按钮 | `CouiBackButton`、`CouiTopAppBarIconButton`、`CouiTopAppBarTextButton` | `MiuixBackButton`、`MiuixTopBarActionButton`、`TextButton`（`GlassIconButton` + `MiuixIcons.Glass.*`） |
| 分组卡片与标题 | `CouiPreferenceGroup`（`PreferenceCategory` + `Card` + `CardListGroup`） | `SmallTitle` + `Card` |
| 懒加载连续分组条目 | `EtaPreferenceGroupItem` → `CouiGroupItem` | `EtaPreferenceGroupItem` |
| 跳转行 / 信息行 | `CouiPreference`（`ArrowPreference` / `BasicComponent`） | `ArrowPreference` / `BasicComponent` |
| 开关行 | `CouiSwitchPreference`（`SwitchPreference` / `SwitchLoadingPreference`） | `SwitchPreference` |
| 下拉 / 弹窗选择行 | `CouiDropdownPreference` / `CouiPopupPreference`（`OverlayDropdownPreference` / `OverlayListPopup`） | `GlassDropdownPreference` / `EtaGlassSpinnerPreference`（基于 `GlassDropdownPopup` + `EtaTopLayer`） |
| 复选 / 单选行 | `CouiCheckboxPreference` | `CheckboxPreference` / `Checkbox` |
| 分组内分割线 | `CardListGroup` 绘制相邻行分隔线 | Miuix 列表间距 |
| 搜索栏 | 页面顶栏用 `TopAppBarSearchBar`，面板内用 `SearchBar` | `SearchBar` + `InputField` |
| 分页 / 筛选标签 | `CouiChipTabs`（COUI `ChipTabRow`） | `GlassSegmentedTabRow` / `EtaChoiceChip` |
| 行内输入框 | `CouiTextField` | `TextField` |
| 页面胶囊按钮 / 悬浮工具栏 | `CouiButton`（`CouiButtonRole`）、`FloatingToolbar` | `TextButton`、`GlassFloatingToolbar` |
| 确认 / 提示 / 底部弹框 | `CouiConfirmDialog`、`CouiNoticeDialog`（`OverlayDialog`）、`CouiBottomSheet`（`OverlayBottomSheet`） | 持续组合的 `GlassDialog`（配 `MiuixDialogActions` / `GlassDialogButton`）与 `GlassBottomSheet` |
| 单行输入弹窗 | `CouiInputDialog`（`OverlayDialog` + `TextFieldState`） | `GlassDialog` + `TextField` |
| 上下文 / 溢出菜单 | `OverlayListPopup` + `DropdownImpl` | `EtaTopLayer` + `GlassPopup` / `GlassTransformPopup` + `GlassPopupItem` |

## 分组、分割线与状态边界

- COUI 分组以 `CardListGroup` 的 `item` 注册每行，由组件处理条件行的卡片位置和分隔线。
- 长列表使用稳定 key 的独立 Lazy 条目与 `EtaPreferenceGroupItem`（COUI 自动切换为 `CouiGroupItem`），每条动态数据独立参与懒加载。
- 开关、复选、单选、输入框草稿与弹窗开关均由页面或 Store 在 `when (LocalUiStyle.current)` 之上持有，保证运行期切换 `COUI` 与 `Miuix` 时状态与草稿完整保留。

模型选择、远程模型导入和子代理详情在两种样式中都使用原生底部面板。可滚动内容在各自的面板内持有状态，退出可通过下拉、点击遮罩或系统返回完成。远程模型导入的提交按钮固定在列表下方。
