# 1.3.0 构建与定向验收

## 本次行为

- 初次录入多个事项时分别保存草稿、待补充问题及提醒意图。追问不再丢掉原始事项；引用原消息、引用追问回复和无引用补充均能继续处理同一组草稿。
- 对旧版没有事项关联的追问，读取有界引用上下文重新解析，再恢复关联。仍校验目标范围、草稿 revision 和正式事项 version。
- 新事项没有通知要求时不自动添加提醒。“只加待办／不用提醒”明确清空对应通知；重复待办没有首次日期时，在没有日期歧义的前提下以今天作为首期日期，并在卡片中展示。
- “需要通知”但缺少通知定义时保持待补充状态；不能只修改卡片状态绕过提交校验。同一批次中完整事项可单独预览并确认，不必等待另一事项补完。
- 月历网格支持左右滑动切月，保留箭头、日期点击、竖向滚动及月底日期截取。
- Room schema 仍为 2；扩展草稿 JSON 格式，同时兼容旧格式。备份保留问题和组关联，恢复本身不安排提醒。

数据契约见 [AI_INTAKE-1.3.0.md](AI_INTAKE-1.3.0.md)。

## 已完成的验证

- `testDebugUnitTest`：58 个用例全部通过，0 失败、0 错误。包含草稿增量修改、多事项问题保留、提醒策略冲突、缺少通知时刻、旧引用解析及已有日期／手机闹钟规则。
- `assembleDebug`、`assembleDebugAndroidTest` 构建成功。版本为 1.3.0，versionCode 为 5，最低 Android 8.0 / API 26。
- Android 15 / API 35 模拟器首次定向运行 30 个用例，29 个通过。通过的范围为：
  - 3 个追问集成用例，其中截图对话分别验证无引用、引用原用户消息、引用 AI 追问三条路径。
  - 6 个原有多轮草稿用例、5 个手机闹钟用例、8 个事项数据库／提醒用例。
  - 5 个备份用例，包含待补充问题与组关联的往返恢复。
  - 2 个 Compose 交互用例：跨年／闰月滑动、箭头与日期点击；一项未完成时单独确认另一项无提醒待办。
- 调试签名沿用原有安装包的签名，支持覆盖安装；不需要先卸载应用。

记录：[单元测试](verification/1.3.0/unit-tests.txt)、[安卓首次运行](verification/1.3.0/android-initial.txt)。

## 尚未完成的一项验证

新增的 `explicitNoReminderCancelsTheRegisteredSystemAlarm` 首次在“检查系统已登记闹钟”的测试断言处失败：Android `dumpsys alarm` 不输出提醒 UUID，原断言不适用，尚未运行到取消行为的断言。

已将测试改为核对系统 `cn.shixu.REMINDER` 队列数量及应用登记记录，并成功编译测试 APK。续跑时当前环境缺少 Android Emulator hypervisor driver；软件 CPU 仿真未能完成启动，因此该项修正后的端到端测试仍待执行，不能计为通过。没有为此安装系统驱动。

在可用模拟器上可仅重跑这一项：

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class cn.shixu.app.RuntimeIntegrationTest#explicitNoReminderCancelsTheRegisteredSystemAlarm cn.shixu.app.test/cn.shixu.app.TestRunner
```

本次未修改 ReminderEngine 的调度算法；“不提醒”通过原有事务保存与 afterChange 对账取消系统提醒。数据库清除、待办保存及提交隔离已由上述通过用例覆盖，系统队列检查仍需补验。

## 范围与手机试用

- 未调用收费模型 API。回归使用固定结构化响应，验证程序的上下文组装、目标校验、真实 Room 写入和 UI；自然语言识别表现仍需用自选服务试用。
- 本轮未扩展“每 N 天”重复。程序提供实际支持的 NONE / DAILY / WEEKLY / MONTHLY / YEARLY，未支持的原始要求保留为问题，不能自动改成另一种周期。
- 未把模拟器结果当作 vivo 等真机后台兼容验证。

安装 `releases/ShiXu-1.3.0-debug.apk` 后，可发送“每隔三天提醒我面部清洁。每天晚上提醒我护肤。”，再引用追问回复“面部清洁改成每周，护肤只放待办，不提醒”。护肤应可单独确认保存，清洁仍询问具体通知日期和时刻。另可在月历网格左右滑动，确认日期点击与上下滚动可用。

APK 与源码包由 `scripts/package.ps1` 生成，校验值位于 `releases/SHA256SUMS.txt`。本轮保留未提交的源码差异，供 VS Code 源代码管理查看；未上传 GitHub 或创建发布。
