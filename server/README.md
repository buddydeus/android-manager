# AMS 远程管理

Node 24.22.2、Fastify、TypeScript 6、Vue 3 与本机 SQLite。需求和技术契约见 [远程扩展](../docs/DR-20261105-remote-management/requirement.md)及[方案](../docs/DR-20261105-remote-management/proposal.md)。浏览器与设备使用独立身份；网页没有本机密码文件、远程 shell 或退管入口。

```text
浏览器 ─ HTTPS ─ Caddy ─ Fastify ─ 本机 SQLite / APK对象
                           │
                         HTTPS / WSS
                           │
                    初始化完成的 Android
```

提供设备自动注册、双向整份配置按修改时间比较、应用目录、90天使用会话/缺口和上海日期合计、APK发布/排队升级，以及仅在线的一次重启许可。网页保存、设备接收和策略执行分别展示。运行时间取系统启动估算；使用从解锁至息屏，缺端点不伪造时长。真实安装、重启和OEM后台效果的实际验收范围见 [设备证据](../docs/device-validation.md)。

## 开发与验证

```sh
npm ci
npm test
npm run typecheck
npm run build
```

`typecheck` 同时运行服务端 tsc 与 Vue 的 vue-tsc；Vite只负责打包。锁定TypeScript 6是因为当前vue-tsc不兼容TypeScript 7原生编译器接口。只批准固定版本better-sqlite3安装脚本；可选fsevents不执行脚本。

实际APK工具集成用例需显式指定本应用已签名且versionCode>1的APK；未提供时该用例会明确跳过：

```sh
AMS_TEST_APK=/absolute/path/update.apk \
AMS_TEST_BUILD_TOOLS=/absolute/path/Android/sdk/build-tools/36.0.0 npm test
npx playwright install chromium
AMS_TEST_APK=/absolute/path/update.apk \
AMS_TEST_BUILD_TOOLS=/absolute/path/Android/sdk/build-tools/36.0.0 npm run test:browser
```

浏览器测试启动独立回环HTTPS服务，使用临时数据库、临时证书与随机账号，不读取生产数据。测试设备是协议夹具，不能证明Android系统能力。桌面和窄屏截图见 [网页验证](../designs/validation/remote-console/README.md)。

## Linux Docker 部署

固定外网地址为 `https://ams.opt.work`。先将DNS指向服务器并放通80/443；本期使用linux/amd64镜像（ARM主机须支持Docker对应平台模拟）。Docker官方镜像通过其AWS公开分发源获取，Node与Caddy均固定摘要。Android build-tools固定36.0.0，下载校验SHA-256。

在本目录生成独立部署凭据，勿提交该文件：

```sh
umask 077
printf 'AMS_ENROLLMENT_KEY=%s\n' "$(openssl rand -hex 32)" > .env
docker compose build
```

正式APK构建环境必须设置与 `.env` 一致的 `AMS_ENROLLMENT_KEY`。App不允许更换服务器地址或本机解绑。APK中共享接入凭据可被提取，不能充当硬件认证；正常连接使用每台设备的独立token，服务端只存摘要。

创建唯一管理员（密码至少12字符，不等于手机管理密码）：

```sh
read -r -p '管理员账号: ' AMS_ADMIN_USER
read -r -s -p '管理员密码: ' AMS_ADMIN_PASSWORD
export AMS_ADMIN_USER AMS_ADMIN_PASSWORD
docker compose run --rm -e AMS_ADMIN_USER -e AMS_ADMIN_PASSWORD ams node dist/api/src/admin-cli.js
unset AMS_ADMIN_USER AMS_ADMIN_PASSWORD
docker compose up -d
docker compose ps
```

重复初始化管理员会拒绝，不覆盖现有账号。Caddy自动提供HTTPS与WSS反向代理；AMS容器不对公网直接开放8080，应用以UID1000运行。私有网络为 `172.30.90.0/24`，只信任Caddy的 `172.30.90.3/32` 转发地址；若与现有网络冲突，须同时调整compose网络、Caddy地址与AMS_TRUST_PROXY。生产不要使用浏览器测试服务。

部署材料、注册凭据、签名私钥及本地数据库均不得提交Git。网页会话为Secure/HttpOnly/SameSite=Strict cookie，空闲30分钟、最长12小时；写操作校验同源和CSRF。客户端包不包含管理员或设备token。

## APK发布与正式分发

管理员在网页上传单APK，最大100MiB。服务端通过固定 `apksigner verify` 和 `aapt2 dump badging` 校验包名、版本、minSdk及证书集合；不接受任意下载URL。对象按SHA-256寻址，只向已分配该版本的鉴权设备下载。

手机再次检查摘要、大小、包名、兼容性、递增版本与相同签名。只有现装版本和签名与目标一致才报告成功；系统要求现场确认视为无法自动安装，未知结果不重复commit。离线任务等待重连。首版不支持签名轮换、降级或拆分安装包集。

根目录执行 `./gradlew :app:assembleDistribution`。必需外部环境值：`AMS_ENROLLMENT_KEY`、`AMS_KEYSTORE`、`AMS_KEYSTORE_PASSWORD`、`AMS_KEY_ALIAS`、`AMS_KEY_PASSWORD`；版本用 `AMS_VERSION_CODE` 和 `AMS_VERSION_NAME`。签名须匹配现装版本，不自动生成生产身份。普通 `assembleRelease` 可产生仅供检查的未签名包，不能用于正式分发。

## 状态与命令

配置只包含黑名单、周时间表和总开关。首次接入先上传本机基线；同一时间保留服务器版本，离线重启后的估算时钟存在精度限制。网页草稿不会因后台刷新被静默覆盖；响应丢失重试沿用同一变更编号。

重启仅允许当前WSS会话且心跳有效的设备。网页二次确认后，手机在同一连接请求10秒内的一次许可，持久保存ATTEMPTED后才调用DPM。断线不排队、不重放；“已观察到后续启动”不证明该命令是重启的唯一原因。服务重启后重新建立在线会话，不沿用数据库里的上次在线状态。

## 存储与备份

SQLite WAL只用单实例、本机持久卷 `ams_data`，禁止水平扩容或放在网络共享盘。未知存储版本或损坏会报错停止，不清库恢复。APK对象与数据库一起保留；不要单独复制运行中主数据库文件。

一致性备份命令使用SQLite备份接口，按快照引用复制并核对每个不可变APK：

```sh
docker compose exec -e AMS_BACKUP_DIR=/data/backups ams node dist/api/src/backup-cli.js
docker compose cp ams:/data/backups ./backups
```

转存到独立受保护介质，并单独安全备份部署 `.env` 与签名材料。备份含账号校验值和设备凭据摘要，勿公开。恢复时停止服务，将完整快照（数据库、releases目录、backup.json）放入**新建本机卷**并确保UID1000可读写，保留原卷，完成健康与登录检查后切换；部署秘密须配套恢复。设备重连会重新提交本机完整配置候选；新设备身份会重新上传保留期内统计及目录。旧重启命令没有内存执行许可，不会重放。恢复后缺失任务，或重启回执缺少服务端许可证据时，回执接口分项返回`orphaned`（id/version），其余正常项返回`accepted`；手机保留原始操作记录并标记不可核验，匹配版本后停止补传，继续其他同步。不要删除未知版本的数据库来“修复”启动。

隔离Android联调入口为`tests/android-server.ts`，只监听127.0.0.1:8443，须显式提供权限600的`AMS_ANDROID_FIXTURE` JSON（随机fixtureId/enrollmentKey/adminPassword）及同目录tls/server.key、tls/server.pem；数据仅写入该目录的server-data。该入口不进入运行镜像。Android测试CA仅debug可用，须通过显式UUID和指纹夹具安装；不得将测试地址、CA、凭据或数据库用于生产。实际验收范围见[设备验证](../docs/device-validation.md)。
