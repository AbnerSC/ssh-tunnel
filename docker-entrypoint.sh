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
#   DIRECT_IP       -> -direct-ip   直连 IP/网段，多个值用逗号或空格分隔（如 "192.168.1.10,10.0.0.0/8"）
#   DIRECT_DOMAIN   -> -direct-domain 直连域名（匹配主域名），多个值用逗号或空格分隔
#   DIRECT_CN       -> -direct-cn    中国域名全量直连（geosite:cn）；取 true 启用默认列表，
#                                  也可直接写 geosite 名（如 "cn,private"，逗号分隔）
#   JAVA_OPTS       -> 覆盖下面默认的 JVM 调优参数
# =====================================================================
set -euo pipefail

# JVM 调优：G1 GC + 堆按容器内存限额自适应。
# 持续高吞吐代理的短命 byte[]/Buffer 分配率达 100~250MB/s，堆过小会使 Young GC
# 频次激增，写积压缓冲跨代存活被晋升到老年代，老年代渐进填满 → G1 并发回收周期
# 持续空转 → 吞吐随运行时间逐渐衰减（实测：起步 100Mbps 缓慢降到 ~16Mbps 后稳定，
# 即 mutator 时间占比被 GC 压缩后的稳态）。因此按容器内存限额推导堆：
# 预留约 300MB 非堆开销（Metaspace/CodeCache/线程栈/直接缓冲），其余为堆，
# 下限 256MB、上限 1024MB（1G 限额容器可得 ~700MB 堆，足以支撑 100Mbps+ 持续吞吐）。
# --enable-native-access 抑制 vertx 可选 native 传输在 JDK 25 下的告警。
# 可通过环境变量 JAVA_OPTS 整体覆盖。

# 读取容器内存限额(MB)：cgroup v2 → v1 → 无限额返回 0
detect_mem_mb() {
	local v="max"
	if [ -f /sys/fs/cgroup/memory.max ]; then # cgroup v2
		v=$(cat /sys/fs/cgroup/memory.max 2>/dev/null || echo max)
	elif [ -f /sys/fs/cgroup/memory/memory.limit_in_bytes ]; then # cgroup v1
		v=$(cat /sys/fs/cgroup/memory/memory.limit_in_bytes 2>/dev/null || echo max)
	fi
	case "$v" in
	*[!0-9]*|"") echo 0; return ;; # max 或非法值 → 未设限额
	esac
	# cgroup v1 未设限额时给出接近 int64 上限的哨兵值，一并视为无限额
	if [ "$v" -gt 1099511627776 ]; then # 1TB
		echo 0
	else
		echo $((v / 1048576))
	fi
}

MEM_MB=$(detect_mem_mb)
if [ "$MEM_MB" -ge 600 ]; then
	# 限额低于 600MB 时扣完非堆开销所剩无几，退回保守默认值
	HEAP_MB=$((MEM_MB - 300))
	# 注意：set -e 下不能用 “[ test ] && 赋值”（测试失败时短路表达式返回非 0 会终止脚本）
	if [ "$HEAP_MB" -gt 1024 ]; then
		HEAP_MB=1024
	fi
	XMS_MB=$((HEAP_MB / 2))
else
	HEAP_MB=256
	XMS_MB=128
fi
DEFAULT_JAVA_OPTS="--enable-native-access=ALL-UNNAMED -XX:+UseCompactObjectHeaders -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -XX:+AlwaysPreTouch -Xms${XMS_MB}m -Xmx${HEAP_MB}m -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=64m"

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
# 直连规则：值原样透传，程序侧按逗号/空格拆分多个规则；
# 命中规则的请求由容器本地直接访问，不经过远程 SSH 代理
add_arg "${DIRECT_IP:-}"     "-direct-ip"
add_arg "${DIRECT_DOMAIN:-}" "-direct-domain"
# 中国域名全量直连：geosite 数据内置于镜像的 dlc.dat，取 true 走默认 cn 列表
add_arg "${DIRECT_CN:-}"     "-direct-cn"

# JAVA_OPTS 未设置时用默认调优参数；此处依赖单词拆分展开为多个 JVM 选项。
# APP_ARGS 用双引号数组展开，确保含空格/特殊字符的值（如密码）作为单个参数传入
# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-$DEFAULT_JAVA_OPTS} -jar app.jar "${APP_ARGS[@]}" "$@"
