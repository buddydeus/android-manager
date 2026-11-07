# 现有 Nginx 承载 AMS

实际部署：`123.206.104.39`，应用目录`/opt/android-manager`，Compose项目`server`，域名`https://ams.opt.work`。这里只保存公开配置，接入凭据、管理员密码和证书私钥不入库。

```text
80 → HTTPS跳转（ACME路径保留）
443 Nginx → 127.0.0.1:18080 → AMS容器8080 → server_ams_data
```

将生成的源码包合并到原部署目录，保留`.env`及卷；把本目录`compose.nginx.yaml`复制到该目录。只启动ams，禁止在此部署直接执行通用`deploy.sh`或启动Caddy，以免占用80/443：

```sh
docker compose -p server -f compose.yaml -f compose.nginx.yaml build ams
docker compose -p server -f compose.yaml -f compose.nginx.yaml up -d --wait --wait-timeout 180 ams
```

镜像构建在本次腾讯云主机使用host网络；运行时仍为私有bridge网络，仅loopback18080暴露。该主机默认构建网络无法访问软件源，实际Dockerfile保留`Dockerfile.original`，apt源切换至`http://mirrors.tencentyun.com`。覆盖新版Dockerfile后须保留此环境调整或恢复可用的构建网络。未修改全局防火墙。

`ams.opt.work.conf`安装至`/etc/nginx/conf.d/`，需先取得对应证书，再执行`nginx -t && systemctl reload nginx`。首次证书用Certbot webroot `/var/www/ams-acme`签发；`certbot-renew.timer`已启用，部署钩子`/etc/letsencrypt/renewal-hooks/deploy/ams-nginx.sh`在续期成功后检查并重载Nginx。邮箱未配置，可由管理员后续设置。

WebSocket反代按[Nginx官方文档](https://nginx.org/en/docs/http/websocket.html)显式转发Upgrade/Connection；应用只信任固定Docker网关172.30.90.1，Nginx覆盖转发来源。若变更子网或代理拓扑，须同时修改信任地址。备份使用既有SQLite一致性备份命令，保留数据卷和独立的部署秘密；不要执行`down -v`。
