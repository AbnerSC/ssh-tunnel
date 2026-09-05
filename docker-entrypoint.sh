#!/bin/bash
# =====================================================================
# ssh-tunnel 容器入口脚本
#
# 作用：把容器运行时的环境变量翻译成程序所需的命令行参数后再启动 JVM。
#       这样既可以用环境变量配置，也可以继续沿用原生命令行方式：
#         docker run 镜像 -D 6666 -server root@1.2.3.4 ...
#       命令行显式传入的参数（"$@"）会追加在最后，优先级最高。
#
# 支持的环境变量（均可选，未设置或为空则不传对应参数）：
#   SOCKS_PORT      -> -D      本地 socks5 监听端口
#   SOCKS_USER      -> -suser  socks5/http 代理账号
#   SOCKS_PASSWORD  -> -spwd   socks5/http 代理密码
#   HTTP_PORT       -> -H      本地 http 代理监听端口（认证复用 SOCKS_USER/SOCKS_PASSWORD）
#   SSH_SERVER      -> -server 隧道服务器地址与账号，如 root@1.2.3.4
#   SSH_PORT        -> -p      服务器 SSH 端口
#   SSH_PASSWORD    -> -P      服务器 SSH 密码
#   SSH_POOL        -> -pool   SSH 连接池大小（默认 5）
#   JAVA_OPTS       -> 覆盖下面默认的 JVM 调优参数
# =====================================================================
set -euo pipefail

# 低内存 JVM 调优：Serial GC + 固定小堆（socks5 转发为轻量 IO，无需大堆）；
# --enable-native-access 抑制 vertx 可选 native 传输在 JDK 25 下的告警。
# 可通过环境变量 JAVA_OPTS 整体覆盖。
DEFAULT_JAVA_OPTS="--enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders -XX:+UseSerialGC -Xms32m -Xmx128m -Xss512k -XX:MaxMetaspaceSize=64m -XX:ReservedCodeCacheSize=64m"

# 程序运行参数（用数组承载，避免密码等含空格/特殊字符时被错误分词）
APP_ARGS=()

# add_arg <环境变量值> <参数名>：值非空时追加 “<参数名> <值>”
add_arg() {
	if [ -n "$1" ]; then
		APP_ARGS+=("$2" "$1")
	fi
}

add_arg "${SOCKS_PORT:-}"     "-D"
add_arg "${SOCKS_USER:-}"     "-suser"
add_arg "${SOCKS_PASSWORD:-}" "-spwd"
add_arg "${HTTP_PORT:-}"      "-H"
add_arg "${SSH_SERVER:-}"     "-server"
add_arg "${SSH_PORT:-}"       "-p"
add_arg "${SSH_PASSWORD:-}"   "-P"
add_arg "${SSH_POOL:-}"       "-pool"

# JAVA_OPTS 未设置时用默认调优参数；此处依赖单词拆分展开为多个 JVM 选项。
# APP_ARGS 用双引号数组展开，确保含空格/特殊字符的值（如密码）作为单个参数传入
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-$DEFAULT_JAVA_OPTS} -jar app.jar "${APP_ARGS[@]}" "$@"
