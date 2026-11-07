# AMS 源码部署包

将本目录完整复制到Linux服务器，运行 `./deploy.sh`。服务器需有Docker Engine与支持`up --wait`的Compose插件、可用网络；域名`ams.opt.work`指向本机，80/443端口可用。镜像固定linux/amd64，ARM服务器需支持相应模拟。

首次运行输入与App构建一致的接入凭据、管理员账号和至少12字符密码。也可通过环境变量 `AMS_ENROLLMENT_KEY`、`AMS_ADMIN_USER`、`AMS_ADMIN_PASSWORD`提供；不要把密码直接写进命令历史。接入凭据为32–256位字母/数字/下划线/短横线，保存到权限600的`.env`；管理员密码不落入部署文件。

```text
./deploy.sh → 检查Docker/配置 → 构建镜像 → 检查既有管理员
              → 必要时创建管理员 → 启动服务并等待容器健康
```

重复运行保留`.env`、管理员、SQLite、APK和证书卷；不执行`down -v`或清库。检查失败即停止，已运行服务不会被主动删除；不承诺自动回滚镜像或数据。健康检查仅证明内部服务就绪，不能代替公网DNS/TLS检查。Caddy仍需联网拉取镜像及签发证书。

Compose项目名固定`server`，沿用原仓库`server/`默认部署的卷。升级时将新包**覆盖合并**到原部署目录，保留`.env`，再运行脚本；不要删除旧目录后整体替换。首次迁移须核对`docker volume ls`和`docker compose -p server ps`，旧部署若使用其他项目名，应先按原部署说明备份和迁移，不要直接运行本脚本。

备份：`docker compose -p server exec -e AMS_BACKUP_DIR=/data/backups ams node dist/api/src/backup-cli.js`，然后 `docker compose -p server cp ams:/data/backups ./backups`。完整快照与`.env`、正式APK签名材料分别安全保管；恢复时停服务、保留原卷，将快照放入新卷再切换，不删除未知版本数据库。部署不修改APK内固定服务地址，不自动生成正式签名。
