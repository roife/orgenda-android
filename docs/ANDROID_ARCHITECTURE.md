# Android 实现

## 领域与 UI

`core` 不依赖 Android：`OrgEngine` 包装不可变语法快照，Tree-sitter 通过 JNI 增量解析。C runtime 固定为 0.26.3，复用 iOS 当前工作区的 Org parser/scanner。源码位置使用 UTF-8 字节；原生 EditText 使用 UTF-16，边界转换拒绝截断 surrogate 或多字节字符。结构化修改只生成目标字节范围补丁；不安全的未闭合结构会要求转到源码编辑。

`NativeOrgEngine` 负责状态切换、规划日期、重复任务、LAST_REPEAT、日志、属性、复选框与子树移动。`CaptureEngine` 负责模板宏和 file/headline/outline/datetree 目标。配置 v1 保持缺省、null 和空数组语义；文件内 TODO 声明优先。文件内和继承的 ARCHIVE 规则仅在归档时解析，普通属性不意外继承为子任务属性。

Compose 页面通过 ViewModel/StateFlow/协程读取快照。源代码编辑器嵌入 AppCompatEditText，使用系统 IME、选择及光标，记录组合输入与撤销历史。自动保存 debounce 不取消已经开始的持久化，异步结果核对会话、正文和版本。原生 RaTeX 0.1.14 Canvas/View/Span 渲染公式；TextView spans、Coil 和 Compose 分别提供富文本、图片和表格/日历等布局。

## 持久化与同步

`WorkspaceRepository` 是状态入口，`StorageBackend` 提供 private/SAF/WebDAV 实现。不可变 SHA-256 blob 先写临时文件并 fsync，rename 后 fsync 目录；Room 使用 SQLite WAL 和 synchronous=FULL，提交文件引用和待同步操作后才返回保存成功。失败事务可能留下未引用 blob，不会丢失原有正文。

正文保存使用预期 blob 校验，旧编辑器无法覆盖新提交。同步成功只确认实际上传的版本；上传期间的新修改继续保留。离线队列、冲突副本、跨文件依赖和移动阶段存储在数据库中，进程重启后恢复。不同区域的文本修改进行三方合并，重叠修改保留 local/base/remote 等待选择。

跨文件归档/Refile 将目标追加与源删除在本机同一个事务中提交。目标外部存储确认了确切 blob 之后才上传源删除；重试不再次追加。依赖未完成时保护目标正文。目录移动先检查完整枚举和缓存正文，响应丢失时核验目标目录及正文再完成本机记录。移动不覆盖既有目标；文件夹提供方不能一次完成改名与跨目录移动时，采用持久的三个步骤。

SAF 使用系统目录选择器与持久 URI 授权，显式检查目录枚举错误和加载状态，不把不完整枚举当成删除。写入前保留 `.orgenda-recovery` 副本并核验前后内容；这些步骤无法提供跨应用原子比较写入保证。授权撤销时保留本机草稿并显示错误。

WebDAV 使用 HTTPS、OkHttp、强 ETag 条件写入、If-None-Match 创建和 MOVE Overwrite:F。同根路径重定向才允许携带认证；不通过忽略 TLS 校验连接服务器。坚果云采用临时资源及条件移动，遵守最小轮询间隔、Retry-After 与指数退避。实际服务端兼容性仍需使用自己的隔离工作区验证。

回收站采用与 iOS 一致的 `.orgenda-trash/<UUID>/info.json` 和 `item`，恢复不覆盖同名新文件。恢复 ZIP 还包含持久操作清单，便于外部手动恢复。附件读取后缓存为 blob，可离线使用已缓存附件。原始 Org、附件、config.json 可共享；SQLite、目录权限、编辑器历史、凭据不能跨设备复制。

## 系统集成

WorkManager 负责同步与重试，AlarmManager 负责提醒。POST_NOTIFICATIONS 和精确提醒权限由用户在系统 UI 授予；未获精确权限时使用不精确提醒并在设置显示状态。应用保留计划并响应开机、升级、时区与时间变化。Android 休眠和厂商后台限制仍影响投递时机。

凭据以 Android Keystore AES-GCM 密钥加密。应用禁用云备份和设备迁移，避免复制无法解密的凭据或失效目录授权。Release 使用独立稳定签名密钥。NDK 链接设置为 16 KB；打包和 RaTeX 预编译库也需要一起验证。

## 图片预览

普通位图、GIF、SVG 使用 Coil/Android；TIFF 使用从固定源码编译的 libtiff 4.7.1 和 libjpeg-turbo 3.2.0，通过 JNI 返回 Android Bitmap。支持首帧/首页、八种方向，以及无压缩、LZW、PackBits、Deflate、JPEG、CCITT 等内置 TIFF 编码。输入上限 20 MiB；TIFF 解码限定 16 Mi 像素，解码器内部累计分配最多 16 MiB，输出边长最多 2048；多个 TIFF 解码串行执行。无文件扩展名依赖，错误进入现有重试 UI，正文/附件原文件不转换或改写。源码归属和哈希见 `vendor/image-codecs.json` 与 THIRD_PARTY_NOTICES。
