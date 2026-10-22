# Android 0.1.1 对齐与验收

日期：2026-10-22。当前安装包为 `artifacts/orgenda-0.1.1.apk`（versionCode 2）。这份记录区分已实现、已自动验证、已查看界面以及仍需真实环境验证，不以编译通过代替跨端全量验收。历史记录保留在 [0.1.0](ACCEPTANCE-0.1.0.md)。

## 逐文件核对

[FILE_COMPARISON.md](parity/FILE_COMPARISON.md) 对照当前 iOS 的 223 个 tracked 文件，每一项列出 Android 对应文件和验证边界。基线是 `9b70a360ed38bac6eb36819aedd891be5126fab7` 加 `parity/current-ios-working-tree.patch` 的大纲交互修改；JSON 保留初始和更新后哈希。未运行格式化工具，未新建分支，Android 修改位于当前 main。没有改动 iOS 工作区。

0.1.0 的 `ee36dde…` 加未提交修改、231 文件快照继续独立保留，不与本次基线混用。共享 C grammar/scanner/generated parser、支持头文件和 Welcome.org 与当前 iOS 源码逐字节一致。

## 本轮修复

- 日历：周/月/年垂直翻页、周内位置与 31 日偏好、年浏览不改选日、月份跳转、今天、原生日期选择、拖动缩放及实时过渡、平板最小月视图与双栏、大字号日期条、日期与连续议程双向联动、置顶日标题、全天事件、逾期入口、完整日记、规范任务拖动改期、按选中日期新建任务。逐行懒加载修复同日 2,000 条任务的内存问题。
- 任务：分组和透视、状态颜色/图标、原生滑动操作、改期/完成撤销、文件定位、上下文预览、父级路径、重复任务主日期推进、多个重复时间拒绝部分修改。表单状态切换不再误推进重复日期；默认不记录日志时不额外生成 CLOSED/LOGBOOK。
- 编辑：Material 下拉框、日期/时间选择和重复规则、规划日志、时长、丢弃确认、实际草稿源预览、配置限定的 Refile、继承归档规则。预览完成按钮与日程共享需要备注时的流程。
- 源码与预览：原生 IME、单步撤销的列表续写和缩进、折叠/大纲过滤/拖动、阅读位置与搜索跳转、去重音高亮、段落分组后保留链接/公式、标题不重复展示 TODO、表格空单元格、抽屉与 CLOCK、原生代码高亮、图片按正文顺序展示、继承附件路径、重试和原生缩放。TIFF 由 libtiff/libjpeg-turbo 原生解码，补齐八方向及常见压缩格式。
- 日记/搜索/设置：iOS 年文件与日/时间标题索引、完整日记详情、六种搜索范围与建议、完整配置转换提示、导入预览/撤销、无效数字不偷偷保存旧值、多值输入保留分隔符、默认状态随序列修正、路径选择、连接状态/错误/断开、三个语言资源键集合一致。
- 存储：文件夹重新授权复用同一工作区、过期文件/文件夹操作拒绝、离线移动/删除撤销及丢失响应恢复、首次 WebDAV 创建与重连不创建分离、附件缓存不掩盖删除/认证错误、冲突版本选择的确认与恢复副本。

## 自动验证

核心 JUnit **40** 项、MockWebServer **7** 项、Android instrumentation **29** 项，共 **76** 项通过。设备测试使用 API 36 arm64 手机窗口；覆盖原有事务/冲突/离线/归档/MOVE 故障注入，以及日历垂直翻页、缩放、月份跳转、日期/滚动联动、同日 2,000 任务懒加载、配置输入、原生高亮、目录重授权、过期文件操作和 TIFF 解码。

原生 InputConnection 测试覆盖中文组合输入、emoji、选择、撤销/重做和回车续写，不代表所有真实输入法都已人工验收。

Release 编译、打包与 Lint 通过：**0 error、306 warnings**。仍有动态资源引用、依赖更新等警告；没有为通过检查而关闭 Lint 规则。证据：`artifacts/qa/parity-release-build.txt`、`parity-instrumentation-final.txt`、各模块 `build/test-results/`。

## 界面检查

已在隔离 QA 工作区查看手机周/月日历、所选日期新建任务、平板导航轨道/日历双栏、2 倍系统字号日期条、长文档议程；查看带中文/emoji/链接/公式/表格/代码/附件的交互预览，以及图片全屏缩放。日历缩放保留 Material 的实时展开和淡入过渡，不移植 SwiftUI 的私有动画实现。

截图位于 `artifacts/qa/parity-*.png`。此前 0.1.0 的真实 SAF 保存与 iOS/电脑往返证据仍在 `compatibility/` 和旧 QA 记录中；本轮没有把这些旧证据标成新的真机或远程服务测试。

本轮另在预览点击复选框，经本机提交和 SAF 同步，核验磁盘文件仅有 `[ ]` → `[X]` 的一个字节变化；再次点击后完整恢复原文。证据为 `artifacts/qa/0.1.1/parity-checkbox-byte-preservation.txt` 及前后 Org 文件。

## 签名、升级和 16 KB

使用原稳定签名证书，SHA-256：`1bc5d142b26c9b8327163ea1b2bf245fb1555834daa6ff13f8b3a72659e07ad0`。APK Signature Scheme v2 校验通过。所有 8 个 arm64-v8a/x86_64 原生库的 LOAD segment 为 0x4000 对齐；`zipalign -c -P 16 -v 4` 通过。

在 `getconf PAGESIZE = 16384` 的 API 36 系统上，从已安装的签名 0.1.0 原位升级到 0.1.1 成功，保留应用数据并冷启动。证据：`parity-signature.txt`、`parity-native-alignment.json`、`parity-zipalign.txt`、`parity-release-upgrade.txt`。额外在该系统的签名 Release 上运行 TIFF 大小端、八方向、Deflate/PackBits/LZW/JPEG、损坏文件与缩放 3 项测试并通过（`parity-tiff-16k.txt`）。x86_64 仅做 ELF/打包核验，未执行设备测试。

## 平台差异与未完成的人工验收

- 未连接真实坚果云账户；HTTP 回归使用 MockWebServer。服务端限流、ETag/MOVE 与 iOS/电脑同时编辑仍需隔离目录验收。
- Android 14 真机、不同 SAF 提供方撤权/加载中行为、实际中文输入法、TalkBack 完整焦点与朗读、真实键盘/触觉、分屏和厂商后台休眠提醒仍需人工检查。
- 图片解码采用 Android/Coil 加原生 libtiff/libjpeg-turbo；TIFF 预览限制为首图、16 Mi 像素和最长 2048 输出边，内存/尺寸超限会显示可重试错误。代码高亮采用 Prism4j 的内置语言及 Shell/Lisp 适配，未内置语言保留原文，着色范围不同于 iOS Highlighter。SAF 不开放任意绝对路径；跨平台附件应使用工作区内相对路径。
- Android 用 WorkManager 与前台核验扫描替代 iOS 目录监控；系统调度和外部提供方不保证实时或跨应用原子写入。旧正文 blob 保守保留，不复刻 NSCache/文件清理策略。

代码中的待实现占位没有作为功能交付；本轮扫描未发现 TODO()/FIXME/NotImplemented 等生产实现占位。上述真实环境与原生能力差异仍明确保留，不能将逐文件映射或 76 项测试描述为“100% 复刻并全量验收”。

## 本包边界与后续协作

本次最终 APK SHA-256：`a7c78e1fee19e07397e630974ed1ab75762698101a24bbdec5fb5ff01c32204c`。此包是本轮验证快照；用户新建的「对齐安卓与 iOS UI 行为」任务已接手后续 UI 与语言资源修改。该任务之后的源码变更不属于本包，不复用这里的测试结论；应重新验证并以新版本交付。
