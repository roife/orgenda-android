# Android 0.1.0 验收记录

记录日期：2026-10-22。实现与自用安装包已交付；这不是所有真机及服务端场景均已通过的声明。

## 构建与自动回归

最终命令：

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintRelease
```

结果：BUILD SUCCESSFUL。核心测试 20 项、WebDAV HTTP 测试 6 项、Android 设备测试 15 项，总计 41 项通过。设备测试在 API 36 arm64、真实 16 KB 页大小的系统镜像运行。Lint Release 无错误，仍有依赖更新建议、动态字符串引用产生的 unused resource 提示等警告。

自动验证覆盖：UTF-8/UTF-16、emoji/组合字符/不合法 surrogate、嵌入 NUL、增量语法快照、相邻字节保留、CRLF、无末尾换行、复选框空白、过期编辑拒绝、未知语法、Capture、配置缺省/null/空数组/校验、时区与重复规则、LAST_REPEAT、日志抽屉保护、属性继承边界、三方合并、离线重启、上传期间继续编辑、Room 事务失败、归档目标先确认、文件及目录 MOVE 响应丢失、创建冲突、不完整枚举、iOS 回收站兼容、恢复不覆盖、切换工作区前同步/恢复导出。原生 EditText InputConnection 验证中文组合输入、emoji、撤销和重做；这不替代实际中文输入法的整套手动检查。

HTTP 测试使用 MockWebServer，验证条件创建、ETag 冲突、Overwrite:F、WebDAV 多状态响应、根目录缺失和越界重定向。它们不代表实际坚果云服务已经验收。

证据：`artifacts/qa/core-tests.xml`、`webdav-tests.xml`、`instrumentation-16k.txt`、`build.txt`。

## 已完成的模拟器与往返检查

- 手机底部导航、平板导航轨道与文件双栏；按实际窗口宽度调整。
- 浅色、深色、1.8 倍系统字号；修复行内公式暗色主题对比度。
- 英文与繁体中文界面；简体中文资源与另两种语言的键集合相同。
- 系统文件夹选择和持久授权，SAF 正文实际读取/写入与恢复副本。
- 中文、emoji、表格、公式、源码高亮、交互复选框和阅读位置。
- 2,000 个标题、246,916 字节的文件在原生编辑器打开、键盘导航和旋转；文件字节保持一致。
- TalkBack 服务启用后应用可运行，语义树中的按钮、复选框和公式具有可读标签；完整连续朗读、焦点顺序与中文发音仍需人工评估。
- 独立 iOS bundle 与 Android 临时工作区及电脑完成往返，完整字节按预期变化；详见 `compatibility/README.md` 和 `artifacts/qa/roundtrip-results.json`。
- iOS 基线的 231 个已记录文件哈希未改变，包含原有未提交修改。

截图：`artifacts/qa/phone-zh-tw-final.png`、`tablet-dark-large-text-final.png`、`long-editor-landscape.png`。

## APK、签名和 16 KB

- APK：`artifacts/orgenda-0.1.0.apk`，applicationId `com.roifewu.orgenda`，versionCode 1，versionName 0.1.0。
- 通过 apksigner verify，RSA 3072，APK Signature Scheme v2。
- 签名证书 SHA-256：`1bc5d142b26c9b8327163ea1b2bf245fb1555834daa6ff13f8b3a72659e07ad0`。
- arm64-v8a / x86_64 的 Orgenda JNI、RaTeX、AndroidX graphics path 共 6 个 so，其所有 LOAD segment 对齐均为 0x4000。
- `zipalign -c -P 16 -v 4` 通过。
- 系统镜像 `system-images;android-36;google_apis_ps16k;arm64-v8a` 的 `getconf PAGESIZE` 为 16384。
- 签名 Release APK 在该镜像冷启动成功，原生 Org 解析与行内/块公式均正常显示。同一签名 APK 再次安装升级成功。
- x86_64 已检查 ELF/打包对齐，但未在 x86_64 设备执行。

证据：`artifacts/qa/signature.txt`、`native-alignment.json`、`zipalign.txt`、`release-16k-launch.txt`、`release-16k-formula.png`、`release-upgrade.txt`。APK 校验和在相邻 `.sha256` 文件。

## 仍需真实环境验收

- Android 14 真机，以及不同厂商手机/平板；真实分屏、硬件键盘、拖动和触觉反馈。
- 实际中文输入法的候选选择、组合输入中切换页面、选区和连续撤销；完整 TalkBack 阅读流程。
- 通知/精确提醒权限的授予与撤销，Doze、厂商省电限制、重启和时区改变后的实际提醒投递。
- 用户的坚果云/WebDAV 隔离目录，包括真实 ETag/MOVE 行为、限流、断网、同时由 iOS 和电脑修改。
- 不同 SAF 文件提供方的撤权、加载中目录、能力差异和写入失败。

上述代码路径已实现，并有部分自动故障注入覆盖；真实环境的投递、并发语义和可访问性不能由模拟器/MockWebServer替代。源文件、未同步正文和冲突恢复副本应继续作为验收时的核对依据。
