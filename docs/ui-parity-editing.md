# 事项编辑、Capture 与目标选择的 UI 行为对照

范围：iOS `Orgenda/Features/ItemEditor` 全部 6 个 Swift 文件，`Components/OrgRecurrenceEditor.swift`、`Components/Workflow/OrgWorkflowControls.swift`、`Components/Tags/OrgendaTagList.swift`，以及 `Features/Journal/JournalComposer.swift`；对应 Android `ItemEditorSheet.kt`、`CaptureSheet.kt`、`RefileSheet.kt`、`WorkspacePathPicker.kt`。对照依据源码行为；此文不声称全部动作已经经过真机验证。

## 本次补齐

| 组件 / iOS 依据 | Android 调整 |
|---|---|
| `OrgItemEditor` 标题、草稿与取消 | 新建事项聚焦标题；标题支持多行输入，保存规范为单行；修改过的 sheet 拦截下滑关闭，先确认丢弃，保存期间不允许下滑隐藏 |
| `OrgItemEditor` 状态说明 | 按状态进入/离开、完成、改期、改截止日期的日志规则显示必填说明，并在说明为空时禁用保存；不再始终显示无关说明输入框 |
| `OrgItemDetailsSection` 类型 / 工作流 | 事件和笔记隐藏状态选择；切换为事件优先沿用计划日期、其次截止日期，清除计划日期；切换任务类型时补上初始状态；状态下拉使用 Material 图标和已有工作流色彩 |
| `OrgItemScheduleSection` 日期与时间 | 日期/时间按钮可换行；启用时间时午夜默认到当前时间，时长不足 5 分钟时初始化为 30 分钟；截止日期首次开启优先沿用计划日期；事件日期不提供误关开关 |
| `OrgItemEditor` 提醒与正文 | APPT_WARNTIME 校验 0–1440；正文保留 120 dp 起步编辑空间；文件路径可选中文字，显示原始行号 |
| `OrgSourceView` 草稿源文本 | Org 源文本预览使用等宽字；预览与复制不会被必填日志说明挡住；未保存说明作为 Org 注释保留在复制内容中 |
| `OrgItemEditor` 保存失败恢复 | 错误保留原草稿。最新文档中可唯一定位同标题/祖先路径事项时可查看最新源文本，并先复制草稿再重载。无法唯一定位时不猜测目标 |
| `OrgItemEditor` 移动与归档 | 有修改时提示先保存；归档等待实际结果，失败保留当前编辑器；Refile 成功关闭内外两层编辑界面，避免继续编辑已移动的旧引用 |
| `OrgRecurrenceEditor` 重复规则 | 关闭再开启保留原规则；间隔输入暂时为空时仍保留结构化控件；同单位 Habit 最大间隔不能小于基本间隔；展示原始规则与模式说明，支持原有高级 Org 文本输入 |
| `ConfiguredCaptureView` 预览 / 提交 | 与 iOS 一样先点 Preview，再允许插入；修改输入立即撤销旧预览；每次预览记录目标 blob 基线；显示渲染错误。单模板时不显示多余选择器，生成文本可选中复制 |
| `ConfigurationCaptureDateField` | 日期占位符遵守固定的是否含时间语义，不再显示一个实际不会生效的时间开关；Capture 日期固定包含时间 |
| `JournalComposer` | 默认聚焦正文；标题明确可选并去除两端空白；正文提供 220 dp 起步空间；取消与下滑都保留未保存内容直到确认丢弃 |
| `OrgItemRefileDestinations` | 目标列表补上工作流状态图标；移动中阻止下滑隐藏；保留原有源版本/目标版本校验 |
| 文件 / 文件夹目标选择 | 浏览从当前路径所在目录开始；初始路径指向文件时修正为父目录。目录选择继续过滤自身及后代，不允许覆盖重名文件 |
| 系统快捷方式排队 | 各 sheet 及日期/时间弹窗登记 `ModalPresence`，配合根导航延迟处理外部快捷方式，防止打断草稿 |

## 已有能力与 Material 平台选择

- 事项已有优先级、标签、计划/截止/事件时间、时长、属性提醒、正文、Habit 记录、归档和 Refile、草稿预览；Capture 已有分组模板、提示、建议值、上下文链接、选中文本、剪贴板手动粘贴、缺失标题创建和配置/工作区/目标基线校验。
- `OrgItemEditorField` 的 SwiftUI 焦点枚举由 Compose `FocusRequester` 和 Android 输入法接管；不移植 UIKit / SwiftUI 编辑器本体。
- 使用 Material `ModalBottomSheet`、`OutlinedTextField`、`ExposedDropdownMenu`、`DatePickerDialog`、`TimePicker` / `TimeInput`、`Switch`、按钮和对话框。保留 Android 48 dp 控件触摸区、系统返回及原生输入法，不模拟 iOS 导航栏或 glass。
- iOS 单一主时间的重复规则在 Android 保留每个 Org 时间戳独立编辑，以免覆盖源文件中不同计划/截止时间戳的规则。效果可更细致控制，未强行缩减 Org 语义。
- iOS 的无修饰键单字符模板 / 状态快捷键没有添加到 Android 文本输入区，以免截获正常输入；屏幕上的模板与状态选择保持完整功能。
- 标签显示的行内 chip / 换行属于 Agenda 与预览组件；此处编辑器保留逗号或空白分隔输入。Habit 日历由共享 `HabitHistory` 显示。

## 验证

新增 `EditingInteractionTest`：重复规则关闭/恢复、同单位最大间隔规范化、删除再输入数字时结构化控件不消失。测试由主任务统一运行；没有运行格式化工具，没有新建分支。

仍需人工覆盖：TalkBack 完整朗读、中文输入法、长草稿及异常网络下保存/移动恢复。最新版本恢复只在目标可唯一定位时开放；不通过猜测字节位置替换用户草稿。


## 实例状态恢复

事项的标题、工作流、标签、正文、备注、日期、时长与重复规则，以及 Capture 的模板、回答、正文、上下文和日期、日记的标题与正文都保存必要的实例状态。恢复后 Capture 必须重新预览，不复用旧的生成内容或提交状态。文件移动的名称、目标目录与目录选择器位置也保留。

`EditingInteractionTest` 额外覆盖日记草稿、关闭的重复规则及正在清空的间隔恢复。日记正文和新建标题的自动聚焦在底部弹窗内容挂载后执行，避免恢复阶段的焦点时序异常。
