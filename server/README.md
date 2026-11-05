# AMS 远程服务

Node 24，Fastify + TypeScript + SQLite。需求与完整方案见 [远程管理扩展](../docs/DR-20261105-remote-management/requirement.md)。当前环节提供管理员会话、设备挑战注册和双向配置比较；推送、记录、安装包、网页及 Android 接入在后续环节完成。

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
