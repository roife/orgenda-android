# Orgenda Android

Orgenda 的原生 Android 客户端，使用 Org 格式的纯文本文件管理任务、日程和笔记。应用基于 Kotlin、Jetpack Compose 和 Material 3 构建，支持本地工作区与 WebDAV 同步，可与 Orgenda iOS 共享 Org 文件、附件和工作区配置。

支持 Android 14（API 34）及以上版本，提供 `arm64-v8a` 和 `x86_64` 构建。

## 功能

- **任务与日程**：自定义工作流状态、优先级、标签、计划日期、截止日期、重复任务和习惯记录。
- **日历**：通过周、月、年视图浏览日程，查看当天事项并调整日期。
- **快速记录**：使用 Capture 模板创建内容，支持文件、标题、大纲路径和日期树目标；支持日记记录与浏览。
- **文档编辑**：在 Org 源码与交互预览之间切换，使用大纲导航、复选框、撤销和重做；预览支持表格、代码高亮、数学公式和图片。
- **文件管理**：创建、重命名、移动文件和文件夹，归档或重新归类（Refile）标题，通过回收站恢复删除的内容。
- **搜索**：搜索标题、正文、标签和文件内容。
- **离线与同步**：编辑已加载的文件，持久保存待同步操作，处理冲突并导出恢复副本。
- **Android 集成**：通知提醒、桌面快捷操作、自适应布局、系统字号、浅色与深色主题，以及英文、简体中文和繁体中文界面。

## 开始使用

首次启动会创建应用私有工作区，并提供 `Welcome.org` 示例文件，可直接体验编辑和预览。

要使用自己的文件，在「设置」中选择工作区：

| 方式 | 使用说明 |
| --- | --- |
| 本地文件夹 | 通过系统文件夹选择器授权访问包含 Org 文件的目录。 |
| WebDAV | 输入 HTTPS 文件夹地址、用户名和密码，连接远端工作区。坚果云使用应用专用密码。 |

连接已有文件夹后，应用会读取其中的 Org 文件。工作区配置保存在根目录的 `config.json` 中，可以通过设置界面修改或导入。

如需提醒，在设置中启用通知并授予系统权限。精确提醒需要单独授权；未授权时使用不精确提醒，实际投递时间也会受到系统省电和后台限制的影响。

## 保存与同步

编辑首先保存到设备，再同步到所选工作区：

- **已保存在设备**：正文已持久化，本地数据库事务已提交。
- **已同步**：对应版本已得到工作区存储的确认。

离线修改保留在持久队列中，后续通过后台任务重试，也可手动触发同步。发生冲突或操作失败时，可在设置中选择冲突版本或重试失败操作。后台同步由 Android 调度，其他设备的修改可能需要等待下一次检查才能显示。

切换工作区前，应用会尝试同步。如果仍有未同步修改，需要先导出当前版本的恢复 ZIP。恢复包包含待处理文件的本地、基线和远端副本，以及操作清单，供手动恢复使用；它不是整个工作区的完整备份。

Org 文件、附件和 `config.json` 可以跨设备共享。文件夹授权和 WebDAV 凭据保存在 Android 端；密码使用 Android Keystore 密钥加密，不写入共享配置或恢复包。

## 工作区配置

应用使用版本为 `1` 的 `config.json`。最小有效配置为：

```json
{
  "version": 1
}
```

省略的字段使用默认值，包括 `TODO` / `DONE` 工作流、写入 `inbox.org` 的 Capture 模板、`journal` 日记目录和 `.attach` 附件目录。配置中的文件路径均相对于工作区根目录。

| 配置项 | 用途 |
| --- | --- |
| `workflow` | 任务状态、显示样式与默认状态操作 |
| `files` | 收件箱、附件、日记、归档与 Refile 规则 |
| `logging` | 完成任务和修改日期时的日志规则 |
| `reminders` | 提前提醒、重复提醒与截止日期预警 |
| `capture` | Capture 模板及写入目标 |
| `agenda` | 日程来源与排除路径 |

可在设置中编辑这些选项，查看、导入或导出 JSON。配置导入会先校验并展示预览，再确认替换。文件内的 TODO 声明优先于工作区默认工作流。

设置中还提供 Emacs 配置转换提示词，用于辅助生成受支持的 JSON 配置。更多配置样例见 [跨端兼容样例](compatibility/README.md)，字段定义见 [Configuration.kt](core/src/main/kotlin/com/roifewu/orgenda/core/Configuration.kt)。

## 从源码构建

### 环境要求

| 工具 | 版本 |
| --- | --- |
| JDK | 17 |
| Android SDK Platform | 36 |
| Android NDK | 28.1.13356709 |
| CMake | 3.22.1 |
| Gradle | 8.11.1，使用仓库内的 Wrapper |
| Android Gradle Plugin | 8.10.1 |

项目包含 JNI 原生代码，需要安装 NDK 和 CMake。可通过 Android Studio 的 SDK Manager 安装所需组件，然后打开仓库根目录并同步 Gradle。

命令行构建时，配置 `ANDROID_HOME`，或在仓库根目录创建 `local.properties`：

```properties
sdk.dir=/absolute/path/to/android-sdk
```

`local.properties` 已加入 Git 忽略规则。

### Debug 构建

```sh
./gradlew :app:assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。连接已启用 USB 调试的设备，或启动 API 34 及以上的模拟器后，可直接安装：

```sh
./gradlew :app:installDebug
```

### 测试与静态检查

运行核心模块和 Android 模块的本地测试：

```sh
./gradlew :core:test :app:testDebugUnitTest
```

在已连接的设备或模拟器上运行仪器测试：

```sh
./gradlew :app:connectedDebugAndroidTest
```

运行 Android Lint：

```sh
./gradlew :app:lintDebug
```

只运行核心模块测试时，可以跳过 Android 应用模块：

```sh
./gradlew -PcoreOnly :core:test
```

核心测试仍会编译并加载 JNI 库，需要主机上的 CMake、C 编译器和 JDK 的 JNI 头文件。

### Release 构建

准备签名密钥，在仓库根目录创建 `signing.properties`：

```properties
storeFile=/absolute/path/to/release-key.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

签名配置及密钥文件已加入 Git 忽略规则。请单独备份密钥，后续升级使用同一签名，并递增 `app/build.gradle.kts` 中的 `versionCode`。

```sh
./scripts/build-personal.sh
```

脚本依次执行核心测试、Android 本地测试、Release Lint 和 Release 构建，将签名 APK 及 SHA-256 校验文件复制到 `artifacts/`。该目录不纳入版本控制。

## 项目结构

```text
app/             Android 界面、工作区存储、同步和系统集成
core/            不依赖 Android 的 Org 操作、日程、配置与合并逻辑
native/          Tree-sitter 和图片解码器的 JNI 桥接及 CMake 构建
vendor/          随仓库提供的原生依赖源码
compatibility/   跨端兼容样例
docs/            架构、交互规范和验证记录
scripts/         签名构建与兼容性核对脚本
```

## 相关文档

- [架构与持久化设计](docs/ANDROID_ARCHITECTURE.md)
- [Material 3 界面与自适应布局](docs/material3-ui.md)
- [滑动与拖放操作](docs/ui-gestures.md)
- [文档排版规范](docs/document-typography.md)
- [跨端兼容样例与往返验证](compatibility/README.md)
- [版本验收记录与验证边界](docs/ACCEPTANCE.md)
- [第三方依赖来源与许可声明](THIRD_PARTY_NOTICES.md)
