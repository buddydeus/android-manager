# AMS 远程服务

Node 24，Fastify + TypeScript + SQLite。需求与完整方案见 [远程管理扩展](../docs/DR-20261105-remote-management/requirement.md)。当前提供管理员会话、设备挑战注册、双向配置比较、WSS推送和设备状态上报，已接入Android端；提供使用记录/缺口的版本化补传、上海日期合计及分页应用目录；在线重启仅经当前WSS短时一次许可执行，不进入离线队列；已提供流式APK发布、固定工具校验、鉴权下载与升级任务/版本回执；网页在后续环节完成。

```text
设备接入凭据 → 一次性挑战及设备签名 → 独立设备 token → 配置同步
网页管理员 → 登录 Cookie + CSRF → 管理配置
```

## 验证与本地启动

```sh
npm ci
npm test
npm run typecheck
npm run build
```

先在受控终端设置以下环境变量，勿将真实值保存到仓库：

- `AMS_DATA_DIR`：数据库目录的绝对路径，须放本机磁盘。
- `AMS_ENROLLMENT_KEY`：至少32字符的随机接入凭据，和受控 APK 构建配置一致。
- `AMS_ADMIN_USER`、`AMS_ADMIN_PASSWORD`：初次管理员账号和至少12字符的密码，仅初始化时需要。
- `AMS_ORIGIN`：默认 `https://ams.opt.work`；开发及测试必须使用隔离的 HTTPS 原点。
- `PORT`：默认8080，生产须经 TLS 代理访问。

```sh
npm run admin
npm start
```

管理员只初始化一次，命令不覆盖已有管理员。启动和构建不自动创建账号。Cookie 仅经 HTTPS 使用，不能用裸 HTTP 网页登录验证代替 TLS 部署。`/health/live` 和 `/health/ready` 分别检查服务存活及数据库可读。

同步接口仅接受完整 `candidate`（变更ID、实际保存时间、时间质量、来源、三项配置）；相同时间保留服务器版本。重传旧候选会返回当前服务端快照。网页保存配置须携带稳定 UUID `x-mutation-id`；请求重试复用同一ID，新的保存操作才生成新ID。固定服务器地址不代替身份验证，注册凭据可从 APK 提取的边界仍适用。

设备WSS端点 `/api/v1/device/channel` 通过 Authorization 验证后建立当前连接；30秒心跳、90秒失联判离线，服务重启不恢复旧在线状态。推送只通知配置有变；设备通过HTTPS双向同步取得当前结果。`/device/status` 上报版本及带配置版本的执行摘要，不能把连接在线当作管控成功。

APK工具固定为 Android build-tools 36.0.0。部署路径默认 `/opt/android-sdk/build-tools/36.0.0`，本地验证可设置 `AMS_BUILD_TOOLS`。APK最大100MiB，只接收管理员同源上传，不接受下载URL；哈希与元数据由文件计算。实际工具集成测试：设置 `AMS_TEST_APK`（本应用已签名、versionCode>1）与 `AMS_TEST_BUILD_TOOLS` 后执行 `npm test`；未设置时该集成用例明确跳过。
