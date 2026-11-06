# 1.4.1 小组件文字修复

日期：2026-11-06；versionCode 7。可以使用原签名覆盖安装。

用户反馈的“2026？11？/？？”是 bug，正确宽版月历标题为“2026 年 11 月 / 时序”，窄版为“11 月 / 时序”。不是设计，也不是手机日期设置问题。

原因：1.4.0 通过 Windows PowerShell 默认编码管道生成文件时，组件内的中文与部分符号被替换为 ASCII 问号。Kotlin 仍能编译，已有布局与逻辑测试无法检出这类文案损坏。

修复内容：

- 三种组件的标题、日期描述、逾期前缀、空状态、下一条提醒、待办数量、颜色方块统一使用 UTF-8 Android 字符串资源 `app/src/main/res/values/widget_strings.xml`。
- 恢复彩条标题的省略号，使用 Unicode 转义，避免生成过程损坏。
- 修正同次未成功更新的 README 版本说明；交接报告明确约定中文写入方式。
- 新增 `scripts/check-widget-text.ps1`，检查严格 UTF-8 源文件、问号占位以及实际 APK 的编译字符串。打包脚本自动执行检查，不能仅凭编译成功认定文字正确。

本次验证：

- `assembleDebug` 编译成功。
- 实际 APK 的 13 个组件字符串与 UTF-8 资源一致，渲染器中未再发现问号占位。[检查记录](verification/1.4.1/widget-text.txt)
- 定向扫描 `app/src/main` 与 README，未发现连续问号／替换字符。
- 签名校验通过，沿用现有签名。[签名记录](verification/1.4.1/apk-signature.txt)、[版本元数据](verification/1.4.1/apk-metadata.json)
- 这是展示文案修正，未重跑整套单元或设备测试；1.4.0 的 65 个 JVM 通过结果仍属该版本历史记录。未进行当前设备截图验证。

安装 `releases/ShiXu-1.4.1-debug.apk` 后打开一次时序以刷新组件。如桌面继续缓存旧文字，可移除并重新添加组件。本轮源码未提交或推送。
