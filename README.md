# SSH隧道
通过SSH建立一个隧道

### JDK 25

### 依赖
- [com.github.mwiede:jsch](https://github.com/mwiede/jsch) 2.28.7
- [io.vertx:vertx-core](https://vertx.io/) 5.1.7
- [org.projectlombok:lombok](https://projectlombok.org/) 1.18.46

### 构建
需将 `JAVA_HOME` 指向 JDK 25：

```powershell
$env:JAVA_HOME = "<JDK 25 安装路径>"
mvn -U clean package
```

产物：`ssh-copy-tunnel/target/ssh-tunnel.jar`

### 使用说明
1. 示例：`java -jar ssh-tunnel.jar -D 6666 -suser "socks_user" -spwd "socks_pwd" -server root@1.2.3.4 -p 22 -P "server_pwd"`
2. 参数说明：
- `-D 6666`：本地socks5监听端口
- `-suser "socks_user"`：socks5代理账号【可选参数】
- `-spwd "socks_pwd"`：socks5代理密码【可选参数】
- `-server root@1.2.3.4`：作为隧道的服务器地址和账号
- `-p 22`：服务器SSH端口
- `-P "server_pwd"`：服务器SSH密码
- `-pool 5`：SSH连接池大小【可选参数，默认5】

### Docker 运行
镜像入口脚本（`docker-entrypoint.sh`）会把环境变量翻译成对应的启动参数，因此既可用环境变量配置，也可继续沿用命令行参数方式（命令行参数追加在最后，优先级更高）。

1. 环境变量说明（均可选，未设置或为空则不传对应参数）：

| 环境变量 | 对应参数 | 说明 |
| --- | --- | --- |
| `SOCKS_PORT` | `-D` | 本地socks5监听端口 |
| `SOCKS_USER` | `-suser` | socks5代理账号 |
| `SOCKS_PASSWORD` | `-spwd` | socks5代理密码 |
| `SSH_SERVER` | `-server` | 隧道服务器地址与账号，如 `root@1.2.3.4` |
| `SSH_PORT` | `-p` | 服务器SSH端口 |
| `SSH_PASSWORD` | `-P` | 服务器SSH密码 |
| `SSH_POOL` | `-pool` | SSH连接池大小（默认5） |
| `JAVA_OPTS` | - | 覆盖镜像内置的 JVM 调优参数 |

2. 环境变量方式示例：

```bash
docker run -d --name ssh-tunnel -p 6666:6666 \
  -e SOCKS_PORT=6666 \
  -e SOCKS_USER=socks_user \
  -e SOCKS_PASSWORD=socks_pwd \
  -e SSH_SERVER=root@1.2.3.4 \
  -e SSH_PORT=22 \
  -e SSH_PASSWORD=server_pwd \
  ssh-tunnel:latest
```

3. 仍可用命令行参数方式（与原生 jar 一致）：

```bash
docker run -d --name ssh-tunnel -p 6666:6666 \
  ssh-tunnel:latest -D 6666 -suser socks_user -spwd socks_pwd -server root@1.2.3.4 -p 22 -P server_pwd
```