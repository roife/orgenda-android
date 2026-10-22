#!/usr/bin/env python3
"""Verify the pinned iOS file set and regenerate its Android responsibility map."""
import hashlib
import json
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
source = Path('/Users/roifewu/.config/orgenda')
record = root / 'docs/parity/ios-file-inventory.json'
inventory = json.loads(record.read_text())
app = 'app/src/main/java/com/roifewu/orgenda/'
core = 'core/src/main/kotlin/com/roifewu/orgenda/core/'
ui = app + 'ui/'
data = app + 'data/'
platform = app + 'platform/'
core_tests = 'core/src/test/kotlin/com/roifewu/orgenda/core/'
device_tests = 'app/src/androidTest/java/com/roifewu/orgenda/'

specific = {}
def assign(names, paths, check):
    for name in names.split(): specific[name] = (paths, check)
assign('OrgendaApp OrgendaRootView OrgendaQuickActions', [app+'MainActivity.kt', app+'OrgendaApplication.kt', ui+'OrgendaApp.kt'], '启动、快捷入口、返回、导航与弹窗排队；Android 生命周期和系统快捷方式')
assign('OrgCalendarDates Date+Orgenda OrgRepeater OrgPlanningDrafts', [core+'OrgDates.kt', core+'CalendarPosition.kt', core+'PlanningDraft.kt'], '日期、重复、时区、区间端点；SourceSafetyTest / ParityRegressionTest / InteractionParityTest')
assign('OrgAgendaPerspective OrgendaDatePresentation', [core+'Agenda.kt', ui+'TimestampPresentation.kt'], '主日期优先级、重复投影、跨日事件、逾期与本地化日期')
assign('JournalEntry JournalFileIndex JournalComposer JournalDayContent', [core+'JournalIndex.kt', ui+'CaptureSheet.kt', ui+'CalendarScreen.kt'], '年文件、日/时间标题、文件名日期、完整日记正文和排序；ParityRegressionTest')
assign('ConfigurationValidation WorkspaceConfiguration OrgWorkflow OrgConfiguredHeading', [core+'Configuration.kt', core+'OrgEngine.kt'], 'v1 默认/空数组/数值校验、经典工作流、文件内 TODO 优先级；三组 core 测试')
assign('ConfiguredCapture', [core+'CaptureEngine.kt', ui+'CaptureSheet.kt'], '模板宏、提示、日期、剪贴板显式输入、目标创建、配置和正文版本校验')
assign('OrgHeadingMove OrgHeadingContinuity', [core+'Outline.kt', app+'OrgendaViewModel.kt'], '子树移动、层级、范围补丁、折叠和阅读位置迁移；InteractionParityTest')
assign('OrgInputCommands', [core+'OrgInputCommands.kt', ui+'NativeSourceEditor.kt'], '列表续写、标题/复选框/缩进、组合输入和单步撤销；NativeInputSafetyTest')
assign('OrgHeadingBody OrgSourceMutation OrgWorkflowOperations OrgItem ParsedOrgDocument', [core+'OrgEngine.kt', core+'Source.kt'], '未知语法保留、正文边界、状态日志、CLOSED 与重复完成；40 项 core 回归')
assign('SourceLocation OrgTreeSitter', [core+'Source.kt', 'native/orgenda.c'], 'UTF-8/UTF-16、不可变快照和 Tree-sitter 0.26.3 增量解析；SourceSafetyTest')
assign('OrgTaskTransfer OrgHeadingDrag WorkspaceFileTransfer', [ui+'DragAndDrop.kt', core+'Outline.kt', data+'WorkspaceRepository.kt'], '应用内类型化拖动、规范任务来源、层级/文件目标校验、禁止覆盖')
assign('SettingsConfigurationSupport', [core+'ConfigurationPrompt.kt', ui+'SettingsScreen.kt'], '完整配置转换提示、导入预览、自动保存与无效草稿恢复')
assign('SettingsDestination SearchModels', [ui+'SearchScreen.kt', ui+'SettingsScreen.kt'], '六种搜索范围、设置目的地和跳转')
assign('DocumentSaveStatus WorkspaceDocument StorageConnection', [data+'WorkspaceDatabase.kt', data+'WorkspaceRepository.kt', app+'OrgendaViewModel.kt'], '本机提交/待同步/冲突状态、工作区和不可变正文版本')
assign('OrgPreviewImageReference OrgPreviewImageLoader OrgPreviewImageView', [core+'ImageReference.kt', ui+'PreviewImage.kt', ui+'PreviewImageDecoder.kt', 'native/tiff.c', data+'WorkspaceRepository.kt'], '图片路径/继承、链接原位布局、缓存、重试、缩放；Coil/Android 加 libtiff 原生解码、方向/压缩/尺寸回归')
assign('OrgCodeHighlighting OrgPreviewSourceBlock', [ui+'CodeHighlight.kt', ui+'CodeGrammars.java', ui+'OrgPreview.kt'], 'Prism4j 原生分词、缓存、TextView spans、换行和复制；ConfigurationInputTest；语言集合不同于 iOS Highlighter')
assign('OrgMathRendering OrgMathExpression OrgPreviewMathBlock OrgPreviewMathFlow', [ui+'OrgPreview.kt', 'app/build.gradle.kts'], 'RaTeX 0.1.14 原生 View/Span；行内/块公式、暗色和 16 KB 检查')
assign('OrgPreviewClockValue OrgPreviewDateText OrgPreviewPlanning OrgPlanningEditorSheet', [ui+'TimestampPresentation.kt', ui+'TimestampSheet.kt', core+'PlanningDraft.kt'], '本地化日期、原文切换、范围编辑、CLOCK、历史抽屉只读')
assign('OrgReminderScheduler WorkspaceStore+Reminders', [platform+'ReminderScheduler.kt', ui+'SettingsScreen.kt'], 'AlarmManager、通知/精确权限降级、60 项计划上限、重启/时区重建；厂商投递需真机验收')
assign('StorageKeychain', [data+'Credentials.kt'], 'Android Keystore AES-GCM、凭据重连与删除')
assign('StorageHTTP WebDAVBackend WebDAVConnectionFactory', [data+'WebDavBackend.kt', data+'WorkspaceRepository.kt'], 'HTTPS、ETag、Overwrite:F、坚果云、首次创建/重连不创建；7 项 MockWebServer 测试')
assign('RemoteWorkspaceBackend', [core+'StorageBackend.kt', data+'SafBackend.kt', data+'PrivateFolderBackend.kt', data+'WebDavBackend.kt'], '统一存储能力、完整枚举、读写核验和异常分类')
assign('WorkspaceStateDatabase WorkspaceState', [data+'WorkspaceDatabase.kt', data+'WorkspaceRepository.kt'], 'Room WAL/FULL、持久队列、事务和重启；StorageSafetyTest')
assign('WorkspaceTextMerge', [core+'TextMerge.kt', data+'WorkspaceRepository.kt'], '三方合并、重叠冲突、版本选择和恢复副本；SourceSafetyTest / StorageSafetyTest')
assign('WorkspaceBlobCache', [data+'WorkspaceDatabase.kt', core+'OrgEngine.kt'], '不可变 blob 与解析缓存；Android 保守保留旧 blob，不复刻 NSCache 淘汰策略')
assign('WorkspaceFolderMonitor SyncEngine', [app+'MainActivity.kt', platform+'SyncWorker.kt', data+'WorkspaceRepository.kt'], '前台检查、WorkManager 后台重试和服务端退避；SAF 无统一文件监控 API，采用核验扫描')
assign('OrgIndexService OrgDocumentSyntaxCache OrgSyntaxHighlighter', [core+'OrgEngine.kt', data+'WorkspaceRepository.kt', ui+'NativeSourceEditor.kt'], '缓存语法、任务/日记索引、原生编辑器语法着色、拒绝过期结果')
assign('WorkspaceFileStore', [data+'SafBackend.kt', data+'PrivateFolderBackend.kt'], 'SAF 持久权限、能力差异、加载中枚举、恢复副本及非原子写入边界')

rows = []
for file in inventory['files']:
    name = file['source']; path = source / name
    content = path.read_bytes()
    assert hashlib.sha256(content).hexdigest() == file['sha256'], 'iOS changed: ' + name
    stem = path.stem
    status = '职责核对 / Android 实现'
    direct = None
    if name.startswith('Packages/OrgTreeSitter/src/'):
        direct = root / ('vendor/tree-sitter-org/' + name.split('/src/', 1)[1])
    elif name in ['Packages/OrgTreeSitter/grammar.js', 'Packages/OrgTreeSitter/LICENSE']:
        direct = root / ('vendor/tree-sitter-org/' + path.name)
    elif name == 'Orgenda/Resources/Welcome.org': direct = root / 'app/src/main/assets/Welcome.org'
    if direct is not None:
        assert direct.read_bytes() == content, name
        paths, check = [str(direct.relative_to(root))], '逐字节相同；保留来源和许可证'
        status = '共享源码 / 字节一致'
    elif stem in specific: paths, check = specific[stem]
    elif name.startswith('OrgendaTests/') or '/Tests/' in name:
        if '/Storage/' in name or '/Workspace/' in name:
            paths = [device_tests+'StorageSafetyTest.kt', 'app/src/test/java/com/roifewu/orgenda/WebDavSafetyTest.kt']
            check = '按行为移植故障回归；并非 Swift 测试逐条等量转换，真实提供方/服务端见验收边界'
        elif '/Editor/' in name:
            paths, check = [device_tests+'NativeInputSafetyTest.kt'], 'InputConnection 中文组合输入、选择、撤销、续写'
        else:
            paths = [core_tests+'SourceSafetyTest.kt', core_tests+'ParityRegressionTest.kt', core_tests+'InteractionParityTest.kt']
            check = '跨端源码/配置/重复/移动样例回归；保留 Swift 测试为参考'
        status = '回归职责映射'
    elif '/Calendar/' in name:
        paths, check = [ui+'CalendarScreen.kt', core+'CalendarPosition.kt', device_tests+'ui/CalendarInteractionTest.kt'], '周/月/年垂直分页、拖动缩放、31 日偏好、年浏览不改选日、大字号及平板、日期/议程双向联动'
    elif '/Editor/' in name:
        paths, check = [ui+'NativeSourceEditor.kt', ui+'DocumentScreen.kt', app+'OrgendaViewModel.kt'], '原生 EditText、IME、撤销、源码工具栏、阅读位置及异步保存边界'
    elif '/Features/Agenda/' in name:
        paths, check = [ui+'AgendaScreen.kt', ui+'CalendarScreen.kt', ui+'RescheduleSheet.kt', core+'Agenda.kt'], '分组/透视、置顶日期、连续议程、改期与撤销、当天捕获；加载使用 Material 进度反馈'
    elif '/Features/Files/Preview/' in name:
        paths, check = [ui+'OrgPreview.kt', ui+'OutlineSheet.kt', ui+'TimestampSheet.kt', core+'Outline.kt'], '富文本、表格、列表、抽屉、交互编辑、折叠过滤、搜索高亮和标题拖动；Android 原生布局'
    elif '/Features/Files/Document/' in name:
        paths, check = [ui+'DocumentScreen.kt', ui+'NativeSourceEditor.kt', ui+'OutlineSheet.kt', app+'OrgendaViewModel.kt'], '预览/源码、导出、行内操作、大纲与阅读/光标位置'
    elif '/Features/Files/' in name:
        paths, check = [ui+'FilesScreen.kt', ui+'WorkspacePathPicker.kt', data+'WorkspaceRepository.kt'], '文件夹导航、固定/最近、长按预览、移动/删除/恢复、拖动和撤销'
    elif '/Features/ItemEditor/' in name or stem in ['OrgRecurrenceEditor']:
        paths, check = [ui+'ItemEditorSheet.kt', ui+'CaptureSheet.kt', ui+'RefileSheet.kt', core+'OrgEngine.kt'], '完整任务草稿、日期/时间/重复、备注、源预览、归档、配置限定转存及丢弃确认'
    elif '/Features/Search/' in name:
        paths, check = [ui+'SearchScreen.kt', core+'SearchText.kt'], '范围、去重音匹配、高亮、上下文、建议、历史和日记详情'
    elif '/Features/Settings/' in name:
        paths, check = [ui+'SettingsScreen.kt', ui+'ConfigurationSettings.kt', ui+'WorkspacePathPicker.kt', core+'Configuration.kt'], 'Material 设置与导入/重置撤销、无效数字和多值输入、连接/冲突、主题/语言/权限'
    elif '/DesignSystem/' in name or name.startswith('Orgenda/Components/'):
        paths, check = [ui+'Common.kt', ui+'AgendaScreen.kt', ui+'OrgendaApp.kt'], 'Material 字体/色彩/组件、工作流语义、触觉、可访问性和 Habit 记录；不移植 Apple 视觉 API'
    elif '/Services/Workspace/' in name or '/Services/Storage/LocalStore' in name:
        paths, check = [data+'WorkspaceRepository.kt', app+'OrgendaViewModel.kt', device_tests+'StorageSafetyTest.kt'], '工作区快照、离线提交、任务操作、文件移动/撤销、目标先确认、冲突/恢复和重连'
    elif name.endswith('.xcstrings') or '/Resources/' in name:
        paths, check = ['app/src/main/res/values/strings.xml', 'app/src/main/res/values-zh-rCN/strings.xml', 'app/src/main/res/values-zh-rTW/strings.xml'], '英文/简体/繁体资源键集合校验；Android 系统组件采用系统语言'
        status = '平台资源替代'
    elif '/Assets.xcassets/' in name:
        paths, check = ['app/src/main/res/drawable/ic_orgenda.xml', ui+'Common.kt'], 'Android 图标资源和 Material 动态色；不搬用 Asset Catalog'
        status = '平台资源替代'
    elif name in ['Orgenda/Info.plist', 'project.yml', '.gitignore'] or name.startswith('Packages/OrgTreeSitter/'):
        paths, check = ['app/build.gradle.kts', 'app/src/main/AndroidManifest.xml', 'native/CMakeLists.txt', '.gitignore'], 'Gradle/Manifest/CMake 对应 Xcode/SwiftPM/npm 构建及权限；固定 vendored grammar，未重新生成'
        status = '平台构建替代'
    elif name.startswith('docs/') or name == 'README.md':
        paths, check = ['README.md', 'docs/ANDROID_ARCHITECTURE.md', 'docs/ACCEPTANCE.md', 'compatibility/README.md'], 'Android 方案、配置和存储约束；历史 iOS 基准保留作为参考，不冒充 Android 实测'
        status = '文档对照'
    else: raise RuntimeError('No responsibility mapping: ' + name)
    for target in paths: assert (root / target).exists(), target
    file.update(review=status, android=paths, verification=check)
    rows.append('| `' + name + '` | ' + '<br>'.join('[' + Path(p).name + '](' + '../../'+p + ')' for p in paths) + ' | ' + status + '：' + check + ' |')
tracked = subprocess.check_output(['git', '-C', str(source), 'ls-files', '-z']).decode().split('\0')
assert set(filter(None, tracked)) == {f['source'] for f in inventory['files']}
record.write_text(json.dumps(inventory, ensure_ascii=False, indent=2) + '\n')
(root/'docs/parity/FILE_COMPARISON.md').write_text('''# iOS → Android 逐文件职责对照

基线：`9b70a360ed38bac6eb36819aedd891be5126fab7`，223 个 tracked 文件，并包含 `current-ios-working-tree.patch` 中的大纲交互未提交修改。逐文件哈希、声明清单和 Android 对应路径保存在同目录 JSON。脚本重新验证源码未变及每个 Android 目标存在；“职责核对”不表示每个 UI 动作都已有自动测试，平台差异及人工验收仍见 [验收记录](../ACCEPTANCE.md)。

原 0.1.0 的 `docs/ios-baseline.json` 另存了 `ee36dde…` 加未提交修改的 231 文件工作区快照，未覆盖或伪装成当前基线。本轮修复依据当前源文件；共享 C grammar/scanner/parser 和欢迎文档逐字节一致。

| iOS 文件 | Android 对应 | 核对与验证边界 |
|---|---|---|
''' + '\n'.join(rows) + '\n')
print(f'Verified and mapped {len(rows)} iOS files; source hashes match the recorded current working tree.')
