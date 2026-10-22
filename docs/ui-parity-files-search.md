# Files / Search 行为对照

对照当前 iOS `Orgenda/Features/Files/{FilesView,WorkspaceBrowser,WorkspaceFileActions,WorkspaceDocumentContextPreview}.swift` 与 `Orgenda/Features/Search/` 全部 9 个文件；修改 Android `FilesScreen.kt`、`SearchScreen.kt`。本记录区分源码对照与自动验证，不将未运行的设备测试记作通过。

## Files

| 组件 / 行为 | 对照结果与处理 |
|---|---|
| 手机导航、宽屏双栏 | 保留 Android Root 的 NavigationBar / NavigationRail 和 840 dp 双栏；文件夹有 toolbar 返回与系统返回。搜索文件夹结果请求 Root 打开 Files 对应路径。 |
| 根目录分区 | Pinned、Recently opened、All files 与 iOS 对应；补齐没有置顶/最近文件时的引导文字，不再直接隐藏分区。最近打开保留 10 条。 |
| 置顶排序 | 最近置顶的文件排在前面，顺序按工作区保存在本机；文件是否置顶仍由 repository 持久化。 |
| 文件行 | 补齐置顶图标、根目录文件的标题数量、文件夹类型、Pinned/Recent 与嵌套文件的路径；标题允许换行，冲突和待同步状态保留。 |
| 隐式目录 | 修复只筛选直接 parent 导致文件不可达的问题；与 iOS `visibleFolderDocuments` 一样，显式文件夹收纳自己的后代，没有显式父目录的嵌套文档直接出现在最近的可达目录。 |
| 外部变化 / 切换工作区 | 切换工作区重置弹层状态；当前文件夹被移走/删除时向存在的祖先回退。 |
| 刷新 / 同步状态 | 新增 Material `PullToRefreshBox`；工具栏显示同步中、失败、待同步或已同步状态；Root 同步按钮仍可使用。 |
| 长按 / 更多操作 | 原先长按只能读摘要；现在打开 Material 原生菜单，包含 Open、Preview、Pin、Move、Delete。仍可用更多按钮，TalkBack 增加 Pin / Move / Delete 自定义动作。 |
| 行滑动 | 使用 Material `SwipeToDismissBox`：起始侧置顶/取消置顶，末尾侧打开删除确认。行不会直接消失；删除仍进入可恢复回收站。Move 保留菜单入口。 |
| 文件拖拽 | 保留文件图标启动应用内拖动、文件夹/当前目录接收放下；不覆盖现有数据边界。 |
| 上下文预览 | 原生 AlertDialog 显示路径、文件正文摘要或文件夹内容，可滚动并打开原文；iOS 同样使用原始文本摘要，不需要复制富文本文档编辑器。 |
| 新建 / 导入 / 改名 | 保留 Android 既有原生入口；系统文档选择器导入、路径对话框新建、Move sheet 改名和选目录。 |
| 旋转中的输入 | 新建入口可恢复；移动入口保存工作区、文件 ID、路径、版本与内容引用，只恢复仍未变化的文件；搜索打开的事项通过共享 `rememberHeadingSelection` 恢复，避免旋转关闭编辑草稿。 |
| 回收站 | 补齐空态、标题与原路径、恢复时进度/错误与防重复提交；恢复期间不能关弹层。 |
| Undo / 失败反馈 | 保留 Root Material Snackbar 的 Undo 与后台错误反馈，具体存储安全由已有 repository 和迁移任务负责。 |

## Search

| 组件 / 行为 | 对照结果与处理 |
|---|---|
| 搜索入口 / 分类 | 保留原生 Material OutlinedTextField、FilterChip，覆盖 All / Tasks / Journal / Calendar / Files / Settings；快捷 Search 请求清空并聚焦，仅消费一次，普通返回不丢查询。键盘 Search 动作记录查询并收起键盘。 |
| 请求生命周期 | 使用去除首尾空白后的 query、scope、工作区 revision 做请求；300 ms debounce；旧请求取消。新结果到达前保留上一批非空结果，显示 loading，避免输入过程中误报 No results。 |
| 结果数量 / 空态 | 增加已完成搜索的范围与数量；零结果可 Search all / Clear search；没有建议时显示搜索引导。加载和失败不再作为零结果。 |
| 任务结果 | 标题/正文/标签匹配；Tasks 按 task/project/habit 筛选；Calendar 限 agenda 来源、开放且带日期条目，并按日期排序。打开 ItemEditor。 |
| 标签建议 | 修复 Tasks 建议混入 event/note 的问题；按类别筛选后统计，频率优先，最多 8 条，采用 Material SuggestionChip。 |
| 推荐内容 | Recent journal entries 取 3 条；Browse files 取 4 条；补齐分区说明。保留 Android 既有本机搜索历史，这是额外便利功能。 |
| 文件结果 | 修复文件夹不参与搜索；匹配文件夹后通过 Root 打开对应 Files 路径；文档按匹配 byte 跳转并传递 query 高亮。排除配置及隐藏内部文件。 |
| 结果标签 | 增加种类图标、工作流图标/颜色、状态文本、来源路径、日记时间；标题不再被 3 行限制。匹配区仍使用 Material 色彩高亮。 |
| 设置结果 | 搜索标题及副标题，补齐工作区/同步、主题、提醒、状态/日志、模板/目的地、议程路径和配置导入/预设；保留 Emacs 提取提示词的多语言检索词。打开准确设置分区。 |
| 日记详情 | 保留 Material ModalBottomSheet、完整可选中文字、日期/标题/正文，以及 Show in file。 |
| 共享弹层排队 | Files 的 AlertDialog 和 JournalDetailSheet 接入 ModalPresence，避免快捷入口覆盖现有弹层。 |

## 有意保留的平台差异

- Android 使用 Material 原生菜单、对话框、底部弹层、滑动容器、下拉刷新、导航条与轨道；不复制 Liquid Glass、iOS context-menu preview 动画或 UIKit 手势容器。
- iOS 的左右 swipe action tray 在 Android 映射为原生 swipe 置顶/删除确认，加显式更多菜单中的 Move；所有操作均有不依赖手势的入口。
- 文件预览为摘要；上下文预览不嵌入会改变阅读位置或允许编辑的完整 OrgPreview。
- 回收站恢复和文件操作的并发/版本边界依赖既有 repository；本轮不改数据库或存储迁移。

## 验证

0.1.3 补充：各目录独立恢复滚动、移动成功后关闭/失败重试，以及回收站按真实删除根项分组。新增四项回归，`FilesSearchParityTest` 共八项，详见 [功能对齐补充](FUNCTION_PARITY-0.1.3.md)。

新增 `FilesSearchParityTest` 四项：隐式目录与显式子树可达性、Tasks/Calendar 标签建议分类、空结果扩大范围和清空动作、文件夹搜索结果点击动作。没有运行格式化工具，没有创建分支。构建及设备测试由主代理统一执行，最终结果以主代理验收记录为准。
