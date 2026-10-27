# 数据、提醒与 AI 操作约束

## 数据源

`PlannerDatabase` 是任务的唯一正式数据源，包含 `tasks`、`reminders`、`messages`、`message_tasks`、`history`、`settings`。任务和提醒使用 UUID；消息与任务是多对多关系。删除任务使用软删除，历史消息和变更记录不会悬空。聊天记录不是任务数据库的替代品。

`Repository` 在 Room 事务内同时保存任务、提醒、前后快照、消息关联和操作卡片状态。每个任务有递增 `version`；手动编辑和 AI 修改均比较原版本。一个 AI 批次只要有一个目标过期，整个事务回滚。重复点击确认不会重复执行。

提醒触发不递增任务内容版本。因此编辑保存时，对时间和重复规则没有变化的提醒，合并当前数据库里的送达状态，防止旧编辑器把已送达提醒重新启用。

## 提醒恢复

`ReminderEngine` 使用 `AlarmManager`，每个提醒的 PendingIntent 使用稳定且唯一的 data URI。调度登记保存本地发生时间及对应 epoch，接收器执行前再次核对任务是否有效、提醒是否开启、发生时间是否一致、是否已经送达。实际补发的系统唤醒时刻与逻辑发生时间分离，避免应用冷启动恢复使待触发事件失效。

数据库事务先提交；之后立即重排系统提醒，并登记 WorkManager 恢复任务。应用启动、开机、包更新、时间/时区变化、授权变化及周期安全恢复都会重新计算。持久化已登记 ID 集合用于取消已删除或被替换的提醒。通知使用稳定 tag，重复恢复不会堆叠同一条通知。

Room 事务与 Android 系统服务之间没有跨服务原子事务。进程在提交后被终止时，下次恢复会从 Room 重建；若同步系统服务失败，界面会报告数据已保存但提醒同步失败，不声称全部成功。WorkManager 周期任务只是补救机制，正常到点执行使用 AlarmManager。

## AI 边界

`AiClient` 只提供解析建议，不直接访问 DAO。上下文包括本次消息、引用消息、少量必要的近期上下文以及最多十二个相关任务。引用关联 ID 优先；没有相关任务时不将所有本地事项上传给模型。

图片、OCR、外部应用分享文字、引用消息及备注均作为不可信数据传递。外部分享文本与用户手写指令分别存储。系统提示禁止执行源内容中的指令，但提示词本身不是唯一保障：`ProposalValidator` 严格检查动作、字段、日期、重复规则、提醒关系、相关目标 ID、引用目标范围和版本。

允许动作只有 CREATE、UPDATE、COMPLETE、DELETE。模型不能设置完成时间、删除标记、数据库 ID、版本或其他内部字段。程序为新任务和提醒生成 ID；创建时再次在事务内查重。延期只移动相对提醒，不重写用户指定的绝对时间。

所有建议都需用户核对卡片后确认，数据库提交成功后状态才变为 APPLIED。真实任务卡片从数据库实时读取。模型文本不会作为“修改成功”的依据。

`startDateAdvice` 仅接受 ASK/NONE。只有创建或修改、确实没有开始日且有截止日的建议才能触发开始日期选择；已有日期不覆盖。兼容旧模型漏填字段时，对作业、计划等标题做保守兜底，普通延期不触发。`ProposalChoices` 严格检查所有待选任务 ID 与用户选择集合完全一致，验证日期范围，不静默补今天。Repository 在同一事务中保存最终开始日期、类别、消息关联及历史，并将实际选择写回操作卡片；不改变原有独立提醒时刻。尚未选择、选了无关 ID 或开始日晚于截止日均拒绝整个批次。

类别由解析器按语义填写，显式用户类别优先；模型漏填新建事项类别时程序按标题基础分类，未知用日常。修改操作未包含类别时保留原值。

## 网络和存储

使用 OkHttp 标准 HTTPS 证书验证，无 trust-all、自签名放行或证书绕过。禁用自动重定向和连接中断时的隐式重试。不记录请求体、Authorization 或错误响应正文。备份不包括 Key，并禁止恢复操作覆盖现有模型连接配置。

应用显式排除 Android 系统云备份与设备迁移中的私有数据，避免密钥密文、调度登记和数据库被不完整地迁移；换机使用应用内 JSON 导出和恢复，重新填写 API Key。旧版 `fullBackupContent` 和 Android 12 起的 `dataExtractionRules` 均有对应排除规则。

API 类型、地址、模型与能力开关保存在 Room，两个服务类型拥有独立配置和密文存储。旧版单一密文按原地址迁移；切换类型不会复制 Key。恢复备份保留本机当前与备用服务配置。模型列表走同一安全 HTTPS 客户端，校验 `/models` 返回的 data/id、去重、限制大小，429/503 仅重试一次；取消旧请求并通过请求序号防止迟到响应覆盖新服务的列表。输入 URL/Key 时不自动发送，离开输入框后延迟获取，可显式刷新。

首次权限引导状态属于本机，存于私有 SharedPreferences，不随 JSON 备份恢复。普通进入按钮要求通知和精确闹钟权限齐备，用户可明确确认仅使用手动排期。引导期间系统分享和深链仍保存在 ViewModel，完成后回到对应页面。

月历 RemoteViews 根据宽高与字体缩放计算周行高度、日期字号和待办数量；窄屏显示更紧凑的彩条与两行待办，宽屏彩条可显示标题。Android 12 使用 OPTION_APPWIDGET_SIZES 提供的尺寸分别渲染，最多两个版本以控制位图内存；旧系统按当前方向选择尺寸。桌面网格的最小占位仍由 Launcher 决定。

参考资料：

- [Android 闹钟调度](https://developer.android.com/develop/background-work/services/alarms)
- [Android 系统备份和设备迁移规则](https://developer.android.com/identity/data/autobackup)
- [Room 官方说明](https://developer.android.com/jetpack/androidx/releases/room)
- [Compose April 2025 BOM](https://android-developers.googleblog.com/2025/04/whats-new-in-jetpack-compose-april-25.html)
- [DeepSeek API 文档](https://api-docs.deepseek.com/)
