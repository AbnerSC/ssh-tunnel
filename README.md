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
2. 参数说明：
- `-D 6666`：本地socks5监听端口
- `-H 6667`：本地http代理监听端口（支持 CONNECT 隧道与普通 HTTP 转发）
- `-suser "socks_user"`：代理账号，socks5 与 http 代理共用【可选参数】
- `-spwd "socks_pwd"`：代理密码，socks5 与 http 代理共用【可选参数】
- `-server root@1.2.3.4`：作为隧道的服务器地址和账号
- `-p 22`：服务器SSH端口
- `-P "server_pwd"`：服务器SSH密码
- `-pool 8`：SSH连接池大小【可选参数，默认8】

> 说明：`-D` 与 `-H` 可同时启用，两者共用同一套账号密码与同一个 SSH 连接池。
> 配置账号密码后，http 代理采用 `Proxy-Authorization: Basic` 认证；未配置则无需认证。

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
    ports:
      - 16666:6666
      - 16667:6667
    volumes:
      - ./logs:/app/logs
    mem_limit: 256m
```

- 环境变量说明：

| 环境变量         | 对应参数  | 说明                                            |
|------------------|-----------|-------------------------------------------------|
| `SOCKS_PORT`     | `-D`      | 本地socks5监听端口                              |
| `HTTP_PORT`      | `-H`      | 本地http代理监听端口                            |
| `SOCKS_USER`     | `-suser`  | 代理账号，socks5 与 http 代理共用（可选）       |
| `SOCKS_PASSWORD` | `-spwd`   | 代理密码，socks5 与 http 代理共用（可选）       |
| `SSH_SERVER`     | `-server` | 隧道服务器地址与账号，如 `root@1.2.3.4`（可选） |
| `SSH_PORT`       | `-p`      | 服务器SSH端口（可选）                           |
| `SSH_PASSWORD`   | `-P`      | 服务器SSH密码（可选）                           |
| `SSH_POOL`       | `-pool`   | SSH连接池大小（可选）                           |
| `JAVA_OPTS`      | -         | 覆盖镜像内置的 JVM 调优参数（可选）             |

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
- [ ] 本机直接提供代理，不通过远程SSH代理

### 待办
- [ ] 优化性能，降低内存使用