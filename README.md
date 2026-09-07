# SSH隧道
通过SSH建立一个隧道

[![GitHub Stars](https://img.shields.io/github/stars/AbnerSC/ssh-tunnel?style=flat&label=Stars)](https://github.com/AbnerSC/ssh-tunnel/stargazers)
[![Docker Pulls](https://img.shields.io/docker/pulls/babyfly/ssh-tunnel?label=Docker%20Pulls)](https://hub.docker.com/r/babyfly/ssh-tunnel)

***GitHub***：[https://github.com/AbnerSC/ssh-tunnel.git](https://github.com/AbnerSC/ssh-tunnel.git)

### JDK 25

### 依赖
- [org.apache.sshd:sshd-core](https://github.com/apache/mina-sshd) 2.19.0
- [io.vertx:vertx-core](https://vertx.io/) 5.1.7
- [org.projectlombok:lombok](https://projectlombok.org/) 1.18.46

### 使用说明
1. 示例：`java -jar ssh-tunnel.jar -D 6666 -H 6667 -suser "socks_user" -spwd "socks_pwd" -server root@1.2.3.4 -p 22 -P "server_pwd"`
2. 本地代理模式（不配置 `-server` 即本机直接提供代理，不通过远程SSH代理）：`java -jar ssh-tunnel.jar -D 6666 -H 6667`
3. 参数说明：
- `-D 6666`：本地socks5监听端口
- `-H 6667`：本地http代理监听端口（支持 CONNECT 隧道与普通 HTTP 转发）
- `-suser "socks_user"`：代理账号，socks5 与 http 代理共用【可选参数】
- `-spwd "socks_pwd"`：代理密码，socks5 与 http 代理共用【可选参数】
- `-server root@1.2.3.4`：作为隧道的服务器地址和账号【可选参数，不配置则本机直接提供代理】
- `-p 22`：服务器SSH端口
- `-P "server_pwd"`：服务器SSH密码
- `-pool 8`：SSH连接池大小【可选参数，默认8】
- `-direct-ip "192.168.1.10,10.0.0.0/8"`：直连IP配置，支持单IP和网段(CIDR 如 `192.168.0.0/24`，也兼容点分掩码 `192.168.0.0/255.255.255.0`)，直连IP由本地直接访问，不经过远程SSH代理【可选参数，单个值内逗号分隔、也可重复传参】
- `-direct-domain "example.com,localhost"`：直连域名配置，支持匹配主域名(如 `example.com` 命中 `www.example.com`)，直连域名由本地直接访问，不经过远程SSH代理【可选参数，单个值内逗号分隔、也可重复传参】

> 说明：`-D` 与 `-H` 可同时启用，两者共用同一套账号密码与同一个 SSH 连接池。
> 配置账号密码后，http 代理采用 `Proxy-Authorization: Basic` 认证；未配置则无需认证。
> 不配置 `-server` 时为本地代理模式，所有请求均由本机直接转发；
> 配置 `-server` 后默认全部走远程 SSH 隧道，命中 `-direct-ip` / `-direct-domain` 规则的请求由本地直连。

### Docker 运行
```yaml
services:
  ssh-tunnel:
    image: babyfly/ssh-tunnel:latest
    container_name: ssh-tunnel
    hostname: linux
    environment:
      - TZ=Asia/Shanghai
      - SOCKS_PORT=6666
      - HTTP_PORT=6667
      - SOCKS_USER=socks_user
      - SOCKS_PASSWORD=socks_pwd
      - SSH_SERVER=root@1.2.3.4
      - SSH_PORT=2
      - SSH_PASSWORD=server_pwd
      - SSH_POOL=6
      - DIRECT_IP=192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,127.0.0.1
      - DIRECT_DOMAIN=localhost,mmxx.fun,05200809.xyz
    ports:
      - 16666:6666
      - 16667:6667
    volumes:
      - ./logs:/app/logs
    mem_limit: 256m
```

- 环境变量说明：

| 环境变量         | 对应参数         | 说明                                                                          |
|------------------|------------------|-------------------------------------------------------------------------------|
| `SOCKS_PORT`     | `-D`             | 本地socks5监听端口                                                            |
| `HTTP_PORT`      | `-H`             | 本地http代理监听端口                                                          |
| `SOCKS_USER`     | `-suser`         | 代理账号，socks5 与 http 代理共用（可选）                                     |
| `SOCKS_PASSWORD` | `-spwd`          | 代理密码，socks5 与 http 代理共用（可选）                                     |
| `SSH_SERVER`     | `-server`        | 隧道服务器地址与账号，如 `root@1.2.3.4`（可选）当为空时，默认本机直接提供代理 |
| `SSH_PORT`       | `-p`             | 服务器SSH端口（可选）,跟随`SSH_SERVER`一起配置                                |
| `SSH_PASSWORD`   | `-P`             | 服务器SSH密码（可选）,跟随`SSH_SERVER`一起配置                                |
| `SSH_POOL`       | `-pool`          | SSH 连接池大小（可选），建议不配置，默认配置已最优                            |
| `DIRECT_IP`      | `-direct-ip`     | 直连IP/网段（逗号或空格分隔多个值，可选）                                     |
| `DIRECT_DOMAIN`  | `-direct-domain` | 直连域名，匹配主域名（逗号或空格分隔，可选）                                  |
| `JAVA_OPTS`      | -                | 覆盖镜像内置的 JVM 调优参数（可选）                                           |

### 使用测试

1. 测试HTTP代理
```bash
# 未配置账号密码
curl -x http://127.0.0.1:6667 https://www.google.com

# 已配置账号密码
curl -x http://socks_user:socks_pwd@127.0.0.1:6667 https://www.google.com
```

### 功能特性
- [X] 通过远程SSH代理建立隧道
- [X] 支持SOCKS5代理
- [X] 支持HTTP代理
- [X] 本机直接提供代理，不通过远程SSH代理（不配置 `-server` 即为本地代理模式）
- [X] 增加直连IP配置(支持IP和网段)，直连IP由本地直接访问，不经过远程SSH代理（`-direct-ip`，支持 CIDR 与点分掩码）
- [X] 增加直连域名配置(支持匹配主域名)，直连域名由本地直接访问，不经过远程SSH代理（`-direct-domain`）
