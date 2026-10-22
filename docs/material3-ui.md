# Material 3 界面优化

本次在已有 Compose Material 3 与动态配色的基础上，统一主界面的颜色角色、列表层级和操作入口。文档正文继续使用原有 DocumentTypography。

## 调整

- 任务行使用 Material `bodyLarge`、`bodySmall`、`labelMedium`，保留完整标题换行、完成状态、滑动、拖放和更多操作。完成按钮保持 48 dp；任务内容起点与列表分隔线对齐。
- 任务标签使用 `secondaryContainer / onSecondaryContainer`，辅助控件直接使用 `onSurfaceVariant`，避免额外混入白色削弱浅色模式对比度。
- 仪表盘、日历时间线与文件页共用分组标题，使用 `surfaceContainerLow`，数量独立显示；标题与数量合并为一个无障碍 heading，避免孤立朗读数字。
- 手机导航栏和宽屏导航轨道使用 `surfaceContainer`，选中图标与未选中图标采用实心／空心区分；图标不再重复朗读已有文字标签。
- 横屏导航仅显示图标，通过图标的无障碍名称保留入口含义；竖屏保留文字标签。
- 导航轨道支持垂直滚动，短横屏仍可到达所有入口；系统字号达到 1.5 倍时文件页采用单栏，为文件名和正文保留阅读空间。
- 仪表盘新建任务采用带文字的 Extended FAB，仪表盘和搜索页顶栏提供设置入口。
- 搜索输入采用圆角填充容器，保留搜索键盘动作、清空按钮、焦点恢复和无障碍名称；选中的筛选 chip 增加勾选标记。
- 文件图标使用带形状的主题容器，文件夹与文档具有不同颜色层级；空状态增加主题图标与居中文案。

## 验证范围

`MaterialLayoutTest` 在 320 dp 宽度下检查普通字号和两倍字号／深色主题：标题不截断、不与操作控件重叠、完成按钮至少 48 dp，且点击只触发一次，并检查分组标题与数量的合并语义。`MaterialAdaptiveLayoutTest` 检查宽屏文件页的字号适配及短横屏导航。其余回归覆盖任务分组与状态恢复、滑动与拖放、页面导航、搜索、文件浏览和日历交互。

验证截图与运行结果保存在 `artifacts/material3/`。构建检查使用 `:app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`；没有运行格式化工具。

最终隔离构建通过，Lint 为 0 errors（393 warnings、5 hints）；48 项设备界面测试全部通过。为避免其它并发构建及设备安装互相干扰，构建在临时源码副本执行，设备回归使用独立 ADB 连接。

## 官方依据

- [Material 3：颜色角色](https://m3.material.io/styles/color/roles)
- [Material 3：字体](https://m3.material.io/styles/typography/overview)
- [Material 3：导航栏](https://m3.material.io/components/navigation-bar/overview)
- [Android：Material Design 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
