# 设备验证记录

日期：2026-10-19（上海）。测试机：OPPO PKD130，Android15 / API35，当前用户0，序列号尾号828E0。

| 检查 | 结果 |
| --- | --- |
| USB 枚举 | 通过，OPPO 设备 |
| ADB 授权 | 通过，状态 device |
| Shell 往返 | 通过，指定 serial 输出探针文本 |
| push/pull | 通过，/data/local/tmp 独立临时文件内容一致，已删除 |
| 现有设备管理身份 | no owners；尚未注册本应用 |
| APK 安装与启动 | 通过，安装成功，am start -W 返回 Status: ok，未见 AndroidRuntime 崩溃 |
| 系统市场暂停/隐藏 | 未验证 |
| 解锁前恢复、Doze、密码恢复 | 未验证 |

连接期间 libusb 出现读取错误并断开；使用本次服务临时环境 `ADB_LIBUSB=0`，手机重开 USB 调试并重新连接后恢复。未修改主机持久设置。
