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
