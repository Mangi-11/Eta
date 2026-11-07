# Eta 设置与管理组件

Eta 支持全局界面样式切换，提供 **COUI** 与 **Miuix** 两种选择。新安装及升级后尚未明确保存样式选择的用户默认使用 **COUI**；用户在“设置 → 外观与主题 → 界面样式”明确选择后，后续升级保留已选样式。两套界面共享同一套业务状态、Store、Repository 与路由栈，切换样式时通过 `AgentAppTheme` 的 `movableContentOf` 移动同一组合树，不丢失导航历史、页面草稿或正在运行的任务。

## 根主题与缩放不变式

- `AgentAppTheme` 同时提供 `CouiTheme`（COUI 模式）与 `MiuixTheme`，并通过 `LocalUiStyle` 暴露当前 `AppearanceUiStyle`（`COUI` / `MIUIX`）。
- COUI 模式在开启模糊且设备支持 `RuntimeShader` 时，在根部挂载 `ProvideCouiMenuBackdrop(isolateContent = false)`，供全局 COUI `Menu`、`Dialog` 与 `InputDialog` 采样窗口背景。
- 界面缩放以 `configuration.densityDpi.toFloat() / DisplayMetrics.DENSITY_DEFAULT` 作为 100% 尺寸基准，并在 `applyInterfaceScale = true` 时同步写入宿主 `context.resources.displayMetrics.density`，保证主页面、独立 Activity（`AgentBrowserActivity`）以及 `Dialog` / `Popup` 独立窗口拥有一致的缩放比例。

## 组件选择与双界面组织

页面层将 `rememberSaveable`、草稿状态、Store 与副作用声明在 `when (LocalUiStyle.current)` 分支之前，再分别组织 COUI 与 Miuix 的界面组合：

| 场景 | COUI 组合（`ui/components/coui/` 及 `com.itos.coui.*`） | Miuix 组合（`ui/components/` 及 `top.yukonga.miuix.kmp.*`） |
| --- | --- | --- |
| 二级页（Column + 回弹滚动） | `CouiScaffoldPage`（`Scaffold` + `LargeTopAppBar` / `TopAppBar` + `couiVerticalScroll`） | `MiuixScaffoldPage` |
| 二级页（长动态列表） | `CouiLazyScaffoldPage` | `MiuixScaffoldPage` |
| 自定义容器二级页（分页、整页编辑器） | `CouiScaffold` | `MiuixScaffold` |
| 顶栏返回与操作按钮 | `CouiBackButton`、`CouiTopAppBarIconButton`、`CouiTopAppBarTextButton`、`TopAppBarButtonGroup` | `MiuixBackButton`、`IconButton`、`TextButton` |
| 分组卡片与标题 | `com.itos.coui.layout.Group(title = ...)` | `SmallTitle` + `Card` |
| 懒加载连续分组条目 | `EtaPreferenceGroupItem` → `CouiGroupItem` | `EtaPreferenceGroupItem` |
| 跳转行 / 信息行 | `CouiPreference`（右侧箭头使用 `CouiIcons.Next`，`12.dp × 24.dp`） | `ArrowPreference` / `BasicComponent` |
| 开关行 | `CouiSwitchPreference`（整行 `Role.Switch` + `CouiHapticFeedback.Switch`） | `SwitchPreference` |
| 下拉 / 弹窗选择行 | `CouiDropdownPreference` / `CouiPopupPreference`（`rememberCouiMenuAnchor` + `Modifier.couiMenuAnchor` + `CouiIcons.Popup` `14.dp × 24.dp` + `Menu`） | `OverlayDropdownPreference` / `WindowSpinnerPreference` / `OverlaySpinnerPreference` |
| 复选 / 单选行 | `CouiCheckboxPreference` / `CouiSelectPreference` | `CheckboxPreference` / `Checkbox` |
| 滑块行 | `CouiSliderPreference` | `SliderPreference` |
| 分组内分割线 | `Divider()`（无前导图标）/ `CouiIconDivider()`（有前导图标） | Miuix 列表间距 |
| 搜索栏 | `CouiSearchBarBox`（COUI `SearchBar` + 局部 `CouiBackdrop`） | `SearchBar` + `InputField` |
| 分页 / 筛选标签 | `CouiChipTabs`（COUI `ChipTabRow`） | `TabRow` / `EtaChoiceChip` |
| 行内输入框 | `CouiTextField` | `TextField` |
| 页面胶囊按钮 | `CouiButton`（`CouiButtonRole`） | `TextButton` |
| 确认 / 提示 / 底部弹框 | `CouiConfirmDialog`、`CouiNoticeDialog`、`com.itos.coui.dialog.Dialog`（`CouiDialogPlacement.Bottom`） | `WindowDialog`、`OverlayDialog`、`OverlayBottomSheet` |
| 单行输入弹窗 | `CouiInputDialog`（`com.itos.coui.dialog.InputDialog`） | `WindowDialog` + `TextField` |
| 上下文 / 溢出菜单 | `com.itos.coui.menu.Menu` + `rememberCouiMenuAnchor` | `WindowListPopup` / `OverlayListPopup` / `OverlayIconDropdownMenu` |

## 分组、分割线与状态边界

- COUI 的 `Group` 内相邻条目之间显式插入 `Divider()` 或 `CouiIconDivider()`；条件行与其前置分割线放在同一可见分支中，避免首尾出现多余分割线。
- 长列表使用稳定 key 的独立 Lazy 条目与 `EtaPreferenceGroupItem`（COUI 自动切换为 `CouiGroupItem`），不将大量动态模型或文件塞进单个 `Group` 或 `Card`。
- 开关、复选、单选、输入框草稿与弹窗开关均由页面或 Store 在 `when (LocalUiStyle.current)` 之上持有，保证运行期切换 `COUI` 与 `Miuix` 时状态与草稿完整保留。
