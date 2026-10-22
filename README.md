# Orgenda Android

0.1.3 延续对 iOS 108 个 UI 及界面支撑文件的行为对照，保留 Material 原生组件，补齐保存失败与重试、文件目录状态、源码撤销分组、日期选区、大纲连续性和跨日/时区刷新。138 项不同自动测试通过；具体差异见 [功能对齐补充](docs/FUNCTION_PARITY-0.1.3.md)，验证范围与安装包摘要见 [本轮验收](docs/ACCEPTANCE.md)。历史签名包与验收证据独立保留。

Kotlin、Jetpack Compose、Material 3 原生 Android 客户端。最低 Android 14 / API 34，当前 target/compile SDK 36，支持 arm64-v8a 和 x86_64。界面遵循 Android 交互，文件和 config.json v1 与 iOS 工作区共享。

## 使用

自用签名包：`artifacts/orgenda-0.1.3.apk`。首次启动使用应用私有的欢迎工作区。在「设置」选择系统文件夹，或连接 HTTPS WebDAV 文件夹（首次连接可创建缺失目录，重连不会重建已删除目录）。坚果云使用应用专用密码。系统目录授权和凭据只保存在 Android 端。

「已保存在设备」表示正文 blob 已持久化且 SQLite 事务提交成功；「已同步」表示外部存储已确认。离线编辑进入持久队列，恢复网络后重试。在设置处理冲突、重试失败操作、导出恢复 ZIP；切换工作区前会尝试同步，仍有未同步修改则要求先导出当前版本的恢复副本。ZIP 包含 local、base、remote 正文及操作清单，不包含 WebDAV 凭据。

包含任务、工作流、Capture、日记、月/周/年日历、Habit 记录、搜索、文件和文件夹管理、归档和 Refile、回收站、源代码编辑与交互预览、通知提醒和配置编辑。手机使用底部导航，较宽窗口使用导航轨道，840 dp 起文件列表与正文双栏显示。支持英文、简体中文、繁体中文、系统字号、浅色和深色主题。

## 构建

使用 JDK 17、Android SDK 36、Build Tools 35.0.0、NDK 28.1.13356709、CMake 3.22.1。Gradle 8.11.1 wrapper 已固定校验和。Android Studio 打开本目录即可；也可创建不入库的 `local.properties`：

```properties
sdk.dir=/absolute/path/to/android-sdk
```

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:lintDebug
```

仅测试领域层：`./gradlew -PcoreOnly :core:test`，仍需要 CMake、C 编译器和 JDK JNI 头文件。

自用 Release 使用本地 `signing.properties`（已加入 .gitignore）：

```properties
storeFile=/absolute/path/to/existing-signing-key.jks
storePassword=YOUR_PASSWORD
keyAlias=orgenda
keyPassword=YOUR_PASSWORD
```

执行 `scripts/build-personal.sh`。初始稳定密钥保存在本机 `~/.local/share/orgenda-android/signing/`，不在仓库中；请独立备份该目录。后续升级继续使用同一密钥，并递增 app/build.gradle.kts 的 versionCode。不要用 debug 密钥替代它。

本机安装的工具链位于 `~/.local/share/orgenda-android/toolchain/`。本机 JDK 为该目录下 `jdk/jdk-17.0.17+10/Contents/Home`，SDK 为 `sdk/`。

## 实现与验收

- [108 个 iOS UI 文件的组件行为对照与 Material 适配](docs/UI_COMPONENTS.md)
- [0.1.3 功能对齐与保留差异](docs/FUNCTION_PARITY-0.1.3.md)
- [223 个 iOS 文件逐项对照与修复](docs/parity/FILE_COMPARISON.md)
- [架构与持久化边界](docs/ANDROID_ARCHITECTURE.md)
- [验收记录与待验证项目](docs/ACCEPTANCE.md)
- [原始语法来源与许可证](THIRD_PARTY_NOTICES.md)
- [iOS 未提交修改基线](docs/ios-baseline.json)
- [往返兼容样例](compatibility/README.md)

没有运行格式化工具，没有创建额外功能分支。本轮未修改 iOS 源码；`python3 scripts/update-parity-inventory.py` 校验本轮 223 文件及已记录的未提交修改。`verify-ios-baseline.py` 仅用于原 0.1.0 的历史工作区，不应拿它验证后续 iOS 开发。
