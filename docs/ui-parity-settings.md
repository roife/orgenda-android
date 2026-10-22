# 设置与配置组件行为对照

对照范围：iOS `Orgenda/Features/Settings/` 的全部 Swift 源文件；Android `SettingsScreen.kt`、`ConfigurationSettings.kt`，并只读检查共享路径选择器、配置 ViewModel 和配置校验模型。基于 2026-10-22 当前工作区源码。此文区分源码确认与设备测试，不声称已完成真实服务器、系统权限或所有辅助功能的人工验收。

| iOS 组件 | Android 对应及本轮处理 |
|---|---|
| `SettingsView` | `SettingsScreen` 保留 Android 导航入口；工作流、Capture、文件与议程、提醒、JSON、外观可直接访问。系统浅/深色选择改 Material 下拉菜单，避免大字号时三个横排 chip 挤压。 |
| `AppearanceSettingsView` | `Choice` 切换 System/Light/Dark，本机偏好不写入共享配置；继续使用 Material 动态色。 |
| `SettingsRow`、`SettingsValueRow` | 原生 `ListItem`、支持换行的文字及独立按钮；新增实际工作区位置和 WebDAV 用户名摘要，可选择复制位置。 |
| `WorkspaceSettingsView` | 同步摘要、错误、上次检查、待同步数量、失败操作重试、恢复导出、切换和断开都保留；同步期间禁用再次同步、切换、连接和断开入口；系统文件夹切换前也先保存配置。 |
| `StorageProviderPicker` | Android 使用系统 SAF 文件夹选择器与 WebDAV 表单。保留 Android 工作区历史和恢复 ZIP 机制；文件夹选择不模拟 iOS provider 页面。 |
| `StorageSyncSummary` | 支持同步中、待同步、已同步、错误和检查时间。界面继续使用 Material 进度组件及文字；服务器认证错误保留原异常信息。 |
| `StorageReconnectAction`、`StorageWebDAVCredentialsView` | 当前 WebDAV 连接的 URL 和用户名预填，重新输入凭据即可重连；密码不预填。 |
| `StorageWebDAVView` | 原生 Material 对话框支持滚动和键盘；URL/密码使用正确键盘类型；连接时禁用所有字段和退出，空 URL/用户名/密码无法提交，用户名和 URL 去掉首尾空白。Android 输入完整 workspace URL，等价于 iOS server + directory。 |
| `StorageConflictsView`、`StorageConflictView`、`StorageVersionPreview` | 本机/存储版本切换、可选择复制的正文、远端删除提示、选择版本前二次确认和恢复副本语义保留。Android 额外提供 Keep both；解析由现有 repository 完成。 |
| `ConfigurationSettingsView` 的状态列表及默认动作 | 从空格分隔状态输入改为 Material `ListItem`：新增、编辑、上移/下移、跨组移动、删除。每组至少保留一个状态。删除/移动涉及默认动作时列出变更并确认，避免静默重设。默认动作菜单显示友好名称和 Org token。经典预设增加替换确认，仍支持撤销。 |
| `ConfigurationKeywordEditor` | 独立 Material 底部表单草稿，显式保存、无效配置错误、取消/下滑时保护修改。已有 token 只读，与 iOS 一致，避免更名破坏现有 Org 文件；可修改显示名称、快捷键、图标、颜色和进出日志规则。新增状态先校验唯一 token。增加实时原生图标预览，默认图标/颜色保留工作流语义。 |
| `ConfigurationLogRulePicker`、日志抽屉字段 | 原有 none/time/note 下拉菜单保留；补抽屉名的实时无效提示（含 END、PROPERTIES 保留名）。 |
| `ConfigurationSettingsView` 的模板列表 | 默认模板显示名称而非 ID；列表显示目标路径及快捷键，更多菜单提供重排及删除；最后一个模板不可删除，删除默认模板会选择剩余首个。新增模板在保存之前不会写入配置。 |
| `ConfigurationTemplateEditor`、`ConfigurationEditorForm`、`ConfigurationTemplateFilePicker` | 独立本地草稿代替逐键直接修改工作区配置。类型、目标、路径、outline、Org source、group、key、prepend、空行全部保留；高级项折叠；模板语法提示与“试用但不保存”；保存与试用均验证配置；取消/外部关闭/下滑均保护未提交修改。工作区路径选择继续使用现有原生浏览器。 |
| `ConfigurationFilesSections`、`ConfigurationPathSelectionRow/View` | 源、排除、Refile 文件由原生多选列表编辑，包含已配置但未加载的路径，支持清空及新增合法相对路径，保留原顺序；目录候选也从文件父路径推导。匹配文件数和列表可展开。Inbox、attachment、journal 保留共享路径组件。 |
| 归档预览 | 补可选示例来源文件、解析后目标路径及 heading、恢复“源文件旁归档”动作，高级规则默认收起，非法规则展开以便修复。预览使用与现有归档动作一致的 `%s` 和源目录解释。 |
| `ConfigurationIntegerField` | 原生数字键盘、范围错误、常用值菜单；无效文本仍立刻进入草稿，不会误保存上一个数字。 |
| `WorkspaceReminderTimingSections`、`ReminderTimingRow/Editor` | 常用值快速选择 + 原生自定义值对话框，自定义值验证后显式保存，取消不改变配置。提醒预览最多前 6 项，再显示省略号和开始时刻，避免长间隔配置刷屏。 |
| `ReminderSettingsSections` | 本机提醒开关、通知许可、系统通知设置、精确闹钟许可、降级说明均保留；权限请求结束立即刷新状态，从系统页返回再刷新。精确闹钟是 Android 平台专有机制。 |
| `ConfigurationImportEditor` | 新增独立粘贴 JSON 表单，验证成功后进入导入预览，失败留在表单；有内容时取消/下滑要求丢弃确认。文件导入、预览确认、替换撤销均保留。 |
| `ConfigurationPromptSection` | 可展开完整提示、复制后显示已复制，配置变化后重置反馈。 |
| JSON、重置与诊断 | JSON 内容默认折叠；保留 Android 已有原始 JSON 编辑能力以修复恢复的无效草稿。通用重置需要确认。保留 ViewModel 自动保存、失败草稿恢复、重试和离开保护；替换撤销绑定当前工作区，防止跨工作区应用旧配置。 |

## 平台保留与边界

- 控件均为 Material `ListItem`、`DropdownMenu`、`Checkbox`、`OutlinedTextField`、`AlertDialog`、`ModalBottomSheet` 等，没有自绘或 Liquid Glass。
- 重排使用可访问的上移/下移菜单替代 SwiftUI 拖拽手柄；系统文件选择与权限流程保持 Android 原生方案。
- 0.1.3 中模板和状态编辑器等待本地事务提交成功才关闭，失败留草稿重试；工作区及配置基线校验避免覆盖其他修改，候选失败不会进入全局自动保存队列。其他设置项继续使用既有自动保存管线；远端同步通过持久队列完成。
- iOS “Keep copies and disconnect / adopt folder versions” 与 Android 恢复 ZIP、独立工作区队列的存储恢复机制由迁移主任务负责，本轮没有为了界面相似改动其安全边界。
- 所有独立对话框/底部表单登记 `ModalPresence`，避免系统快捷入口打断设置草稿。

## 验证

`SettingsBehaviorTest` 共八个设备测试，覆盖：模板内容编辑不提前发布、取消时丢弃保护、删除默认状态需确认默认动作更新、JSON 不通过验证不能进入导入、`StateRestorationTester` 恢复父级模板编辑入口和未提交草稿，以及模板保存失败/重试、状态保存失败/重试、保存中旋转恢复原工作区和配置基线。失败测试同时检查保存中字段与取消按钮被禁用、失败后草稿仍保留、只有重试成功才能关闭。

工作流状态、模板、粘贴 JSON、自定义提醒值均使用 `rememberSaveable`，模板/样式以小型 JSON Saver 保存；不保存预览渲染缓存。保存中旋转会恢复未完成请求，由 ViewModel 的幂等提交处理已完成的本地写入。测试按真实保存完成、失败显示和请求开始条件等待，保留严格结果断言；条件等待上限为 5 秒，不依靠固定延时或仅将 UI 空闲视为事务完成。编辑后的保存、重试和取消先关闭测试输入法，再滚动并确认按钮可见、启用后实际点击，避免 IME 自动滚动让点击落在可见区域之外；最终八项回归全部通过。

构建与设备测试由主代理统一运行；本子任务未自行运行 Gradle，也未运行格式化工具、创建分支或修改 iOS 源码。
