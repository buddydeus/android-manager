# 工作约定

- 中文说明简洁，流程优先文本图；独立检查前提和冲突。
- 不自动建分支；每个完成环节验证后提交，显式暂存本环节文件。
- 代码/脚本变化同步更新 README.md 和本文件，删除过时说明。
- 界面设计SSOT：designs/specs/android-manager-minimal.md（已确认）；预览与提示词必须配对，主题token集中于ManagerTheme，功能语义仍以需求和技术契约为准。
- 本机需求：docs/requirement.md；本机技术契约：docs/proposal.md；远程扩展按 docs/DR-20261105-remote-management/{requirement,proposal}.md，受影响规则以已批准扩展为准；术语：GLOSSARY.md。
- 不擅自撤回用户改动；.gitignore 和实施计划属于任务前置资料。

## 验证命令

- server 使用Node24、TypeScript6（vue-tsc兼容）与Vue3；在server/运行 `npm test && npm run typecheck && npm run build`，网页关键路径用 `npm run test:browser`；实际APK工具用例显式提供AMS_TEST_APK/AMS_TEST_BUILD_TOOLS。npm锁文件固定依赖；SQLite仅单实例本机卷，未知存储版本禁止清库恢复。凭据、签名和本地数据不入库。
- 远程扩展分环节实施；README 说明实际接入状态，不以 API 通过声称 Android 或实机能力完成。自动注册必须完成既有初始化；设备 token 与网页管理员会话隔离；配置只同步黑名单、周表和总开关。
- Android 用原子状态同时保存同步字段与待上传元数据；重连始终发送完整本机候选，包括已确认版本，以恢复旧服务快照；旧响应不得清除新修改。策略回执携带实际执行版本，不将旧成功标记为新配置结果。服务端在线判定用当前WSS会话及心跳，重启后重新建立。

- JDK21、SDK36：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`。
- ManagerInterfaceTest仅在模拟器使用独立Compose宿主、固定数据与回调验证UI，不访问ManagerGraph、SAF或DPM；不要用实机配置做界面夹具。
- 设备测试：`ANDROID_SERIAL=SERIAL ./gradlew :app:connectedDebugAndroidTest`；所有 ADB 命令指定 `-s SERIAL`。
- 完整 DPM 协调测试在独立模拟器显式传入 `policyTarget`；实机单包探针须显式传入 `physicalPolicyTarget`，仅测试无既有管控的非黑名单包并在 finally 恢复；不得自动清除实机账户/数据、恢复出厂设置或退管。
- 不与 instrumentation 同时运行外部 uiautomator；两者会争用 UiAutomation。UI dump失败时不得读取旧XML继续操作。
- BackgroundBoundaryProbeTest 仅接受显式 UUID、arm/cleanup 模式和配置版本；只处理空配置或既有授权临时夹具。arm 后退出 instrumentation 再观察真实后台闹钟，完成后 cleanup；清理必须复核版本和夹具，保留管理员后续修改。
- ColorOS 实测需同时授权精确闹钟、允许后台行为及开机/后台自启动；授权不代表准点，必须记录实际边界时间。
- 验证证据更新 docs/device-validation.md；OPPO 已取得 DO；注册成功不能代替目标应用、通知、启动入口及开机等实际效果验证。

## 实现边界

- 单模块 app：domain 纯规则，data 存储，application 串行协调，platform 系统适配，ui Compose。
- 状态读取异常必须抛出并呈现，禁止返回空规则；周表采用上海时区及半开区间，排他结束边界为所选结束分钟的下一分钟。
- 初始化前不施加策略；ManagerGraph 仅 credentials 懒加载 CE/SAF，开机路径仅用 DP。
- 密码固定 PBKDF2-SHA256/600000/16字节盐/256位；SAF 最多8192字节。新目录在密码写入回读成功后持久保存；认证仅进程内。
- SAF EXTRA_ERROR/LOADING 不能当作文件删除；新目录已有有效密码也须完成新密码写入后才授予会话。
- PolicyCoordinator 是配置和系统变更唯一串行入口：先持久意图、后调用、再保存回执。不得通过隐藏绕过暂停拒绝。
- 暂停和隐藏的未知归属独立保存；单步失败不可被后续成功覆盖，已知归属的另一维仍应解除。
- 重装按当前 instance 重建回执；EXTRA_REPLACING 中间状态保留策略，不视为真卸载。
- 初始化后的事件服务受 DO 系统绑定，远程连接不随管控总开关停用；心跳仅检查连接，不轮询应用启动或时间规则。执行服务使用 systemExempted，执行结束即停止。
- ManagerViewModel 统一授权；离开/锁屏清会话和日志。IO 提交时捕获认证 generation 并传入操作，不能仅丢弃过期 UI 回填。
- 密码存储、闹钟和服务启动失败须记录权限异常并展示；损坏日志行跳过时显示提示。

- 时间表每天独立显示全天按钮，不受当天开关控制；点击启用当天并将草稿设为00:00–23:59，保存后生效。结束分钟完整包含至第59秒；连续区间合并，整周连续限制不安排虚假边界。草稿用分钟整数并在管理页面层保留；切页和无关配置版本变化不得清空，返回概览保留，退出管理前提示丢弃。系统24小时时分选择器取消不修改草稿，随页面销毁释放，保留起止相等校验。
- 清空密码仅在当前会话/generation有效时通过SAF删除单个 password.json并复查缺失；不改管控配置或目录授权，删除尝试后清会话，失败不可报成功。禁止用实机管理员密码文件做自动化删除测试。

- 登录页密码输入框下不展示默认密码说明文字。

- 日志结果携带成功读取时的日期/分类/偏移；筛选不匹配时不把旧页显示为当前结果，会话清理同时清除查询标记。
- 使用统计只处理屏幕交互/解锁事件；DP SQLite 中先事务保存，再按 ID/版本补传，旧确认不得清除修正记录。缺端点不补造时长，历史只修复同启动内唯一匹配，空查询保留缺口；合计按上海自然日分摊，保留90天。历史查询下界不得早于首次开始监测；设备身份变化须事务重排保留期内记录，旧设备ACK不得清除。应用目录变化本身须触发同步，分页完整后才切换快照。
- 重启仅在当前WSS与boot身份匹配且短时一次许可有效时，经PolicyCoordinator串行入口执行；先持久ATTEMPTED再调用DPM，同一ID不重试。回执版本递增，旧ACK不清新状态；断线和调用返回不表示重启成功。SQLite远程存储v1→v2仅新增操作表，不清统计数据。
- 升级下载不持PolicyCoordinator锁；固定同源对象、大小/摘要/包名/minSdk/递增版本/相同签名均核对后才创建会话。sessionId与INSTALLING原子保存后commit；回调验证jobId/sessionId，实际版本与签名核验后才成功。未知结果不重复提交，现场确认视为无法自动安装。正式分发走assembleDistribution凭据与外部签名门禁。

- 网页采用script setup/类型化props和共享配置契约；仅同源cookie+CSRF，拒绝不可信HTML。后台刷新保留草稿，旧401不得清除新登录；配置重试复用变更ID。Docker为单实例linux/amd64、非root AMS和固定构建工具；备份使用SQLite一致性接口并核对快照引用APK，不单拷WAL主文件。

- 恢复旧服务器备份后，缺失任务或缺许可证据的回执须分项隔离；保留本机原始状态/结果，仅同版本停止补传，不阻塞其他同步，不重建许可或重复执行。网页须分别展示当前配置的策略结果和闹钟状态。
- RemoteValidationBootstrapTest只接受显式UUID、CA指纹和固定隔离HTTPS原点，在模拟器安装测试CA且不得修改初始化；真实初始化须通过现有UI/SAF。测试服务仅监听loopback，不使用生产凭据。

- `npm run build:server`在根目录或server/生成源码部署包server/out；输出只含构建所需文件，不打包秘密/数据/依赖。含.env或额外部署文件的旧out禁止覆盖。deploy.sh固定Compose项目server，保留.env及管理员/卷，不source配置、不清库，构建或状态检查失败立即停止；部署状态探针区分首次/既有管理员与存储错误。部署回归纳入server/tests/deployment.test.ts。

- ams.opt.work线上使用现有Nginx+Docker AMS，配置见server/deploy/nginx；只启动ams，禁用通用Caddy启动路径，保留server_ams_data和.env。应用仅绑定loopback18080，可信代理为固定bridge网关；host网络仅用于该主机镜像构建。证书由Certbot续期并重载Nginx，生产凭据/私钥不得入库。

- ManualVisibilityRecoveryTest仅用于用户明确授权的com.heytap.market恢复显示，不作为常规探针；须显式模式/UUID/physicalRecoveryTarget/expectedRevision，初始化+DEBUG+DO+管控关闭+未暂停后才在PolicyCoordinator锁内审计并单包setHidden(false)。不得扩为自动清除未知限制；执行后复核规则、系统可见性和远程连接。

- 网页黑名单只保留应用目录行内复选开关与统一保存，不维护第二份已选应用操作列表；增删均复用同一草稿与保存接口，受保护应用仍禁止新增选择。

- 使用记录表按字段与会话状态解释缺失：未记录开始/结束、进行中尚未息屏、缺端点无法计算；保持有效时间和零时长，不用结束时间补造开始，不把显示优化当作采集修复。不要改动其他模块仍需诚实显示的未知执行结果。

- UsageTracker固定四个系统保护广播使用RECEIVER_EXPORTED以接收独立UID的SystemUI；不得混入自定义/未保护动作。注册回归需校验标志及精确动作集合；实机验收用真实系统解锁/息屏，不用伪造广播或管理密码登录代替。进程中断的历史端点仍保留缺失。

- 使用明细在服务端分页前按startedAtMs倒序；缺开始时间置后，回退事件时间和ID倒序保证分页稳定，不能只排序当前网页页。
