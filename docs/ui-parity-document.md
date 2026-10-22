# 文档、预览与大纲 UI 行为核对

核对来源为当前 `~/.config/orgenda/Orgenda/Features/Files/{Document,Preview}` 和 `Components/Editor` 源码；Android 对应 `DocumentScreen`、`OrgPreview`、`NativeSourceEditor`、`OutlineSheet`、`TimestampSheet`、`TimestampPresentation`。本记录区分源码已存在的能力和本轮修改；编译、设备测试结果由主任务统一记录，不把源码对照当作设备验收。

| 组件 | iOS 行为 | Android 核对与处理 |
|---|---|---|
| 文档模式 | Preview/Edit 切换，保留阅读位置与撤销；大纲可跳到两种模式 | 原有模式切换和独立文档历史保留。本轮切换先记录 viewport 顶部位置并收键盘；原先只记录光标位置，滚动后切换会回到旧光标。 |
| 阅读位置 | 原文 byte 位置，恢复长段落内部位置，搜索结果上方留上下文 | 保留 byte 映射。本轮防止预览恢复过程中用临时滚动位置覆盖目标；恢复前展开目标祖先；搜索定位上方保留约三分之一视口。 |
| 重复导航 | 滚动离开后再次选择同一标题仍跳回 | 接入 `EditorState.navigationRevision`，预览 effect 与源码编辑器同时响应重复 byte 位置请求。 |
| 搜索高亮 | Preview 短暂高亮；Edit 选中匹配词；匹配大小写和重音折叠 | 本轮预览高亮改为两秒，补齐列表、代码块、标题/标签/元数据高亮；源码进入编辑时选中完整 Unicode 匹配，包含组合附加符号。 |
| 保存失败 | 保留草稿，显示原因并可重试 | 原有错误保留，本轮在错误行补 Retry 操作，使用现有保存流程。 |
| 源码键盘工具栏 | Heading、Checkbox、缩进、日期、撤销/重做、隐藏键盘 | 已有原生 EditText、IME/UTF-16 处理、列表续行、Tab 和 Ctrl+Z/Y。本轮撤销按钮正确反映历史和 composing 状态；日期仍用 Material DatePicker。 |
| 源码着色 | 随主题更新，保留原生文本输入 | 原有 native span 着色；本轮代码着色使用 Material tertiary 角色，配色变化重新着色。 |
| 大纲入口 | 结束输入并刷新解析后打开 | 本轮先等待 `saveNow` 的保存和索引刷新，再显示大纲；避免新编辑还未解析就跳转旧偏移。 |
| 大纲树/过滤 | 左侧 disclosure、单独标题跳转；状态及日期交集，保留命中祖先 | 原有展开/过滤/祖先逻辑已对齐。本轮校验展示源与当前源一致再跳转；过滤器隐藏无意义的 Include time；重置和空态保留原生控件。 |
| 标题移动 | 移动子树、兄弟前后、缩进/反缩进；移动后保持折叠关系 | 原有领域层子树移动和预览折叠重映射；Android 保留拖动后 Material 位置选择及菜单 Up/Down/Indent/Outdent，满足无障碍操作。 |
| 预览标题 | 左侧 disclosure、工作流操作、标题编辑、优先级、标签、Habit | 原有任务/状态操作。本轮为无子节点标题保留 disclosure 槽，使同级标题对齐；展开按钮描述包括标题。 |
| Checkbox 列表 | 原位修改原文，完成文本删除线，保留列表缩进 | 原有精确源范围写回；本轮补完成删除线、嵌套缩进与搜索高亮。仍使用 Material Checkbox。 |
| 段落、独立时间戳 | 连续文本合段；独立日期显示可编辑日期控件 | 原有段落 grouping 和 inline timestamp 链接；本轮独立时间戳使用同一 Material 日期控制，可长按查看 raw。 |
| Planning 编辑 | 类型、起止日期、Include time、重复规则、取消未保存确认 | 原有精确原文保留、冲突检查与取消确认。本轮类型选项使用本地化标签。 |
| 可读日期 | 可见同日时间范围、重复模式/最大间隔、提醒及未知扩展 | 本轮修复此前丢失的范围终点、habit 最大间隔及未知注释；采用系统 locale 与 12/24 小时制，原始 Org 文本不变。 |
| Drawers/Comments/Clock | 折叠，搜索命中自动展开；历史日期只读；clock 可读 | 原有只读 drawer/clock。本轮使用原生 disclosure 图标、搜索高亮和 clock 文本选择；状态历史仍显示原文内容，不复制 iOS 的状态图标排列。 |
| Source/Example/Export/Fixed width | 横向滚动、选择、语言着色 | 原有 TextView + Prism4j；本轮代码内匹配词也可见，拷贝内容仍为原文。 |
| Tables | 横向滚动、保留空单元格、单元格富文本 | 原有表格宽度与空列处理；Material Surface/Divider 替代 SwiftUI Grid 外观。 |
| Quote/Verse/Center/Dynamic | 引用/诗行/居中；生成内容中的日期只读 | 本轮 center 内容居中、dynamic 日期保持只读；其余已有原生富文本内容展示。 |
| 富文本/链接/数学 | Org 标记展示、外部与内部链接、inline/display math | 已有 native spans/系统 Intent/Org 跳转、RaTeXView/RaTeXSpan。Android 的选择菜单及浏览器交给系统。 |
| 图片 | 路径解析、加载失败重试、全屏查看及缩放 | 核对既有 `PreviewImage`/decoder 与 iOS Images；此次没有改动解码管线，图片专项由主任务统一检查。 |
| 模态排队 | 模态界面期间延后快捷操作 | 大纲、过滤、大纲移动、日期插入、TimestampSheet 已接主任务的 `ModalPresence`。 |

保留的平台差异：Material TopAppBar、FilterChip、BottomSheet、DatePicker、原生 EditText/TextView、Checkbox 与系统返回/文本选择；不移植 Liquid Glass、SwiftUI 自定义对齐线或 iOS 拖动指示线。拖动位置通过原生菜单表达。代码/数学/图像由现有原生库渲染；不另画系统组件。

新增设备回归测试：

- `DocumentInteractionParityTest`：Unicode 搜索选择不改源文或历史；滚动位置与离屏光标分离；Undo/Redo 可用状态及 IME composing 保护；日期展示保留时间范围、重复上限和未知注释。
- `DocumentNavigationParityTest`：真正挂载 `DocumentScreen`，覆盖相同 `jumpByte` 和新的 `navigationRevision` 再次导航、源码与预览滚动恢复、文件末尾完整可见且没有越界留白。

仍需人工体验验证的边界：真实中文输入法候选窗、TalkBack 全流程、所有超大文档/所有语法组合。上述测试不替代这些验证。

## 旋转与实例状态恢复补充

- Document 的 Insert date / Outline 入口保存实例状态；源码记录已处理的导航 revision，重建后使用当前 `readingByte`，不会再次套用最初的 `jumpByte`。预览保留已恢复的 LazyListState，只在新的导航请求时重定位。
- 预览主界面和 inline 富文本的日期编辑入口保存节点 type/start/end，恢复后仅解析当前语法树中的同一节点。未打开日期编辑器时不扫描整棵语法树。
- TimestampSheet 保存原始文本、每个日期/时间/重复规则字段及 note，包含暂时无效的输入；无效规则不再调用会抛错的 source 预览。脏草稿阻止 BottomSheet 进入 Hidden，选择继续编辑后仍保留可见表单。
- 新增 StateRestorationTester 回归覆盖源码滚动位置、预览滚动位置，以及从预览打开日期编辑后保留重复规则/备注并在下滑取消时继续编辑。测试仍由主任务统一运行。

设备回归额外发现 TextView 在 pre-draw 自动把光标滚入可见范围，并增加行高余量；普通 post 的阅读位置恢复会被其覆盖一行。恢复现延后到本次 pre-draw 回调完成之后，保持原有精确 byte 断言，并取消过期导航请求。同一光标位置也显式请求绘制以消费跳转，滚动上限限制在内容末尾。源码着色仅使用 ForegroundColorSpan，不修改行高或字形尺寸。0.1.2 的文档/原生输入定向回归 10 项通过，详见 [历史验收记录](ACCEPTANCE-0.1.2.md)。

## 第二轮：从 iOS 源码复查实际编辑功能

本轮不重复上一轮已验收的导航和 Timestamp 恢复；以下是重新沿操作调用链发现的缺口。行号以本轮阅读的源码为准，iOS 文件均位于 `~/.config/orgenda/Orgenda/`。

| 触发场景 | iOS 具体实现 | Android 差异与本轮处理 |
|---|---|---|
| 连续输入 `hello`，点击一次 Undo；移动光标或点工具栏命令后再撤销 | `Components/Editor/OrgSyntaxTextEditor.swift:174` 检查选择变化；`:246` 结束输入组；`:256–275` 将连续输入归组并在 700ms 停顿后结束 | 原 NativeSourceEditor 每个 afterTextChanged 都保存独立快照，输入 5 字符需撤销 5 次。本轮 `NativeSourceEditor.kt:74` 按前次文本、光标连续性和 700ms 时间界限归组；导航、工具栏命令、日期和暂停编辑切断输入组。IME composition 完成后立即登记历史，仍禁止在 composing 中改写。 |
| 选择含 emoji 的一段文字，打开日期弹窗，旋转，再点击 Insert；随后 Undo | `Components/Editor/OrgEditorSession.swift:167–179` 在打开日期选择器时保存 dateSelection，并用该范围生成一次可撤销编辑 | 原 DocumentScreen 只保存 insertDate 开关，Native 重建后光标是 readingByte，可能插错位置。本轮 `DocumentScreen.kt:27–30,107–124` 保存 UTF-16 起止选区和源文 hash；确认时按原范围替换、光标落在日期之后，Undo 恢复原内容和原选区。源文已被外部替换时禁用确认，避免向旧范围写入。 |
| 展开一棵子树，把它移到折叠的另一父标题下；旋转；手动折叠后再次旋转 | `Features/Files/Document/DocumentOutline.swift:182–190` remap 已展开节点，`:257–260` 展开目标祖先 | 原 OutlineSheet 使用带祖先路径的 key，跨父节点移动后 key 失效；旋转还清空 expanded/filter。本轮 `OutlineSheet.kt:21–47` 保存筛选和展开状态，使用 ViewModel 提供的旧 key→新 key 映射，匹配 moved.text 后只消费一次并展开新祖先。映射由主代理补到 EditorState，避免 repository 先发布新 document 时丢失旧路径。 |

本轮新增设备回归（由主任务统一运行，不在此提前宣称通过）：

- `DocumentInteractionParityTest` 新增连续中英文/emoji 输入的一次 Undo/Redo、移动选择与工具栏命令分组、结束编辑后 IME composition 立即撤销三项。
- `DocumentDateInsertionTest` 使用真正 DocumentScreen + StateRestorationTester，覆盖旋转后替换原 Unicode 选区、一次撤销恢复原选区，以及源文变化后拒绝旧选区插入。
- `OutlineContinuityTest` 覆盖先发布新 document 再发布移动映射的时序、移动子树展开路径恢复、旧移动不重复消费，以及旋转保留筛选与此前展开状态。
- 追加 `DocumentInteractionParityTest` 两项历史保护回归：外部 source 替换后 Undo/Redo 不得覆盖新内容；同 source 重新创建 Native 编辑器保留 Undo/Redo。
- 追加光标移走又移回原位置后继续输入的分组回归，防止两段输入错误合并。第二轮文档相关共增加 10 项设备测试。

主代理随后授权补齐外部版本的撤销保护：EditorHistory 绑定当前实际 source，每次本地输入/命令/撤销重做更新；`display` 接受不同的 backing source 时清空旧 Undo/Redo 并刷新按钮，同 source 重建保留历史。DocumentScreen 的外部更新仍仅在非 dirty/saving 时进入 display，Native 额外拒绝在 composing 时替换不同 source。此行为避免旧快照覆盖同步新版本。

仍需后续处理的明确差异：

- **本会话预览编辑纳入 Undo**：iOS `OrgSyntaxTextEditor.swift:323–335` 把本会话预览改源登记为可撤销编辑。Android 本轮已保守重置不同 source 的历史，场景“预览勾选 checkbox/修改状态→回源码 Undo”仍不能直接撤销预览操作。后续需要领域层明确区分修改来源，才能将本地预览操作纳入历史并仅在外部 revision 清空；本轮不在 UI 猜测来源。
- **跳转后的键盘恢复**：iOS `OrgEditorSession.swift:120–130` 选择大纲标题会成为 first responder；Android 当前完成导航和光标定位，但显式收起键盘后不会自动再展开。保留原生 Android 焦点处理，尚未增加对应编辑意图 API。
- **可配置 workflow 标签在大纲中的呈现**：iOS `DocumentOutline.swift:322–338` 使用 OrgWorkflowLabel；Android filter chips 和行次要文本仍为原始 token。过滤值正确，但自定义显示名没有在此两处呈现；未将其混入本轮输入状态修复。

已再次核对且未发现新功能缺口：Heading/Checkbox/Indent/Outdent 的多行选择边界、已有 checkbox 的幂等插入、Tab/Ctrl+Z/Y、列表续行与退出空列表、日期筛选交集及保留祖先、折叠时标题单独跳转。Android 保留 Material 菜单和原生输入组件，不复制 iOS 工具栏玻璃或拖动指示线。
