# Agenda、日历与任务行行为核对

核对日期：2026-10-22。基于当前 iOS `~/.config/orgenda/Orgenda` 源码和 Android 工作区；未修改 iOS，未格式化，未切分支。本文区分原有实现、本次修复和保留的平台差异，不把源码核对当成设备实测。

## 组件覆盖

| iOS 组件 | Android 对应 | 核对结果 |
|---|---|---|
| `Features/Agenda/AgendaView.swift` | `AgendaScreen.kt`、根导航 | 已有 Dashboard/逾期/未安排/工作流状态视图、编辑、完成、改期、定位文件；本次增加各视图独立滚动恢复、已删除工作流状态回退 Dashboard。Android 额外保留已完成视图。撤销及日志提示由根导航统一处理。 |
| `AgendaPerspectiveContent.swift` | `AgendaScreen` | 已有逾期→未来七天→近期截止→未安排工作流状态顺序；修复只有逾期项目时未来七天标题与空说明消失的问题。 |
| `AgendaTaskLists.swift` | `AgendaScreen` | 未安排项目按文件分组并按优先级排列；逾期按日期分组。分组、空状态、文件路径语义已存在。 |
| `OrgAgendaPerspective+Presentation.swift` | 资源、`Choice` | 使用 Material 下拉选择与配置状态标签；不复制 SF Symbols。 |
| `AgendaStartupSkeleton.swift` | 根导航初始加载状态 | 根导航负责 workspace 初始状态；本组已向主代理报告 Android 直接显示 no_workspace 的差异。无需复制 iOS 骨架视图外观。 |
| `AgendaCalendarView.swift` | `CalendarScreen` | 已有选中日期和时间线双向联动、前30/后365天延伸、按天隔离重复任务键、宽屏双栏、窄屏独立滚动；本次保存 CalendarPosition（含月末 preferredDay、浏览年月），避免重建界面丢失月末意图。 |
| `AgendaDayContent.swift` | Calendar 时间线、`TaskRow`、`JournalCard` | 已有全天事件优先、今日逾期入口、空日说明、日记；本次恢复日历任务的菜单/长按/无障碍改期入口。日历行继续禁用横向 swipe，避免干扰日历导航。 |
| `AgendaDayHeader.swift` | 时间线 stickyHeader | 已有日期、项目数量、无障碍 heading；本次隐藏零项目数量，与 iOS 信息密度一致。 |
| `AgendaRescheduleSheet.swift` | `RescheduleSheet.kt` | 已有今天/明天快捷操作、初始计划日期、Material 日期选择器、取消/保存与保留时间/截止/重复规则说明；加入根级弹窗占用登记。 |
| `Components/OrgItemRow.swift` | `TaskRow` | 已有原生 Checkbox、状态色/图标、优先级、时间、标签、文件祖先路径、完成、编辑、定位、拖动与手势操作；本次用“标为完成/标为未完成/完成本次”替换误用的配置字段名，并为 Checkbox 补操作语义；菜单按钮改为“更多操作”。 |
| `Components/OrgContextPreview.swift`（任务预览） | TaskRow 只读预览 AlertDialog | 本次为长按预览补完成/改期/定位文件动作与纵向滚动，保持预览正文有界，不在打开预览时打开编辑器或写入工作区。 |
| `Components/HabitHistoryView.swift` | `HabitHistory`、TaskRow 七日标记 | 已有28日记录和日期/完成状态语义；Android保持4×7并显示日期，便于系统字号和TalkBack，不复制 iOS 普通字号2×14的色块造型。 |
| `Components/Calendar/OrgendaCalendar.swift` | `CalendarPanel` | 已有周/月/年纵向分页、按钮翻页、拖动密度、今天、日期选择、选中日期新建任务/长按日记、任务拖放改期；本次修复年视图浏览其他年份后切周视图仍使用旧浏览月份标题，模式chips可横向滚动以容纳大字号。 |
| `OrgendaCalendarDayGrid.swift`、`OrgendaDateButton.swift` | `DayCell`、`WeekdayHeader` | 已有本地化周首日/星期、选中/今天/跨月样式、事项标记、拖放高亮、日期完整语义。点击使用 Material Surface。 |
| `OrgendaCalendarYearGrid.swift` | `YearGrid` | 已有12个月、小月历/大字号月份、月份点击进入月视图、浏览年份不改选中日。 |
| `OrgendaCalendarSizing.swift` | CalendarPanel/响应式布局 | 保持 Android dp、Material触摸目标和大字号横向日期条；密度拖柄触摸区域从40dp扩大到48dp。 |
| `OrgendaCalendarResizeContent.swift`、`OrgendaCalendarResizePreview.swift` | `CalendarResizePreview` | 已有密度拖动时的周/月/年预览；保留现有 Compose 标准布局/Surface，不增加自绘或复制 iOS 弹簧、模糊、几何动画。 |
| `OrgendaDateHeading.swift` | CalendarPanel 日期标题 | 保留 Material 文本按钮和原生日期弹窗，显示浏览月份/年份；不复制 iOS 超大日号的变形过渡。 |

## 本次文件

- `app/src/main/java/com/roifewu/orgenda/ui/AgendaScreen.kt`
- `app/src/main/java/com/roifewu/orgenda/ui/CalendarScreen.kt`
- `app/src/main/java/com/roifewu/orgenda/ui/RescheduleSheet.kt`
- `app/src/androidTest/java/com/roifewu/orgenda/ui/AgendaBehaviorParityTest.kt`

资源由主代理集中补齐：`more_actions`、`nothing_next_seven_days`、`mark_complete`、`mark_incomplete`、`complete_occurrence`。所有新弹窗入口使用共同的 `ModalPresence`，避免快捷方式插入已有模态流程。

## 验证

新增七个测试方法，覆盖以下六类行为；已由主代理统一编译并通过设备回归：

1. Dashboard 仅有逾期项目时仍显示未来七天及空说明。
2. 切换视图后恢复原列表滚动位置。
3. 已删除/不存在的工作流状态视图回退 Dashboard。
4. 日历任务可以通过长按预览改期，无需拖动操作；选中的候选日期在状态重建后保留。
5. 日历日期选择弹窗与候选日期在状态重建后保留。
6. 同日百项任务的列表在状态重建后保留滚动位置，不跳回日首。

既有 `CalendarInteractionTest` 覆盖周翻页保持星期、日期与时间线联动、密度拖动、年内月份选择及2000任务惰性布局。实际执行结果由主验收记录提供，不在此预先宣称通过。

## 保留差异和验证边界

Material Checkbox、下拉菜单、AlertDialog、BottomSheet、DatePicker、FilterChip 与 Snackbar 继续作为 Android 控件；不移植 Liquid Glass、iOS context-menu 预览动画、SF Symbols 或 iOS swipe-action 轨道。Android swipe 使用 Material 滑动确认回弹，等价动作同时可通过菜单和无障碍入口操作。

全天事件仍使用原生列表项（有单独全天标记），并保留完成/编辑/定位行为；未复刻 iOS 胶囊外观。Habit记录保留4×7日期网格。Android按窗口宽度决定双栏/底部导航，以支持分屏和折叠屏，而不按设备是否iPad分支。

真机触觉、TalkBack完整朗读与拖放、极端字体/屏幕组合仍需人工体验验证；本组未把它们标为自动测试通过。0.1.3 的午夜日期刷新统一由根级 TodayProvider 管理，详见第二轮记录。

## 状态恢复补充

日历改期入口使用工作区ID、文件路径、起始位置与源哈希恢复，只有当前文档仍匹配时才重新打开。日期弹窗开关使用saveable，日期草稿继续由Material DatePickerState保存。时间线滚动派生的选中日期一并保存，避免LazyListState恢复后又被初始化定位覆盖。临时拖动状态与只读菜单保持瞬态。新增恢复验证使用StateRestorationTester；真实Activity旋转与进程回收仍以主验收实测为准。

## 第二轮：当前日期与时区更新

iOS 源码依据：`WorkspaceStore+Agenda.swift` 的未来七天和截止窗口依赖调用时 `.now`，`OrgItem.isOverdue` 使用当前日期；`OrgendaRootView` 恢复前台时刷新提醒和存储。当前 iOS 未注册午夜或时区变化监听，且 `WorkspaceStore.overdueItems` 只在事项集合重建时更新。因此此次实现是修复实际实时更新缺口，并非宣称复制了 iOS 已存在的自动日切能力。

Android 新增单一根级 `TodayProvider`，共享 `LocalToday.current.date` 与 `.zone`。可见期间监听系统日期、时间、时区事件和恢复前台，并按本地下一午夜安排一次延迟；后台取消监听与定时，重新可见时立刻重读。日期间隔按 ZoneId 计算，跨夏令时不假定一天恒为24小时，不使用固定轮询。

Dashboard的未来七天/逾期/截止窗口、日历“今天”标记与返回今天按钮、习惯记录日期使用共享日期。日历section缓存加入当前日期与时区，使今日逾期入口增减后后续日标题索引同步更新。日期或时区变化不强制修改用户选中日期，也不丢弃当前编辑草稿。

新增 `TodayStateTest` 验证23/25小时日、无源数据变化的午夜更新、系统日期/时间/时区广播与生命周期恢复。新增 `CurrentDayBehaviorTest` 验证同一workspace revision下逾期迁移、未来七天窗口变化、当前日标记与新增逾期行后的时间线定位。执行结果交主验收记录，不预先标记通过。
