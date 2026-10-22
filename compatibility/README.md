# 跨端兼容样例

`roundtrip.org` 使用 CRLF，包含中文、emoji、组合字符、文件 TODO 声明、重复日程、属性和日志、未知指令、行尾空格、复选框、表格、链接、公式和源码块。`config.json` 是包含默认值与空数组语义的 v1 配置。

2026-10-22 的隔离往返操作记录保存在 `../artifacts/qa/`：

1. Android 通过系统 SAF 目录授权读取样例，完成重复任务，推进日期并写日志。
2. 使用当前 iOS 工作树编译独立 bundle `com.roifewu.orgenda.androidcompat`，在其私有工作区读取 Android 的文件，并切换第一个正文复选框。
3. 在电脑上追加一个未知 `#+desktop-roundtrip` 指令，保留原有字节。
4. Android 重新读取，通过预览切换同一个正文复选框并同步。
5. 每步比较预期的完整文件字节；最后一步只改变状态字符，保留空格、CRLF、未知指令及此前两端修改。

`roundtrip-results.json` 记录每份文件的 SHA-256。验证中发现的复选框空白丢失问题已修复，并加入 `checkboxOnlyChangesTheStatusByte` 回归。当前重复任务的 LAST_REPEAT 行为由核心回归测试覆盖。

这些是专用临时工作区，没有把样例写入用户的生产文件夹。真实 WebDAV 服务端仍需另外验证。
