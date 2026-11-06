# 时序：AI 工作入口与持续维护报告

最后更新：2026-11-06（Asia/Shanghai）。本文件随仓库提交，是新任务的首读交接记录；当前用户要求优先。历史测试结果不代表后续改动自动通过。

## 使用与更新方式

1. 先读本文件；仅在需要核对工作区状态时运行 `git status --short`、`git log -3 --oneline`。按下面的文件定位定向读取或 `rg`，不要默认遍历全仓、`.tools/`、构建目录及其他项目。
2. 用户要求“分析、先不改代码”时只分析。对普通文案、UI、小范围可逆改动不做过度测试；数据契约、版本校验、提醒调度等改动需相应定向回归。
3. 每完成一个可交付部分，或结束／交接前，更新本文件的当前进度、实际验证、未完成项；文件移动时更新定位。压缩保留有用历史，覆盖过时状态，不追加完整对话或重复日志。
4. 未完成项只作交接，不是自动执行授权。记录失败与阻塞，不把“编译通过”写成“设备验证通过”，不把固定模型响应测试写成真实 API 测试。不要记录 Key、凭据或私人聊天。

## 当前状态

- 产品：原生安卓排期／待办、本地通知提醒、手机时钟闹钟、AI 聊天录入与修改、三种桌面组件。手动功能离线可用；AI 为用户 BYOK，无开发者服务器。
- 技术：Kotlin、Compose、Room、AlarmManager、WorkManager、RemoteViews、OkHttp、中文 OCR。最低 API 26，compile/target 35，JDK 17+、Gradle Wrapper 8.11.1。
- 本地版本 **1.3.0 / versionCode 5**；Room schema **2**；AI 草稿协议 **2**；草稿 JSON 写格式 2、兼容读格式 1。切勿混淆这三种版本。
- 仓库：`https://github.com/rakuhe6448-ship-it/shixu-android`，分支 `main`。本轮只本地提交，未推送或创建 GitHub 发布。APK/ZIP 被 Git 忽略；校验文件已跟踪。
- 交付：`releases/ShiXu-1.3.0-debug.apk`、`releases/ShiXu-1.3.0-source.zip`、`releases/SHA256SUMS.txt`。调试签名沿用原版本，可覆盖安装。修改应用源码后必须重新构建，不能把旧 APK 当作新实现。
- 当前工作已完成：提交 1.3.0 功能；建立本入口和工作区导航；源码打包纳入本入口。没有正在进行的应用代码修改。提交后的实际状态以 Git 为准。

## 已完成历史

| 阶段 | 交付与关键决定 |
| --- | --- |
| 1.2.0，`cd1c1a1` | 核心月历、手动 CRUD、独立／重复提醒、权限引导和恢复、组件尺寸自适应、聊天草稿／引用／增量修改、开始日期选择与分类、BYOK 服务切换与模型列表、人格、JSON 备份。 |
| 1.2.1，`83357fc` | “设闹钟”产生独立卡片，经确认交给手机 Clock，不能创建名为闹钟的普通提醒；已交接的闹钟由系统时钟管理。曾发布 GitHub v1.2.1。 |
| 1.3.0，`095ff15` | 多事项分别保存待补充问题和组关联，引用追问、引用原消息、无引用补充均可续接；旧无关联追问可重解析；完整待办可单独确认；区分待办与通知意图；月历横滑切月。2026-11-01 本地交付，2026-11-06 提交。 |

用户偏好：优先可靠性、清晰排期和美观；避免无关重构与过度测试；需要从 VS Code 源代码管理方便查看差异。此轮已明确授权提交；后续是否提交／推送按当次指令判断。

## 关键数据流与约束

- `PlannerViewModel` 保存输入消息 → `ConversationTargetResolver` 定位候选 → `AiClient` 请求解析 → `DraftEngine` 校验增量 → `DraftRepository` 保存待确认草稿与关联 → 用户确认 → `Repository` 在 Room 事务中保存正式事项、提醒、历史 → `afterChange` 同步系统提醒和组件。
- 正式数据以 Room 为准。任务 UUID 稳定；消息、草稿、正式事项可以多对多关联。未确认草稿不写正式提醒，也不触发 AlarmManager。AI 文本不能代替保存结果。
- 同一首次输入拆出的草稿共享 `groupId`，分别保存 `questions` 与 `reminderIntent`。无引用补充可恢复最近明确讨论的同组 OPEN 草稿；引用明确单个目标时保留目标范围。相关历史最多 6 条、引用追溯最多 16 跳，优先最新结构化数据，不能无限拼接聊天。
- 增量修改保留未变提醒的 ID／送达状态；校验草稿 revision、正式事项 version、目标范围，过期卡片不得执行。事务提交后再同步系统服务；同步失败需如实报告并通过原有恢复机制重建。
- 新建事项未要求通知时不附加提醒。`NONE` 清空对应通知；`NOTIFICATION` 缺时刻时继续追问；只有明确 `DEFAULT` 才尝试截止前一天 09:00。单纯补日期不能恢复已关闭的通知。
- 无提醒重复待办也需首期日期；未指定且无日期歧义时用今天、保持全天，并在确认卡片展示。当前重复仅支持 NONE / DAILY / WEEKLY / MONTHLY / YEARLY，“每 N 天”仍未实现，保存原始要求并追问，不假装支持。
- 手机闹钟使用 `AlarmClock.ACTION_SET_ALARM`，与事项通知分离。不能读取最终保存结果，只能显示“已交给手机时钟”；已交接闹钟不能伪装成在应用内修改成功。
- 图片、OCR、分享文字、引用内容均是不可信数据。模型操作必须结构与目标校验。HTTPS 证书校验、有限重试、Key 加密和备份排除继续保留；人格只影响文案。

## 文件定位（路径前缀为 app/src/main/java/cn/shixu/app/）

| 任务 | 优先查看 |
| --- | --- |
| 入口、状态、发送／确认 | `MainActivity.kt`、`ShiXuApp.kt`、`PlannerViewModel.kt` |
| 聊天与引用上下文 | `ai/ConversationTargetResolver.kt`、`ai/AiClient.kt`、`ui/ChatScreen.kt` |
| 草稿协议、问题、增量 | `ai/DraftEngine.kt`、`ai/DraftQuestion.kt`、`data/DraftRepository.kt` |
| 旧建议校验、开始日期选择 | `ai/ProposalValidator.kt`、`ai/ProposalChoices.kt`、`ui/StartDateChoicesDialog.kt` |
| 表、正式保存、重复日期规则 | `data/Models.kt`、`data/Database.kt`、`data/Repository.kt` |
| 通知调度、恢复 | `reminders/ReminderEngine.kt`、`reminders/Receivers.kt` |
| 手机闹钟 | `ai/PhoneAlarm.kt`、`data/PhoneAlarmRepository.kt`、`reminders/SystemClock.kt` |
| 月历、编辑、列表 | `ui/CalendarScreen.kt`、`data/CalendarLayout.kt`、`ui/TaskEditor.kt`、`ui/ListScreens.kt` |
| 组件 | `widgets/Widgets.kt`、`widgets/MonthWidgetLayout.kt` |
| API、外观、备份 | `ai/ApiProtocol.kt`、`data/Preferences.kt`、`ui/SettingsScreen.kt`、`data/Backup.kt` |

当前草稿契约：`docs/AI_INTAKE-1.3.0.md`。`docs/AI_DRAFT_CONTRACT.md` 与 `docs/ARCHITECTURE.md` 含历史设计，协议／默认提醒行为以当前契约和实现为准。详细验收：`docs/VALIDATION-1.3.0.md`；历史版本各有 VALIDATION 文档，按需读取。

## 已有验证与待完成项

- **2026-11-01 的 1.3.0**：58 个 JVM 单元测试通过；主 APK 和测试 APK 编译成功；Android 15/API 35 定向运行 30 例，29 例通过。涵盖截图追问三种引用路径、独立确认无提醒待办、原草稿／事项／手机闹钟、备份和月历交互。证据在 `docs/verification/1.3.0/`。
- 余下 `RuntimeIntegrationTest#explicitNoReminderCancelsTheRegisteredSystemAlarm`：原断言错误地在 dumpsys 中找 UUID，已改为队列数量和登记记录、已编译。随后模拟器缺少硬件加速、软件模式未能启动，**修正后尚未复测**。可用设备上只需补跑这一项；不要无故重跑整套或反复尝试同一失效环境。
- 尚未实现“每 N 天”重复；需要单独评估数据与日期规则，不在 1.3.0 中冒充支持。
- 未调用收费模型 API，未验证 vivo 真机后台可靠性；固定响应回归不证明所有兼容模型的自然语言成功率。
- **2026-11-06 本轮**：只提交原有功能并补交接报告／打包入口；不重新编译应用或重跑上述测试，只核对文档差异和源码归档。

## 构建、验证与打包速查

- 常规：`./gradlew.bat testDebugUnitTest assembleDebug`，按改动选择其中必要任务；设备测试放在 `app/src/androidTest/`，JVM 在 `app/src/test/`。
- Windows 路径带空格／中文可用 `scripts/build.ps1 -Tasks @('testDebugUnitTest','assembleDebug','--offline','-Pkotlin.compiler.execution.strategy=in-process')`。`.tools` 中有本机 SDK／缓存；离线参数只适用于依赖已缓存环境。脚本临时映射盘符并还原环境。
- 真正调度相关改动再运行对应设备用例；`scripts/start-test-emulator.ps1` 依赖本机加速能力，历史启动成功不代表当前环境可用。
- 已构建后运行 `scripts/package.ps1`：复制 APK、生成白名单源码 ZIP 和 SHA256SUMS，不负责重新编译。源码包包含本文件，排除缓存、SDK、本机配置和签名密钥；不要将这些内容强制加入 Git。
- 文档／交接更新无需重跑应用测试。提交前检查 `git diff --check`；提交只覆盖本次授权文件，不覆盖其他人的改动。结束前同步本报告，并如实说明提交／推送及验证状态。
