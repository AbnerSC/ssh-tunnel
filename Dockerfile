# =====================================================================
# ssh-tunnel 多架构镜像构建文件（linux/amd64 & linux/arm64）
#
# 未使用 # syntax 指令：避免 buildkit 额外拉取 docker/dockerfile 前端镜像，
# 本文件仅用标准语法，buildkit 内置前端即可解析
#
# 多阶段构建：
#   - 编译阶段固定 --platform=$BUILDPLATFORM（构建机原生架构）运行 Maven，
#     依赖（jsch / vertx-core / lombok）均为纯 Java，maven-shade 打出的 fat-jar
#     与架构无关，无需按目标架构重复编译，避免 QEMU 模拟，多架构构建速度更快
#   - 运行阶段基于 eclipse-temurin:25-jdk-noble（官方多架构镜像），仅拷贝
#     maven-shade-plugin 产出的 ssh-tunnel.jar 为 app.jar；运行参数由命令行传入
#
# 本地手动构建多架构镜像：
#   docker buildx build --platform linux/amd64,linux/arm64 \
#     -t ssh-tunnel:latest --push .
# 国内环境构建可注入阿里云 Maven 镜像加速依赖下载：
#   --build-arg MAVEN_MIRROR=https://maven.aliyun.com/repository/public
# =====================================================================

# ---------- 阶段一：Maven 编译打包 ----------
ARG MAVEN_IMAGE=maven:3-eclipse-temurin-25
# 运行时基础镜像：必须声明在第一个 FROM 之前（全局 ARG），否则后续 FROM 无法引用
ARG BASE_IMAGE=eclipse-temurin:25-jdk-noble
FROM --platform=$BUILDPLATFORM ${MAVEN_IMAGE} AS build
WORKDIR /build

# 可选：注入 Maven 镜像仓库（阿里云自动构建等国内环境可加速依赖下载）
ARG MAVEN_MIRROR=
RUN if [ -n "$MAVEN_MIRROR" ]; then \
        mkdir -p /root/.m2 && \
        printf '<settings><mirrors><mirror><id>mirror</id><mirrorOf>central</mirrorOf><url>%s</url></mirror></mirrors></settings>' \
            "$MAVEN_MIRROR" > /root/.m2/settings.xml; \
    fi

# 先拷贝各模块 pom.xml 预取依赖，源码变更时可复用 Docker 层缓存
# （根 pom 为 pom 聚合工程，仅拷根 pom 会因缺少子模块而无法解析 reactor）
COPY pom.xml .
COPY ssh-common/pom.xml ./ssh-common/
COPY ssh-copy-tunnel/pom.xml ./ssh-copy-tunnel/
RUN mvn -B -q dependency:go-offline || true

COPY ssh-common ./ssh-common
COPY ssh-copy-tunnel ./ssh-copy-tunnel
RUN mvn -B -DskipTests clean package

# ---------- 阶段二：运行时镜像 ----------
FROM ${BASE_IMAGE}
WORKDIR /app

COPY --from=build /build/ssh-copy-tunnel/target/ssh-tunnel.jar app.jar

# 入口脚本：把容器运行时环境变量翻译成程序命令行参数后再启动 JVM。
# 低内存 JVM 调优（Serial GC + 固定小堆 + --enable-native-access 抑制 JDK 25 告警）
# 已内置于脚本，可用环境变量 JAVA_OPTS 整体覆盖；命令行显式传入的参数优先级最高
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
# Windows 下检出可能带 CRLF，构建时统一去除，避免 shebang 解析失败
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh && \
    chmod +x /usr/local/bin/docker-entrypoint.sh

# 运行时环境变量（均可选，未设置或为空则不传对应参数；详见 README）：
#   SOCKS_PORT     -> -D      本地 socks5 监听端口
#   SOCKS_USER     -> -suser  socks5/http 代理账号
#   SOCKS_PASSWORD -> -spwd   socks5/http 代理密码
#   HTTP_PORT      -> -H      本地 http 代理监听端口（认证复用 SOCKS_USER/SOCKS_PASSWORD）
#   SSH_SERVER     -> -server 隧道服务器地址与账号，如 root@1.2.3.4
#   SSH_PORT       -> -p      服务器 SSH 端口
#   SSH_PASSWORD   -> -P      服务器 SSH 密码
#   SSH_POOL       -> -pool   SSH 连接池大小（默认 5）
#   JAVA_OPTS      -> 覆盖默认 JVM 调优参数
ENV SOCKS_PORT= \
    SOCKS_USER= \
    SOCKS_PASSWORD= \
    HTTP_PORT= \
    SSH_SERVER= \
    SSH_PORT= \
    SSH_PASSWORD= \
    SSH_POOL= \
    JAVA_OPTS=

ENTRYPOINT ["docker-entrypoint.sh"]

# socks5 监听端口（README 示例 -D 6666），实际端口由启动参数/环境变量决定
EXPOSE 6666
