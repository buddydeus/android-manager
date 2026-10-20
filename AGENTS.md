# 工作约定

- 中文说明简洁，流程优先文本图；独立检查前提和冲突。
- 不自动建分支；每个完成环节验证后提交，显式暂存本环节文件。
- 代码/脚本变化同步更新 README.md 和本文件，删除过时说明。
- 需求事实来源：docs/requirement.md；技术契约：docs/proposal.md；术语：GLOSSARY.md。
- 不擅自撤回用户改动；.gitignore 和实施计划属于任务前置资料。

## 验证命令

- JDK21、SDK36：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`。
- 设备测试：`ANDROID_SERIAL=SERIAL ./gradlew :app:connectedDebugAndroidTest`；所有 ADB 命令指定 `-s SERIAL`。
- 完整 DPM 协调测试在独立模拟器显式传入 `policyTarget`；实机单包探针须显式传入 `physicalPolicyTarget`，仅测试无既有管控的非黑名单包并在 finally 恢复；不得自动清除实机账户/数据、恢复出厂设置或退管。
- 不与 instrumentation 同时运行外部 uiautomator；两者会争用 UiAutomation。
- 验证证据更新 docs/device-validation.md；OPPO 已取得 DO；注册成功不能代替目标应用、通知、启动入口及开机等实际效果验证。

## 实现边界

- 单模块 app：domain 纯规则，data 存储，application 串行协调，platform 系统适配，ui Compose。
- 状态读取异常必须抛出并呈现，禁止返回空规则；周表采用上海时区及半开区间。
- 初始化前不施加策略；ManagerGraph 仅 credentials 懒加载 CE/SAF，开机路径仅用 DP。
- 密码固定 PBKDF2-SHA256/600000/16字节盐/256位；SAF 最多8192字节。新目录在密码写入回读成功后持久保存；认证仅进程内。
- SAF EXTRA_ERROR/LOADING 不能当作文件删除；新目录已有有效密码也须完成新密码写入后才授予会话。
- PolicyCoordinator 是配置和系统变更唯一串行入口：先持久意图、后调用、再保存回执。不得通过隐藏绕过暂停拒绝。
- 暂停和隐藏的未知归属独立保存；单步失败不可被后续成功覆盖，已知归属的另一维仍应解除。
- 重装按当前 instance 重建回执；EXTRA_REPLACING 中间状态保留策略，不视为真卸载。
- 事件监听受 DO 系统绑定；执行服务使用 systemExempted，执行结束即停止，不轮询。
- ManagerViewModel 统一授权；离开/锁屏清会话和日志。IO 提交时捕获认证 generation 并传入操作，不能仅丢弃过期 UI 回填。
- 密码存储、闹钟和服务启动失败须记录权限异常并展示；损坏日志行跳过时显示提示。
