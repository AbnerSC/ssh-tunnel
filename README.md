# SSH隧道
通过SSH建立一个隧道

### JDK 1.8

### 使用说明
1. `java -jar ssh-copy.jar -D 6666 -server root@206.119.119.53 -p 12265 -P "password"`
2. 参数说明：
- `-D 6666`：本地socks5监听端口
- `-server root@206.119.119.53`：作为隧道的服务器地址和账号
- `-p 12265`：服务器SSH端口
- `-P "password"`：服务器SSH密码