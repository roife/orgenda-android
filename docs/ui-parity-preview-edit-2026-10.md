# Preview / Edit 当前源码对齐（2026-10-22）

参照 `~/.config/orgenda/Orgenda/Features/Files/{Document,Preview}`、`Components/Editor` 和 `Services/Indexing/OrgSyntaxHighlighter.swift`。修改仅在 Android 当前分支；没有运行格式化工具，没有修改 iOS 工作区。

## 功能与行为

| 范围 | 本次调整 |
| --- | --- |
| 预览修改与撤销 | checkbox、工作流、日期和事项编辑的本地保存进入同一文档历史；标题移动只登记一次。保留此前的源码输入历史。每次成功提交记录前后版本与 generation，支持 StateFlow 合并多个发布，以及操作后回到原文的情况。 |
| 外部版本保护 | 无法证明为连续本地修改的内容版本仍清空旧历史；保存失败不登记历史。跨文件转移继续使用原有事务与冲突保护，不把它当作可随意撤销的单文件操作。 |
| 模式与焦点 | Edit / Preview 使用互斥分段按钮；大字号使用菜单。保留两种模式各自的实例状态，切换后保留抽屉展开和原始日期显示选择。回到 Edit 恢复先前键盘意图；大纲选择与日期弹窗关闭恢复编辑焦点。 |
| 中文输入 | 离开编辑或打开日期选择前提交原生 composing 区，并结束输入撤销组。着色仍等待 composing 完成，不替换 Editable。 |
| 标题交互 | 修复原生可选文本吞掉标题点按的问题；点标题可打开事项编辑。标题使用工作流颜色与图标，优先级和标签分行或换行排列。图片标题继续使用图片交互。 |
| 大纲状态 | 行和筛选器使用工作流显示名称，行包含配置的状态图标。过滤值仍使用原始 token。 |
| 块渲染 | 自定义块递归展示代码、表格、列表和可编辑日期。抽屉与生成内容使用同样的布局，但历史日期、生成日期和 checkbox 保持只读。状态历史显示前后状态图标，属性名和值分行。 |
| 日期 | 预览使用简短重复说明，保留起止时间、跳过/完成后重算语义、最大间隔和未知注释；长按仍可切换原始 Org 时间戳。 |
| 源码样式 | 补齐日期、属性、抽屉、链接、列表、注释、进度、脚注、代码、强调等样式，标题颜色跟随工作流。装饰不改变字号或源文。 |

## 间距、留白与缩进

按 iOS `OrgPreviewOutline` / `OrgPreviewContent` 的布局层级区分不同元素，不使用统一的大间距：

- 预览行基础间距 2 dp；一级标题额外 6 dp、次级标题 4 dp、正文段落 8 dp、元数据及其他块 2 dp，列表项不额外加段落间距。首行不加上方空白，源码里的空行不重复堆叠成空白行。
- 阅读区域水平边距 16 dp，上边距 6 dp、下边距 20 dp；大屏正文居中，最大宽度 900 dp。
- 标题每级增加 12 dp 缩进；正文、日期、抽屉、表格、代码及习惯记录统一对齐所属标题的文字起点：计入 24 dp 折叠槽，任务或可完成的重复事件再计入 32 dp 状态槽。深层缩进保留 Android 原有上限，避免窄屏完全挤掉正文。
- 标题 disclosure 的视觉列宽 24 dp、工作流列宽 32 dp；一级标题按钮及 checkbox 的最小布局高度为 40 dp，次级标题按 iOS 参考保留 44 dp，水平槽宽不变。一级标题文字垂直内边距为 2 dp，次级标题为 4 dp，标签下边距为 4 dp；无可折叠内容的标题保留同样的 disclosure 槽。
- 同一段落的连续源码行去除各行两端的展示空白，再用空格合段；单行段落也使用段落间距。保留原始 byte 范围，日期点击、图片、搜索和保存仍定位原文。诗行继续保留换行。
- 去除 TextView 默认字体上下留白，富文本行间距使用 6 dp。列表文本按第一行对齐 checkbox，保留列表自身的嵌套缩进；单元格、列表和属性值不重复显示源码前导空白。
- 引用使用左侧 2 dp 竖线和 10 dp 内容缩进，不再叠加四周的大块 padding。代码块内边距 8 dp，补齐不同代码块的类型标签并去掉 fixed-width 的展示分隔符；表格单元格垂直内边距 5 dp，保留横向滚动。
- 自定义块、抽屉和生成内容内部单独处理段落留白；不把顶层标题缩进再叠加到嵌套块内容上。
- 折叠抽屉最小高度 24 dp，日期行之间间距 6 dp，独立公式不再叠加上下各 8 dp 内边距。解析器单独产生的纯缩进节点在展示分组时视为空行，避免欢迎文档列表前后多出空白正文行；原文、UTF-8 范围和非空错误内容均保留。

缩进修复在默认及两倍字号下检查任务、普通子标题、重复事件、深层标题和标题外正文的实际位置；两项布局回归与七项导航、日期及抽屉交互回归通过，Debug / 测试包构建及 lint 通过。[默认字号截图](../artifacts/ui-parity/preview-indent-2026-10-22/preview-default.png)与[两倍字号截图](../artifacts/ui-parity/preview-indent-2026-10-22/preview-large.png)保存在本机验收目录。

纵向间距收紧及纯缩进空行修复通过 40 项 core 测试、8 项设备布局／抽屉／导航回归，以及 Debug / 测试包构建和 lint。欢迎文档已检查列表、链接、代码块及行内／独立公式的实际布局：[收紧后截图](../artifacts/ui-parity/preview-spacing-2026-10-22/welcome-after.png)。

Material 日期控件、原生文本选择、Android 输入法和系统返回继续使用平台行为；没有仿制 SwiftUI 或 Liquid Glass。

## 字号对齐补充

文档专用的 `DocumentTypography` 按 iOS 默认 Dynamic Type 层级映射到 Android SP；不再用 Material 的 label/body 默认值代替对应字号。

| 内容 | iOS 样式 | Android 基准字号 |
| --- | --- | --- |
| 文件 `#+title` | title2 bold | 22 sp / bold |
| 一级标题 | title3 bold | 20 sp / bold |
| 二级标题 | callout semibold | 16 sp / semibold |
| 三级及以下标题 | subheadline semibold | 15 sp / semibold |
| 正文、诗行、居中段落、大纲标题 | body | 17 sp |
| 列表、表格、标签、引用、元数据、抽屉正文 | subheadline | 15 sp |
| 代码块正文、脚注、图片错误详情 | footnote | 13 sp |
| 日期、优先级、代码块标签、属性名 | caption | 12 sp |
| 抽屉名称、clock 标签/时间/时长 | caption2 | 11 sp |
| Edit 源码 | callout monospaced | 16 sp / monospace |

- 二级及以下标题使用 600 字重，一级标题为 700；表头为 600，标签恢复普通字重。文档 Compose 文本明确设置行高和零额外字距，避免从按钮的 Material 样式继承不同指标。
- 同一行中的列表编号与列表正文使用相同字号；属性值、历史记录、图片失败文案也使用各自对应的文档样式。
- 行内公式使用所在文本的字号和 inline 模式；独立公式按 iOS 的 body × 1.08 显示。RaTeX 的字号经过 Android SP 转换，遵循 Android 14+ 非线性字体放大，不再直接乘全局 fontScale。
- 表格列宽按当前 15 sp 字体和实际字宽测量，并保留横向滚动；不再按字符数乘固定宽度估算，中文和放大字号都使用相同测量依据。
- 系统字号继续由 Android 控制；没有固定物理像素，也没有把 iOS 字体文件带入 Android。

字号补充已通过 Debug / 测试包构建、lint 和 9 项导航、日期及状态恢复设备回归。默认字号与 1.5 倍系统字号分别检查 Preview / Edit。

- [默认字号 Preview](../artifacts/ui-parity/preview-edit-2026-10-22/fonts/preview-default.png)、[默认字号 Edit](../artifacts/ui-parity/preview-edit-2026-10-22/fonts/edit-default.png)。
- [放大字号 Preview](../artifacts/ui-parity/preview-edit-2026-10-22/fonts/preview-large.png)、[放大字号 Edit](../artifacts/ui-parity/preview-edit-2026-10-22/fonts/edit-large.png)。
- 字号样例、日志与 APK 校验和位于 `artifacts/ui-parity/preview-edit-2026-10-22/fonts/`。

## 功能与间距验证（字号调整前）

- `:core:test`、`:app:testDebugUnitTest`、`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:lintDebug`。
- 设备定向回归：`DocumentUndoSafetyTest`、`DocumentInteractionParityTest`、`DocumentNavigationParityTest`、`DocumentDateInsertionTest`、`TimestampRestorationTest`、`OutlineContinuityTest`、`NativeInputSafetyTest`。
- 新增的四项安全回归覆盖 composing 后的连续撤销、发布合并且最终原文不变、外部替换/失败保存保护，以及标题移动登记一次。
- 使用独立 Android 16 模拟器与合成 Org 工作区检查标题编辑入口、预览块、模式切换、深色和大字号；没有连接个人工作区。

此前功能与间距调整通过 40 项 core 单元测试、10 项 app 单元测试和 25 项设备定向回归，共 75 项；Debug 与测试包构建成功，lint 无错误（仍有警告）。

截图与合成样例保存在本机 `artifacts/ui-parity/preview-edit-2026-10-22/`（按仓库规则忽略）：

- [手机 Preview](../artifacts/ui-parity/preview-edit-2026-10-22/preview-phone.png)、[展开抽屉](../artifacts/ui-parity/preview-edit-2026-10-22/preview-drawers.png)、[块与缩进](../artifacts/ui-parity/preview-edit-2026-10-22/preview-blocks.png)。
- [手机 Edit](../artifacts/ui-parity/preview-edit-2026-10-22/edit-phone.png)、[深色大字号 Preview](../artifacts/ui-parity/preview-edit-2026-10-22/preview-dark-large.png)、[深色大字号 Edit](../artifacts/ui-parity/preview-edit-2026-10-22/edit-dark-large.png)。
- `verification.json`、`final-checks.log`、`final-device-tests.log` 记录本轮结果与 Debug APK 校验和。

完整 TalkBack 朗读及真实中文输入法候选窗不属于模拟器自动化结论。
