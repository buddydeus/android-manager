# Android 应用管控

Android 11+ Device Owner 应用。既有本机需求见 [requirement](docs/requirement.md)，技术契约见 [proposal](docs/proposal.md)，设备验证见 [device-validation](docs/device-validation.md)。已批准的[远程管理扩展](docs/DR-20261105-remote-management/requirement.md)与[技术方案](docs/DR-20261105-remote-management/proposal.md)正在分环节实现，不表示已部署服务器或完成设备验收。

```text
管理入口 → 密码验证 → 黑名单 / 共用周时间表 / 总开关 / 设置 / 分类日志
系统事件 → 串行协调 → 保存意图 → 暂停与隐藏或解除 → 保存执行结果
```

支持系统应用搜索、24小时时分选择、全天快捷设置及跨午夜时段、独立密码文件及恢复、登录暂停、上海时区策略、开机恢复和精确边界闹钟。初始化完成前不施加策略；密码存储异常不停止已有后台管控。系统拒绝或归属不明会明确展示，不盲目解除外部限制。

界面采用白底蓝色的简洁布局，底部固定五项导航；规范与六页设计预览见 [界面规范](designs/specs/android-manager-minimal.md)。时间表以24小时细轨道辅助显示时段，保存按钮固定在导航上方；未保存草稿在切页、无关状态刷新及配置重建后保留，退出管理前提示丢弃。

## 构建与调试

远程服务位于 [server](server/README.md)，使用 Node 24。当前接入设备自动注册、管理员会话、Android 双向配置同步、WSS 推送与状态上报；使用记录补传与应用目录已接入；在线重启已接入；自更新已接入；网页仍在实施，当前不表示设备联调已通过。服务端验证：在 `server/` 执行 `npm ci && npm test && npm run typecheck && npm run build`。生产值通过环境注入，勿将接入凭据、管理员密码或签名私钥提交仓库。

远程接入在本机初始化完成后启动，独立于本机登录和管控总开关。受控 APK 构建前设置 `AMS_ENROLLMENT_KEY`，与服务端接入凭据一致；缺少凭据时显示待同步，本地管控继续。服务器固定为 `https://ams.opt.work`，仅 debug 构建可用 `AMS_TEST_ORIGIN` 指定隔离 HTTPS 测试原点。设置页显示连接与同步状态，不提供更换地址或解绑入口。

使用统计从解锁至息屏，离线暂存并补传，保留90天。设置页可选授权“使用情况访问权”补查系统有限历史；不上传其他应用的使用行为。缺失事件保留不完整标记，完整会话按上海日期分摊合计。最近系统启动时间为墙钟减系统运行时长的估算，不是应用启动时间。服务重建及时间跳变可能保留无法补齐的缺口，仍需设备验证。

使用 JDK 21、Android SDK 36；设置 `JAVA_HOME`、`ANDROID_HOME` 后执行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
adb devices -l
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -W -n com.buddydeus.androidmanager/.ui.MainActivity
```

调试 APK：`app/build/outputs/apk/debug/app-debug.apk`。Release 在未注入签名时输出未签名 APK，`debuggable=false`，仅供构建检查。正式分发执行 `./gradlew :app:assembleDistribution`，须设置 `AMS_ENROLLMENT_KEY`、`AMS_KEYSTORE`、`AMS_KEYSTORE_PASSWORD`、`AMS_KEY_ALIAS`、`AMS_KEY_PASSWORD`；签名必须与现装版本一致。用 `AMS_VERSION_CODE`（递增正整数）与 `AMS_VERSION_NAME` 设置发布版本，不自动生成生产签名。

界面夹具测试：`ANDROID_SERIAL=EMULATOR_SERIAL ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.buddydeus.androidmanager.ui.ManagerInterfaceTest`。仅在模拟器运行，使用独立Compose宿主、固定数据和回调，不连接密码存储或DPM；截图输出到测试设备的应用外部目录 `files/ui-validation/`。

设备基础测试：`ANDROID_SERIAL=SERIAL ./gradlew :app:connectedDebugAndroidTest`。测试中的完整协调策略用例只在明确指定 `policyTarget` 的独立模拟器运行。实机探针须显式传入 `physicalPolicyTarget`，拒绝受保护、已在黑名单或已有外部限制的应用；探针结束解除自身限制，不修改生产配置和时间策略。普通测试不自动选择应用施加策略。

后台边界调试探针位于 `androidTest`，不会打包进正式 APK。`BackgroundBoundaryProbeTest` 默认跳过，显式 arm 后退出测试进程，再观察实际系统闹钟；必须用同一 UUID 执行 cleanup 恢复空黑名单和周表。它仅接受空配置或已授权的临时测试夹具及匹配的配置版本，不读取密码，不覆盖管理员后续修改。

## 部署与使用

安装后通过电脑注册完整设备管理身份：

```sh
adb -s SERIAL shell dpm set-device-owner com.buddydeus.androidmanager/.platform.ManagerAdminReceiver
```

普通设备管理员权限不足。注册条件以系统返回为准；已有账户可能阻挡注册，须由管理员处理。不得自动删除账户、清除数据或恢复出厂设置。

进入应用后授权本地主存储密码目录，建议 `Documents/AndroidManager`；输入默认密码 `0000` 并设置至少六位数字的新密码。登录页采用密码输入框和登录按钮，不在输入框下展示默认密码说明。密码文件只保存加盐校验值；删除该文件可恢复默认密码，读取异常则通过恢复入口重新选择目录并设置密码。配置和日志独立保留。

时间表每天独立显示“设为全天”按钮，无需先打开当天开关；点击即可启用当天并填入 `00:00–23:59`，点击“保存周时间表”后生效；也可继续修改起止时间。结束分钟包含至第59秒（如 `23:59` 覆盖到 `23:59:59`，次日 `00:00` 解除），连续全天不会在午夜解除。已有周表也按此规则执行。

配置周表后，按界面提示授予“闹钟和提醒”权限，并返回应用核验边界登记。ColorOS 15 实机还须在系统应用详情 → 耗电管理中开启“允许应用后台行为”，确认显示“不优化”；还须在系统设置 → 应用 → 自启动中开启“应用管控”，确认允许开机及后台自启动。仅授予精确闹钟权限不足以排除 ROM 延迟或后台启动拦截。部署后应锁屏实测到点解除，不能只看授权状态。

设置页底部“清空密码”会删除当前 `password.json` 并退出管理；再次用 `0000` 登录后强制改密，管控规则继续保留。删除失败会显示原因，界面不会把失败当作清空成功。

总开关关闭后解除本软件施加的限制并保留设置和 Device Owner 身份。关闭不等于退管；系统应用能否限制、桌面和通知效果须在目标 ROM 验证。

## 验证状态

本轮63项单元测试、Lint、Debug和Release构建通过；既有13项模拟器界面测试尚待本轮重跑。使用记录与远程命令共7项模拟器存储测试通过（命令测试使用替代系统调用，不重启设备）；远程全链路联调仍待执行。简洁界面实际截图见 [界面验证](designs/validation/minimal/)。OPPO PKD130 / Android 15 已完成基础存储、应用目录、初始化入口与 SAF 查询验证，以及显式应用和后台边界探针。独立 Android 16 模拟器通过真实暂停、隐藏、边界解除和关闭状态清理测试，并完成密码目录初始化、恢复及重启应用登录检查。

OPPO 账户已由管理员移除，重新安装 APK 后 Device Owner 注册成功；实机小红书暂停、隐藏及恢复探针通过；系统市场可暂停，但 ColorOS 明确禁止隐藏，因此市场完整效果未通过。锁屏深度待机下可自动解除，但实测比边界晚约118秒，即时性不通过。通知展示等验收仍未完成，不能宣称此 ROM 完整兼容。临时黑名单和周表已清空，小红书恢复可用，总开关保留开启。实机空规则、开启状态的重启恢复通过；USB 连接通过不等于管控验收通过。

升级只接受本应用的单APK，服务端用固定36.0.0构建工具验签并提取版本，手机复核摘要、大小、签名及系统兼容性后自动安装。提交安装不代表成功；目标版本与签名实际安装后才成功，系统要求现场确认则失败。离线任务等待设备恢复，未知结果不重复提交。当前真实自动安装仍待隔离模拟器与实机联调。
