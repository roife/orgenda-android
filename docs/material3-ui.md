# Material 3 界面优化

本次在已有 Compose Material 3 与动态配色的基础上，统一主界面的颜色角色、列表层级和操作入口。文档正文继续使用原有 DocumentTypography。

## 调整

- 任务行使用 Material `bodyLarge`、`bodySmall`、`labelMedium`，保留完整标题换行、完成状态、滑动、拖放和更多操作。完成按钮保持 48 dp；任务内容起点与列表分隔线对齐。
- 任务标签使用 `secondaryContainer / onSecondaryContainer`，辅助控件直接使用 `onSurfaceVariant`，避免额外混入白色削弱浅色模式对比度。
- 色彩由 `UiColors.kt` 集中管理：标准界面继续使用 `MaterialTheme.colorScheme`，应用专用的表单、禁用、选中、拖放、正文和工作流色彩从主题派生，Compose 与原生编辑器共用语法配色。搜索列表、正文与代码预览共用 `tertiaryContainer / onTertiaryContainer` 高亮；工作流颜色按当前背景保证至少 4.5:1 对比度。系统主题回退色与启动图标色收拢到 `values/colors.xml` 和 `values-night/colors.xml`。
- 仪表盘、日历时间线与文件页共用分组标题，使用 `surfaceContainerLow`，数量独立显示；标题与数量合并为一个无障碍 heading，避免孤立朗读数字。
- 手机导航栏和宽屏导航轨道使用 `surfaceContainer`，选中图标与未选中图标采用实心／空心区分；图标不再重复朗读已有文字标签。
- 横屏导航仅显示图标，通过图标的无障碍名称保留入口含义；竖屏保留文字标签。
- 导航轨道支持垂直滚动，短横屏仍可到达所有入口；系统字号达到 1.5 倍时文件页采用单栏，为文件名和正文保留阅读空间。
- 仪表盘新建任务采用加号图标 FAB，仪表盘和搜索页顶栏提供设置入口。
- 正文编辑／预览切换使用右下角图标 FAB：预览时显示铅笔，编辑时显示眼睛；正文底部预留滚动空间。所有 FAB 使用圆形外观，只显示图标，保留无障碍名称；默认和聚焦状态使用 2 dp 轻微阴影，按下和悬停时为 3 dp。
- 搜索输入采用圆角填充容器，保留搜索键盘动作、清空按钮、焦点恢复和无障碍名称；选中的筛选 chip 增加勾选标记。
- 文件图标使用带形状的主题容器，文件夹与文档具有不同颜色层级；空状态增加主题图标与居中文案。
- 圆角矩形统一使用 12 dp 圆角，由 `OrgendaTheme` 集中定义，覆盖输入框、卡片、标签、搜索栏、菜单、对话框和底部弹层；按钮与分段选择显式使用主题形状，日历展开／收起和正文预览也共用同一弧度。文字按钮也显式指定主题形状，覆盖仪表盘视角入口及弹窗确认／取消等操作，避免按压水波纹沿用 Material 默认的胶囊轮廓。
- 页面与底部弹层的水平边距统一为 16 dp，由 `UiSpacing.edge` 集中管理，涵盖日历、搜索、设置、编辑、捕获、大纲与路径选择。任务行的完成按钮保留 48 dp 触控范围，按钮前与按钮到正文的额外留白各为 4 dp，避免与按钮自身的内边距叠加；正文与分隔线起点统一为 56 dp，右侧操作区保持 16 dp 边距和间隔。选择与 Refile 列表去掉叠加的外层水平留白。设置表单中的 Material 列表行通过 `formListItem` 抵消父容器的水平留白，避免再次叠加 16 dp。正文保留原有段落间距和层级缩进，日期控件之间改为 6 dp，满足已有布局测试要求。

## 共享配置维护

`UiSpacing` 提供 2／4／8／12／16／24／32 dp 间距刻度，以及页面边距、列表间隔、大纲缩进、紧凑控件和 FAB 底部留白等语义配置。通用 padding、Spacer 和排列间隔应从这里取值。`UiSizes` 管理触控范围、图标、进度条、描边、圆角和通用控件尺寸，避免把相同数值的控件尺寸与内容间距耦合。正文表格、列表标记、日历网格和动画计算所需的专用尺寸仍由各组件维护。

标准颜色继续使用 `MaterialTheme.colorScheme`；应用专用透明度、工作流颜色与对比度要求集中在 `UiColors`。原生编辑器和代码预览共用 `sourceForeground`、`sourceAccent`、`sourceSecondary`、`sourceCodeAccent` 与 `sourceMuted`。本次提取保留已有尺寸和色值。

## 2026-10-23 交互反馈补充

- 共享 `Field` 使用 Material 3 `OutlinedTextField`，由组件处理浮动标签、焦点描边与辅助文字布局，沿用已有的 12 dp 圆角和主题容器色；普通描边使用 `outline`，提高输入区域的可辨识度。错误同时提供图标、说明及无障碍错误语义；修正输入或禁用控件后清除错误语义。
- 分段选择和单选 chip 显示勾选标记，并为分段布局预留图标宽度；放不下时保持自动换行。单选 chip 明确使用单选按钮语义。
- 搜索范围改用有内容边距的横向列表，程序切换范围时自动露出选中项。切换关键词或范围后立即进入加载状态，避免短暂显示上一条件的结果；同一查询的后台刷新仍保留结果。
- 搜索结果标题与数量使用浅色层级容器，在窄屏和大字号下自然换行；后台刷新用独立线性进度条，不再与标题争抢横向空间。结果数量、无结果提示和搜索错误支持无障碍动态通知。
- 无结果状态使用主题图标容器、居中标题与说明，继续提供扩大范围和清空搜索操作。
- 搜索范围的日记标签使用独立的「日记／日誌／Journal」文案，避免误用配置项「日记目录」；设置搜索结果去掉空白元信息行。
- 顶栏同步指示器置于 48 dp 容器内，补上同步状态的无障碍名称，避免贴紧屏幕右边缘。

本轮保持现有圆角、图标 FAB、导航结构和正文排版；直接修改当前分支，未运行格式化工具。

最终验收使用当前源码的隔离副本和独立 ADB 服务：Debug 应用与测试包构建通过，14 项单元测试通过，45 项设备回归及 3 项截图检查全部通过。覆盖表单错误语义、选择项状态、搜索与页面导航、窄屏／两倍字号、设置和日期布局。Lint 为 0 errors、394 warnings、5 hints（包含动态字符串查找产生的未使用资源提示）。最终源码校验和、构建日志、测试记录、截图和 Debug APK 保存在 `artifacts/material3-refinement/`；表单与大字号截图使用独立组件场景，搜索结果截图来自完整应用。

## 验证范围

`MaterialLayoutTest` 在 320 dp 宽度下检查普通字号和两倍字号／深色主题：标题不截断、不与操作控件重叠、完成按钮至少 48 dp，且点击只触发一次，并检查分组标题与数量的合并语义。`MaterialAdaptiveLayoutTest` 检查宽屏文件页的字号适配及短横屏导航。其余回归覆盖任务分组与状态恢复、滑动与拖放、页面导航、搜索、文件浏览和日历交互。

验证截图与运行结果保存在 `artifacts/material3/`。构建检查使用 `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`；没有运行格式化工具。

最终隔离构建通过，Lint 为 0 errors（393 warnings、5 hints）；48 项设备界面测试全部通过。为避免其它并发构建及设备安装互相干扰，构建在临时源码副本执行，设备回归使用独立 ADB 连接。

文字按钮按压形状修复通过 Debug 应用和测试包构建，并通过 `MaterialLayoutTest`、`AgendaBehaviorParityTest`、`MaterialAdaptiveLayoutTest` 共 13 项设备测试；验证记录保存在 `artifacts/button-press/`。

色彩集中管理回归通过 Debug 构建、测试 APK 构建和 Lint（0 errors），14 项单元测试与 API 36 上的 13 项界面测试全部通过，覆盖工作流对比度、Unicode 搜索高亮、深浅色切换、原生编辑器默认色、表单和正文抽屉。日志与测试结果保存在 `artifacts/colors/`。

2026-10-23 边距统一专项验证：隔离源码副本的 Debug、测试包构建及 Lint 通过（0 errors，393 warnings、5 hints），46 项设备回归全部通过，覆盖窄屏／大字号任务行、横屏导航、选择弹层、日期布局、预览、日历、大纲、文件搜索、设置与编辑状态恢复。另检查了手机任务列表和工作流设置的实际对齐。构建包、源码校验和、日志及截图保存在 `artifacts/spacing/`；未运行格式化工具。

2026-10-23 共享配置整理验证：UI 中 `dp` 字面量由 276 处减少到 110 处，保留现有尺寸和色值。Debug 应用、测试包构建、14 项单元测试及 Lint 通过（0 errors，393 warnings、5 hints）；独立 API 36 模拟器上的 27 项回归全部通过，覆盖主题配色、窄屏／大字号任务行、自适应导航、表单、日期控件、正文预览和日历交互。首轮共享模拟器测试受另一组安装／测试中断，已在独立设备重跑。日志、报告与源码校验和保存在 `artifacts/ui-tokens/`；当前分支直接修改，未运行格式化工具。

## 官方依据

- [Material 3：颜色角色](https://m3.material.io/styles/color/roles)
- [Material 3：字体](https://m3.material.io/styles/typography/overview)
- [Material 3：输入框、辅助文字与错误图标](https://m3.material.io/components/text-fields/guidelines)
- [Material 3：导航栏](https://m3.material.io/components/navigation-bar/overview)
- [Android：Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Android：无障碍错误语义与动态通知](https://developer.android.com/develop/ui/compose/accessibility/semantics)

## 2026-10-23 设置面板紧凑布局（选定方案 3）

本轮取代先前过大的设置组间留白。依据用户选定的 Product Design 第 3 张方案，设置页保留 16 dp 水平边距，分组间隔缩为 12 dp；连续设置项使用至少 48 dp 的标签／值行和轻分隔线，输入字段间隔 8 dp。状态、添加与更多操作同处一行；默认状态与日志统一右侧值列。状态标签采用内容大小的轮廓，外围保留完整点击区域。

`SettingsLayout.kt` 集中管理设置分组、紧凑选择行、状态行、弹层正文和可换行的操作区。`LocalSettingsLayout` 限定紧凑选择样式的使用范围。文件路径的浏览按钮移入输入框；提醒数值与预设相邻，JSON 操作可在窄屏换行。外观去除重复标题。

大字号下，设置标签与数值上下排列，工作流预设操作移到标题下一行；弹层可滚动，表单首个浮动标签保留必要空间。保留现有持久化、撤销、取消和工作流编辑逻辑。当前分支直接修改，未运行格式化工具。

设计对比和范围说明见根目录 `design-qa.md`。截图、构建记录、回归记录及 Debug APK 位于 `artifacts/settings-spacing/`。
