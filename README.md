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