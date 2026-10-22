# 跨组件 UI 行为核对

本记录比较当前 iOS 工作区 `~/.config/orgenda/Orgenda` 与 Android Compose 实现。范围为图片、拖放、根导航、设计系统和通用可访问性；其他五个专项记录覆盖各业务页面。这里只确认源码行为，不把代码阅读等同于 TalkBack 或真机验收。

## 组件对应与结论

| iOS 组件 | Android 对应 | 行为核对及处理 |
|---|---|---|
| `Features/Root/OrgendaRootView.swift`、`App/OrgendaQuickActions.swift` | `OrgendaApp.kt`、`MainActivity.kt`、`OrgendaApplication.kt` | 保留 Dashboard、Calendar、Files、Search 和系统快捷入口。Android 用底部 NavigationBar / 大屏 NavigationRail，设置保留独立目的地。本轮已补齐快捷入口清空搜索/回文件根目录、无日期 Capture 与日历定日任务分离、弹窗排队、启动和保存失败保护。 |
| `DesignSystem/OrgendaTheme.swift`、`ConfigurationColor+Presentation.swift` | `Common.kt` | Material 动态色、系统字号、浅色/深色、原生主题替代 iOS 固定 accent；配置工作流颜色/图标语义仍保留，颜色按当前 surface 提升对比。无需复制玻璃材质、圆角、字重或 SF Symbols。 |
| `DesignSystem/OrgendaMotion.swift` | Material / Compose 自带动画、`CalendarScreen.kt` | 保留选择和布局变化的反馈。采用 Compose/系统动画机制，不复制 iOS 的 spring 参数；需要实际设备系统动画关闭设置验收。 |
| `DesignSystem/OrgendaHaptics.swift` | `DragAndDrop.kt`、`CalendarScreen.kt`、Snackbar | 拖放/日期选择已有平台触觉；修改完成与失败以 Snackbar、表单错误和撤销反馈为主，不添加全局后台振动。iOS 的每次成功/失败振动不作为 Android 必须复刻项。 |
| `DesignSystem/Layout/OrgendaFlowLayout.swift` | Compose `FlowRow` 与可横向滚动的 Material chips | 内容换行/溢出采用原生布局。Calendar 模式 chips 的短窗口可达性由日历专项处理。 |
| `DesignSystem/Layout/OrgendaEmptyState.swift` | `Common.kt` 的 `EmptyState` 与根启动状态 | iOS 空态在短窗口/大字号下可滚动，Android 原先为固定 Box。已补可滚动空态与“工作区不可用”独立错误标题。 |
| `Components/Gestures/OrgHeadingDrag.swift` | `DragAndDrop.kt`、`OutlineSheet.kt`、`OrgPreview.kt` | 两端均使用原生应用内类型化拖放；Android payload 通过 `localState` 传递，外部应用无法伪造内部对象。iOS 使用目标行上下区域推断 before/after/child；Android 原生对话框明确选择插入位置，并提供上移/下移/缩进/减少缩进菜单，作为可访问的等价操作保留。 |
| `Features/Files/Preview/Images/OrgPreviewImageView.swift` | `PreviewImage.kt` | 内嵌图片最大高度、点击打开、长按重载、加载、失败重试、全屏关闭、双击/双指缩放均已存在。本轮修正全屏弹窗登记、加载与点击语义、缩放和平移边界及窗口变化恢复 fit。 |
| `Services/Images/OrgPreviewImageLoader.swift` | `PreviewImage.kt`、`PreviewImageDecoder.kt` | 两端限制输入约 20 MiB、缩略图最大边约 2048、按候选附件路径尝试缺失文件、显示失败并允许重试。Android TIFF 已由迁移聊天接入自带源码 decoder；本轮未改解码器及网络/存储安全规则。 |
| `Features/Files/Preview/Images/OrgPreviewImageReference.swift` | `core/ImageReference.kt` | 文档相对路径、继承的 DIR/ID、attachment 候选位置已有共享语义，属于领域层映射。本轮保持该路径处理。 |

## 本轮图片修复

- 全屏图片调用 `ModalPresence()`，系统快捷方式会等待图片关闭，避免关闭底层文档时图片被突然移除。
- 打开时保留图片数据和标题快照，内嵌图片的后台重载不会使全屏弹窗消失后重现。
- 图片入口带 Button role、打开/重载的动作标签、48 dp 最小触摸高度；加载 spinner 有描述，全屏图片读出缩放百分比，放大/缩小仍为 Material IconButton。
- 拖动范围根据实际图片的拟合尺寸计算；横向长图不会因容器高度而允许被拖到屏幕外。按钮缩小时同步收紧偏移，双击回中心，窗口尺寸变化恢复 fit。
- 全屏图片裁剪在内容 viewport 内，避免缩放后绘制溢出到工具栏；长标题使用省略号。

## 验证边界

本专项没有运行 Gradle、模拟器或格式化工具，统一验证由根代理执行。需要覆盖：普通图和长宽比极端的图片在缩放后拖动、缩小按钮、双击恢复、窗口旋转；图片打开期间触发系统快捷方式；加载失败后重试与 TalkBack 动作朗读。未变更的 TIFF 格式/方向/16 KB 页验证属于迁移聊天的 0.1.1 证据，不能自动视为本轮完整 UI 验收。
