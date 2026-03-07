# SSH隧道
通过SSH建立一个隧道

### JDK 1.8

### 使用说明
1. 示例：`java -jar ssh-copy.jar -D 6666 -suser "socks_user" -spwd "socks_pwd" -server root@206.119.119.53 -p 12265 -P "server_pwd"`
2. 参数说明：
- `-D 6666`：本地socks5监听端口
- `-suser "socks_user"`：socks5代理账号【可选参数】
- `-spwd "socks_pwd"`：socks5代理密码【可选参数】
- `-server root@206.119.119.53`：作为隧道的服务器地址和账号
- `-p 12265`：服务器SSH端口
- `-P "server_pwd"`：服务器SSH密码