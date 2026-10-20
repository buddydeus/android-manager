# 设备验证记录

更新日期：2026-10-20（上海）。测试机：OPPO PKD130，Android 15 / API35，ColorOS 15，当前用户0，序列号尾号828E0。

## OPPO 实机

| 检查 | 结果 |
| --- | --- |
| USB、ADB 授权 | 通过，状态 device；当前可持续执行调试命令 |
| Shell、push/pull | 通过，独立临时文件往返内容一致，已删除 |
| Device Owner | 已取得；管理员移除账户后复核为0个账户，重新安装 APK，正常注册成功 |
| 最终 Debug APK 安装与启动 | 通过；启动返回 Status: ok，未发现应用 AndroidRuntime 崩溃 |
| instrumentation | 5项通过、1项按条件跳过：DP状态损坏处理、真实应用目录与保护、初始化入口隔离、SAF错误查询、SAF确认文件缺失 |
| 初始管理入口 | 未初始化不施加策略；管理员已授权本地密码目录、完成默认密码验证与首次改密 |
| 系统市场 | 暂停成功、隐藏被 ROM 拒绝；完整暂停+隐藏探针失败，已恢复原状态 |
| 小红书对照探针 | 暂停、隐藏、保留隐藏目录记录及解除恢复通过；未修改生产黑名单或周表 |
| 通知、已打开应用 | 市场前台任务在暂停时结束；通知实机展示仍待验证 |
| 锁屏 / Doze / 真实边界 | 已实测；最终可自动解除，但比21:21边界延迟约118秒，即时性不通过；开机验证另见下文 |
| 本机密码目录及恢复 | 本地目录授权及首次改密通过；删除和异常恢复尚未在实机验收 |

连接期间 libusb 出现读取错误并断开；使用当前服务临时环境 `ADB_LIBUSB=0 adb start-server`，手机重开 USB 调试并重新连接后恢复。未修改主机持久设置，未移除手机账户、清除数据或恢复出厂设置。

实机初始化后复核：`auto_time=1`、`auto_time_zone=0`、`persist.sys.timezone=Asia/Shanghai`，DPM 和用户限制均包含本应用施加的 `no_config_date_time`。

系统市场失败证据：`OplusForbidHideOrDisableManager: Cannot hiding protect app com.heytap.market`；DPM `setApplicationHidden` 返回 false。不得将其记录为隐藏成功，也不通过其他手段绕过 ROM 保护。相同显式单包探针在 `com.xingin.xhs` 通过（1项），两次探针后均确认 `hidden=false / suspended=false`。

此前账户阻挡解除后，首次重试发现目标包当前不存在，系统报 Invalid component；重新安装已验证 APK 后注册成功。未通过清除数据或恢复出厂处理。

实机首次锁屏深度待机边界测试失败：临时周二 00:00–08:23、小红书在限制时段暂停并隐藏；授予精确闹钟权限并返回应用后已登记 08:23 边界。但 08:23:24 仍受限，系统闹钟显示 `adjustment` 约加3天，日志有 `OplusAlarmAdjustment proxyAlarmsByUid`。已退出强制待机，返回应用重新计算后解除。随后在系统耗电管理发现“允许应用后台行为”关闭，已通过标准设置开启并确认“不优化”，不能将该失败算作通过。

### 后台边界复测（21时段）

由于 ROM 屏蔽管理页的自动化读取，新增显式 UUID、配置版本及 arm/cleanup 两阶段调试探针，调用相同的生产策略协调器；不读取密码，不改变认证代码。arm 后 instrumentation 退出，锁屏并强制深度待机，单独观察真实系统闹钟。cleanup 复核配置未变化后清空临时黑名单和周表。

开启后台行为后，21:13 解除边界仍失败：21:16:00 目标仍隐藏并暂停。系统于21:15:07.531才发送闹钟，紧接着 `OplusAppStartupManager` 记录 `prevent start ... AlarmReceiver by broadcast system[alarmManger]`。实际闹钟窗口为127529ms；因此既有延迟，也有自启动拦截，不能记为即时生效。随后 cleanup 用例通过，确认空黑名单/周表且目标恢复。已在标准系统“应用 → 自启动”中将本应用由“开机自启动、后台自启动已禁止”改为允许；未改其他应用的自启动设置。

允许自启动后的最终复测：21:21边界，整个观察期间未打开管理界面、未运行 instrumentation。系统于21:22:56.186发送闹钟，21:22:56.328启动接收器；应用日志记载21:22:58.152解除隐藏、21:22:58.310解除暂停。锁屏深度待机下自动执行通过，但边界延迟约118秒，**即时性要求未通过**。此轮系统窗口116151ms，不能通过应用已调用 `setExactAndAllowWhileIdle` 就宣称目标ROM准点。

两轮探针各自的 arm / cleanup 均通过；最终已退出强制待机，总开关保留开启，黑名单为空、七天周表均未设置、无待执行边界，小红书 `hidden=false / suspended=false`。密码未读取或修改；保留必要的闹钟、后台行为与自启动授权。

### 实机重启核验

重启前复核：配置版本7、初始化完成、总开关开启、黑名单为空、七天周表为空、无待执行边界，系统 boot count 为21。正常重启后曾只枚举 MIDI 接口，ADB 列表为空；管理员解锁并重新开启 USB 调试、重新连接后恢复，无需重新注册 Device Owner。

未重新打开管理界面，读取到 boot count=22，`lastPolicyBoot=22 / lastCleanupBoot=22`，时间策略启用，系统事件服务 `[bound] [connected]`；配置版本仍为7，黑名单与周表仍为空，无待执行边界，小红书未隐藏也未暂停，AndroidRuntime 无应用崩溃。**本次空规则、总开关开启的实机重启恢复通过**；不据此宣称已验证非空黑名单在首次解锁前恢复。

已卸载临时 `com.buddydeus.androidmanager.test` 测试 APK；正式应用及 DO 身份保留。DP 文件夹仅余正式 state、marker 和 logs，无边界探针标记；强制待机已退出为 ACTIVE。

## 独立模拟器

新建 `android_manager_validation`（Android 16/API36，Google APIs x86_64，独立空白设备），通过正常 `dpm set-device-owner` 注册。测试不代表 OPPO 管控能力。

- 真实 DPM 集成用例通过：对显式指定的 Google Clock 执行暂停及隐藏，确认隐藏后仍保留应用目录记录；注入测试时钟越过区间后解除；再次限制后保存关闭并执行开机协调，确认解除暂停、隐藏及本软件时间限制。
- 基础存储与 SAF 查询用例通过；已初始化模拟器跳过未初始化入口用例，该入口已在 OPPO 通过。
- 使用真实系统目录选择器授权 `Documents/AndroidManager`，默认密码验证、强制新密码设置通过；最终 APK 覆盖安装后目录授权保留，重新验证密码进入管理界面通过。
- 在独立模拟器通过 ADB 直接删除共享密码文件，返回登录后 `0000` 可验证且强制设置新密码；完成后黑名单1项、周表及关闭状态保留。该操作未在 OPPO 上执行。
- 从管理界面搜索 Clock、加入黑名单、保存当天生效周表后，系统实际显示 `hidden=true / suspended=true`；关闭总开关后变为 `false / false`，时间限制解除，黑名单保留。精确闹钟未授权时界面明确提示能力不足。
- 额外执行一次模拟器真实重启，全程未打开管理界面：系统开机广播送达后，`lastCleanupBoot` 和 `lastPolicyBoot` 均更新至当前 boot count 2，总开关保持关闭、黑名单保留，Clock 未隐藏也未暂停，未见应用崩溃。
- 注入时钟用例不等同于真实闹钟唤醒；本次无锁屏凭据的模拟器重启不等同于带密码锁屏的解锁前验收。

## 构建与回归

- 35项单元测试通过，覆盖跨日周表、原子存储、日志保留、密码和会话、策略意图及失败归属等。
- `testDebugUnitTest`、`lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`、`assembleRelease` 通过。
- Release APK 的 manifest `debuggable=false`；产物未签名，正式分发签名尚未配置。
- 独立审查发现的会话排队、失败覆盖、重装归属、未知隐藏阻塞暂停解除及 SAF 错误误判均已修复并回归。新目录持久化、损坏日志提示及更新中间状态也已补齐。

```text
构建 / 单元测试 / 基础实机测试 → 已通过
独立模拟器实际 DPM 调用       → 已通过
OPPO 完整管控验收            → 未通过：市场隐藏受保护、待机边界有约118秒延迟
```
