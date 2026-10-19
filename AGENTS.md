# 工作约定

- 中文说明简洁，结构/流程优先文本图；独立检查前提和冲突。
- 不自动建分支；每个完成环节验证后提交，显式暂存本环节文件。
- 代码/脚本变化同步更新 README.md 和本文件，删除过时说明。
- 需求事实来源：docs/requirement.md；技术契约：docs/proposal.md；术语：GLOSSARY.md。
- 构建：JDK21、SDK36；`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- 设备命令必须 `adb -s SERIAL`；不得自动清除账户/数据、恢复出厂设置或退管。
- 现有用户暂存改动不得擅自撤回；.gitignore 和实施计划属于本任务前置资料。
- 单模块 app；domain 纯规则，data 存储，application 串行协调，platform 系统适配，ui Compose。
- 初始化前不施加管控；后台仅依赖 DP 存储，不读取 CE 密码。
- 已完成规则、状态格式及日志。状态读取异常必须抛出并呈现，禁止返回空规则；时段合并采用上海时区和半开区间。

- 密码格式固定 PBKDF2-SHA256/600000/16字节盐/256位；SAF 读入最多8192字节。新目录只在密码写后回读成功时持久保存；认证仅进程内。

- 已实现 AppCatalog、动态保护与 DPM 适配；不得通过隐藏绕过暂停拒绝。当前实机 DO 注册因账户被拒绝，不得写成管控能力通过。

- PolicyCoordinator 是配置和系统变更唯一串行入口：先持久意图、后调用、再保存回执；PREPARED 归属不明不得盲目解除。

- ManagerGraph 只在 credentials 懒加载时创建 CE/SAF；开机路径禁止触碰。事件监听受 DO 系统绑定，执行服务使用 systemExempted，结束即停止。

- 管理界面通过 ManagerViewModel 统一授权；离开/锁屏清会话及日志，异步结果以 generation 拒绝过期回填。目录选择只用于未登录初始化/恢复。

- 密码存储异常、闹钟能力不足及执行服务启动失败须进入权限异常日志；UI 展示同一错误，不能只保留“正在应用”。
