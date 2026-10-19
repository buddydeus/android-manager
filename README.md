# Android 应用管控

Android 11+ 单机 Device Owner 应用。需求见 [requirement](docs/requirement.md)，实现契约见 [proposal](docs/proposal.md)。

## 构建与调试

使用 JDK 21、Android SDK 36。设置 `ANDROID_HOME` 为本机 SDK 路径。

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb devices -l
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -W -n com.buddydeus.androidmanager/.ui.MainActivity
```

测试机为 OPPO PKD130 / Android 15 / API35 / 主用户0。设备连接及能力证据见 [device-validation](docs/device-validation.md)。ADB 37 的 libusb 后端曾断开，本次用 `ADB_LIBUSB=0 adb start-server` 及手机重开 USB 调试后恢复；此为当前会话环境，未修改用户 Shell 配置。

## 部署

安装后注册完整设备管理身份：

```sh
adb -s SERIAL shell dpm set-device-owner com.buddydeus.androidmanager/.platform.ManagerAdminReceiver
```

管理组件已实现。测试机当前被已有账户阻挡注册，须由管理员移除账户后重试。普通设备管理员不足以管控；注册要求以系统返回为准。不要自动删除账户、清除数据或恢复出厂设置。完整设备管理可能需要设备重置，操作前备份并由管理员确认。

## 当前进度

构建及实机启动通过；周时间规则、原子状态快照及分类日志已完成，密码文件、恢复、会话和登录暂停已实现；串行策略协调及崩溃归属处理已实现；28 项单元测试通过。Direct Boot、事件服务和单次边界闹钟已接入；Lint 与构建通过。初始化、登录、应用搜索/黑名单、周表编辑、总开关、改密与日志界面已实现。每环节验证后提交，连接通过不等同于系统管控验收通过。
