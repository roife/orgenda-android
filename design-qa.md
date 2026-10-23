# 设置面板设计验收 — 2026-10-23

## 视觉依据与范围

用户选择了 Product Design 三张方案中显示的第 3 张。原图：`artifacts/settings-spacing/design/selected.png`（984 × 1599 px）。实现截图：`artifacts/settings-spacing/design/implementation-final.png`（585 × 951 px）。原生 Android Compose 内容视口为 390 × 634 dp，密度 1.5；参考图按自身宽高比归一化为相同视口，未拉伸成最初提示词中的 390 × 844。比较中文、浅色、默认 TODO / DONE、LOGBOOK、未展开菜单的工作流页，不含系统栏。

完整并排比较：`artifacts/settings-spacing/design/comparison-final.png`；状态行局部比较：`artifacts/settings-spacing/design/detail-state-rows.png`。面板总览：`artifacts/settings-spacing/design/all-panels.png`。本任务修改现有 Android 应用，未创建网站或替换运行时。

## 比较记录

1. 用户否定了前一轮过大的留白。改为第 3 张方案中的状态行、标签／值设置行、轻分隔线。组内按控件自身高度排列，表单字段间隔 8 dp，组间 12 dp，页面边距 16 dp。
2. 首次并排比较发现状态按钮和数值框过宽（P2）。状态改为内容大小的标签，外围保留至少 48 dp 点击范围；普通字号数值列统一为 112 dp。对应修复前证据为 `comparison-first.png`，修复后证据为 `comparison-final.png` 和局部图。
3. 320 dp、两倍字号下，页标题和预设按钮互相挤压（P2）。大字号时预设按钮放到标题下一行，选择项采用标签在上、值在下的布局。修复前：`compact/settings-workflow-2.0.png`；修复后：`final/settings-workflow-2.0.png`。
4. 系统级两倍字号下，WebDAV 和提醒数值对话框的首个浮动标签被滚动区域裁剪（P2）。为这两个滚动表单增加与字号匹配的顶部标签空间，不增加普通设置列表的组间留白。最终截图和验证结果见 `system-large/`。

## 五项视觉检查

- 字体：沿用原生 Material 字体和系统中文字体；主内容 bodyLarge、数值 bodyMedium、分组 titleSmall。中文标签不缩小，长标签可换行；大字号改为纵向布局。
- 边距与节奏：标题、状态标签、字段和分隔线共享 16 dp 边线；默认状态与日志可在参考视口同时看到。状态标签的可见轮廓小于点击范围。输入、关闭和保存仍可滚动到达。
- 色彩：沿用应用动态 Material 主题。主文字、主色标题、浅色已结束状态和 outlineVariant 分隔线保持角色一致；动态主题的具体色值与生成图存在预期差异。
- 图像及图标：此方案没有照片或插画资产。使用项目已有 Material 图标，未把界面栅格化；生成图仅作为设计参照。
- 内容：保留应用已有中英繁体文案与真实工作流值。日志抽屉保持可直接编辑的输入框，未照抄生成图中不适用的下拉箭头。延续应用既有 12 dp 圆角；这些是明确的原生产品约束。

## 覆盖与验证

设置首页、外观、工作流、模板列表及编辑、文件与日程、路径多选、工作区浏览、提醒及数值编辑、JSON 配置与粘贴、单选弹层、WebDAV 均有实际截图与导航验证。普通字体和深色两倍字体分别捕获；另通过系统 `font_scale=2.0` 验证独立窗口的弹层。测试结束后恢复为 1.0。

Debug 应用和测试包构建通过；14 项单元测试通过。16 项设备回归通过，包括设置保存、失败重试、取消、状态恢复、输入和表单反馈。最后的尺寸调整另重跑 3 项设置布局测试。Lint 通过，0 errors；现有 warnings/hints 未作为本轮间距任务扩展处理。

未在无真实冲突数据的情况下执行远端冲突解决、外部文件授权或真实 WebDAV 登录；这些分支只做了布局代码检查，未修改业务协议。系统状态栏不属于参考图范围，不以测试宿主的系统栏样式作为应用内容验收结论。

## 最终检查

- 最后一轮系统两倍字号导航测试通过（`system-large-text-tests.log`）；重新打开了 WebDAV 与提醒数值截图，首个浮动标签均完整可见，取消与保存位于可达区域。
- 最终 Debug 与测试 APK、Lint 通过；构建前后五个主要修改文件的校验和一致，记录于 `final-source.sha1`。
- 无待修复的 P0/P1/P2 问题。生成图与系统字体的细微光栅化、文案长度、动态主题色值及既有圆角差异已列为预期约束。

final result: passed

---

# 2026-10-23 文档预览字体专项

## 目标与范围

以用户提供的欢迎文档截图为改进基线，按对比、重复、对齐、亲密性调整原生 Android 排版。字号规范见 `docs/document-typography.md`。这是排版重新设计，字号和组间距离与原图的差异属于预期修改。

- 源图：`artifacts/document-typography/reference.png`，784 × 652 px。原始截图裁剪范围和设备密度未知；按 392 dp 宽度进行视觉归一化，不声称逐像素复刻。
- 实现原图：`artifacts/document-typography/normal.png`，1029 × 2400 px，392 dp 宽、density 2.625、fontScale 1。
- 普通字号对比：`artifacts/document-typography/comparison.png`。实现顶部 1029 × 856 px 归一化为 784 × 652 px，与源图同图并排；左侧为原图，右侧为实现。
- 大字号：`artifacts/document-typography/large.png`，840 × 2400 px，320 dp 宽、density 2.625、fontScale 2；可读局部图为 `large-detail.png`。
- 状态：Projects 展开、A small example 与任务正文折叠，保留同一标题、作者、启动属性、任务与标签。截图来自直接渲染 OrgPreview 的原生组件宿主，非完整应用导航截图；截图在点击前捕获。
- 并行改动：右侧拖动手柄在本次工作期间被另一项拖拽修改移除。字体改动没有移除控件，也没有回滚该修改；验收聚焦当前文字与控件布局。

## 比较记录与五项检查

1. 第一轮普通字号、两倍字号检查确认任务不再与分组争夺视觉重点，标签左缘与任务标题一致。大字号时控件偏向文字区域上缘，随后增加随字号计算的顶部偏移，使其靠近标题首行。
2. 第二轮以 `comparison.png` 与 `large-detail.png` 重新检查。未发现文字截断、重叠或控件遮挡；长任务自然换行，标签仍紧贴任务。两张图的字形与间距清楚可辨，不需要额外局部放大。
3. 字体：系统字体；标题 26 sp / Bold，分组 20、18、16 sp / SemiBold，任务 16 sp / Medium，辅助文字 13 sp / Normal。富文本与纯文本标题共用字号、字重；文档正文继续为 17 sp。
4. 对齐与节奏：标题、标签共用一列；标签上方 2 dp。一级分组前间距 12 dp，子级 4 dp。保留原有层级缩进和段落起点。
5. 色彩：沿用 Material 与工作流颜色，本次没有更改配色。截图宿主使用默认浅色 Material 主题，与用户设备的动态背景存在预期细微差异。
6. 图像与图标：保留原生 Material 折叠和状态图标，没有新增位图、插画或模拟图标。
7. 内容：欢迎文档的可见文案与参考一致，任务文本与标签未被简写或省略。

## 验证与边界

当前分支修改，未运行格式化工具。源码副本 `/tmp/orgenda-typography-3NX68r` 的 Debug 应用和测试包构建通过，避开共享构建目录的并行修改。字体和预览文件的校验和记录在 `artifacts/document-typography/verified-source.sha1`；工作区其他文件仍有并行改动，本记录不替这些修改作完整回归结论。

新增检查覆盖普通字号／320 dp 两倍字号的标签对齐、标签归属、标签单击打开一次、状态按钮独立点击和末尾分组可达。既有习惯记录与正文对齐检查适配了并行拖拽修改后的容器标记，未减少布局断言；同时运行抽屉回归。最终 6 项测试通过，记录见 `artifacts/document-typography/final-device-tests.log`。验收完成后，工作区 `OrgPreview.kt` 的拖拽调用又发生一处并行修改（移除 `consumeDown = false`）；字体、间距和本轮测试文件保持一致。已验证的预览源码另外保存为 `artifacts/document-typography/OrgPreview.verified.kt`，该后续拖拽行为不在本轮验证结论内。

无待修复的 P0/P1/P2 视觉问题。动态深色主题和全应用导航不属于本轮截图覆盖；这是专项排版验收。

final result: passed

---

# 2026-10-23 标题折叠符号与紧凑间距修正

用户反馈上一版折叠符号与标题错位、标题间距过大，按 P2 问题处理，取代上一版对应验收结论。

- 基线：`artifacts/document-typography/alignment/before.png`，784 × 652 px，上一版真实渲染截图。
- 新实现：`artifacts/document-typography/alignment/normal.png`（392 dp，1029 × 2400 px，density 2.625）与 `large.png`（320 dp，840 × 2400 px，fontScale 2）。同为欢迎文档，Projects 展开，示例与任务正文折叠。
- 比较证据：`alignment/comparison.png`，左旧右新。新版顶部 1029 × 856 px 归一化至 784 × 652 px；大字号局部见 `alignment/large-detail.png`。文字和图标清晰可辨，无需进一步放大。
- P2 修正：移除按字号乘数估算的顶部偏移，采用与原生标题相同字体、字重和系统缩放后的真实字体度量，让图标中心通过 Row 的基线对齐机制与标题首行相接。多行标题及其标签不参与图标的垂直居中计算。
- P2 修正：各级标题前间距统一为 10 dp，正文段落仍为 8 dp；两者另有共享的 2 dp 列表间隔。去除标题文字列上下各 8 dp 留白，并将控件可见高度收至 24 dp，保留 Compose 默认扩展触控行为。
- 五项检查：字号、字重、字体与上一版一致；标题组间节奏明显缩紧且没有文字重叠；颜色未变；图标沿用 Material 原生矢量；文案与折叠状态一致。窄屏两倍字号下标题自然换行，图标仍对齐首行，标签仍跟随标题。
- 新增回归直接比较原生 TextView 首行中心和折叠按钮的实际窗口坐标，覆盖一级分组、二级分组、带标签任务及两倍字号，不再只检查估算数值。
- 构建与测试记录位于 `artifacts/document-typography/alignment/`。改动在当前分支完成，没有运行格式化工具。

隔离源码副本的 Debug 应用与测试包构建通过；6 项设备界面测试全部通过，包括实际首行对齐、标签交互、习惯记录与正文对齐以及抽屉回归。最终并排检查未发现待修复的 P0/P1/P2 问题。

final result: passed
