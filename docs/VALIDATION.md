# 时序 1.0.0 验收记录

验收日期：2026-10-25。交付为 Android 调试签名测试版，可直接安装，不含 API Key、示例数据库或开发者服务器。

## 环境与结果

使用 Windows、JDK 22（编译目标 Java 17）、Gradle 8.11.1、Android Gradle Plugin 8.9.2。设备为本项目专用 Android 15 / API 35 / x86_64 模拟器，1080 × 2400，Asia/Shanghai 时区。

| 检查 | 结果 |
| --- | --- |
| `testDebugUnitTest` | 27 项通过，0 失败 |
| Android Instrumentation | 17 项通过，0 失败 |
| `assembleDebug`、`assembleDebugAndroidTest` | 构建成功 |
| `lintDebug` | 0 错误；38 条非阻断警告，主要为依赖更新提示、KTX 建议和旧版系统忽略的新属性 |
| APK 签名 | `apksigner verify` 通过，APK Signature Scheme v2，Android Debug 证书 |
| 安装包对齐 | `zipalign -c -P 16 -v 4` 通过；此项检查 ZIP 对齐，不代表完成所有 16 KB 内存页设备兼容性测试 |
| APK 平台信息 | `cn.shixu.app`，1.0.0 / versionCode 1，minSdk 26，targetSdk 35；ARM64、ARMv7、x86、x86_64 |

## 实际验证的行为

- **日期和排期**：跨周、六行月份和重叠排期布局；只有截止日期的事项；默认提前提醒；月末及闰年重复计算；错过提醒后的状态处理。
- **真实手动操作**：通过 Compose 界面新建、保存、完成、进入已完成列表、重新打开，检查 Room 中的真实结果。
- **AI 操作安全**：使用固定结构化响应测试引用目标、ID 和版本、未知字段、歧义、相对与绝对提醒；批次遇到过期版本时整体回滚；再次点击确认不会重复创建；确认后真实更新任务、提醒、关联和历史。
- **通知**：真实通知权限、通知去重、完成后取消、过期 PendingIntent 拒绝、冷启动重复重排；另外创建一个未来提醒，等待 Android AlarmManager 实际发送广播，验证通知和送达记录，没有直接调用触发函数代替系统计时。
- **重复恢复**：重新打开已完成事项会恢复重复提醒，保留已送达实例记录，且不会重发已经触发的单次提醒。
- **图片 OCR**：在设备上生成中文公告测试图片，经真实图片导入和随包中文识别引擎识别“作业”和日期；无需 API Key。该测试不代表任意低清晰度截图都可准确识别。
- **备份**：任务、聊天图片、外部分享文本、消息关联和历史完整往返；非法重复 ID 拒绝导入并保留原数据；外部备份不能覆盖本机模型服务地址。
- **桌面组件**：三种 Provider 在真实 AppWidgetHost 中绑定、更新和渲染；测试最小和常用两种高度，检查底部文字完整可见，小组件包含下一条提醒。截图为系统 RemoteViews 的渲染结果。
- **界面**：查看浅色月历、深色月历、引用聊天、确认操作卡片、设置和三种组件截图。截图里的任务是测试夹具，不会预置到交付 APK。

## 报告与截图

- [单元测试原始结果](verification/unit-tests.xml)
- [设备测试原始结果](verification/instrumentation.txt)
- [Lint 原始结果](verification/lint.txt)
- [APK 签名结果](verification/apk-signature.txt)
- [APK 元数据](verification/apk-metadata.txt)
- [浅色月历](screenshots/calendar-light.png)、[深色月历](screenshots/calendar-dark.png)、[引用及操作卡片](screenshots/chat-proposal.png)、[设置](screenshots/settings.png)
- [今日组件](screenshots/widget-0.png)、[近日组件](screenshots/widget-1.png)、[月历组件](screenshots/widget-2.png)

## 未覆盖的实际环境

没有使用私人 API Key 发起付费模型请求，因此尚未实测具体服务的识别成功率、真实图片接口和网络故障组合。API 调用代码完整保留，配置服务后可使用连接测试。

尚未连接 vivo 等实体手机；厂商省电、自启动、长时间熄屏及强行停止后的表现仍需实机验证。开机、时间及权限变化的恢复接收器已实现，本次没有完整执行关机重启、跨时区、各厂商系统的组合验收。完整手工检查步骤见 [验收清单](ACCEPTANCE.md)。

重复事项在完成本期后生成下一期；月历展示已经保存的事项。自定义人格用于聊天，通知采用本地预设模板。以上产品行为与备份大小限制详见 [README](../README.md)。
