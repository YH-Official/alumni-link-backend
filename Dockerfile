# 注意：这里故意不写 "# syntax=docker/dockerfile:1"。
# 那行会让 BuildKit 去 Docker Hub 拉 docker/dockerfile 前端镜像，国内基本拉不动（403），
# 本项目也没用到任何 BuildKit 专有语法，用内置前端即可。

# ─────────────────────────────────────────────────────────────
# 校友录后端（Spring Boot WebFlux + Ebean + Compose Desktop）
#
# 镜像里只跑 HTTP 服务：入口加 --no-gui，跳过 Compose 桌面界面，
# 只启动 Spring Boot（见 AlumniLink.main 里的 noGui 分支）。
#
# 构建（在仓库根目录）：
#   gradlew bootJar
#   docker build -t alumni-link:1.0.0-SNAPSHOT .
#
# deploy 任务会把 jar 上传到 Dockerfile 同级目录（<name>-fat.jar），
# 那种场景用 --build-arg 覆盖 jar 路径：
#   docker build --build-arg JAR_FILE=alumni-link-1.0.0-SNAPSHOT-fat.jar \
#                -t alumni-link:1.0.0-SNAPSHOT .
# ─────────────────────────────────────────────────────────────

# 基础镜像与 build.gradle.kts 里 lolosia.docker 的配置保持一致。
# 国内拉不动 Docker Hub 时，daemon 已配好的加速源会接管（见 /etc/docker/daemon.json）。
FROM eclipse-temurin:21-jre AS runtime

LABEL org.opencontainers.image.title="alumni-link" \
      org.opencontainers.image.description="校友录后端（Spring Boot + Ebean + MariaDB）" \
      org.opencontainers.image.version="1.0.0-SNAPSHOT"

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    SERVER_PORT=7031 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.awt.headless=true -Dfile.encoding=UTF-8"

# JDBC 里的 serverTimezone=GMT+8 要跟容器时区对上，否则时间字段会偏 8 小时
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo "$TZ" > /etc/timezone

# 非 root 运行。固定 uid/gid，宿主机挂载 work 卷时不会出现随机 uid 写不进去的问题。
# 注意用 10001 而不是 1000：eclipse-temurin 基于 Ubuntu，1000 已被内置的 ubuntu 用户占用。
RUN groupadd --gid 10001 app \
 && useradd --uid 10001 --gid 10001 --create-home --shell /bin/bash app

WORKDIR /app

# ① 应用 jar。默认取 gradle 产物路径；deploy 场景由 ARG 覆盖成同级文件名。
ARG JAR_FILE=build/libs/alumni-link-1.0.0-SNAPSHOT-fat.jar
COPY ${JAR_FILE} /app/app.jar

# ② 配置。avaje-config 从“工作目录”读 application.yaml，
#    所以必须落在 WORKDIR 上，不能只打在 jar 里（那样改配置就得重新构建镜像）。
COPY application.yaml /app/application.yaml

# ③ 运行期目录：work/files 放上传文件（/oss/show 从这里读），work/log 放日志
#    入口脚本负责在启动时按 DB_URL 改写数据库地址
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN mkdir -p /app/work/files /app/work/log \
 && chmod +x /app/docker-entrypoint.sh \
 && chown -R app:app /app
USER app

EXPOSE 7031

# 上传的文件会持续增长，单独声明成卷，容器重建不丢数据
VOLUME ["/app/work"]

# 探活：7031 端口能建立 TCP 连接即视为存活。
# actuator 是 developmentOnly，没有 /actuator/health 可用，这里只做端口探测。
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT}' || exit 1

# --no-gui 只启动 Spring Boot；DB_URL 用来指定数据库（见 docker-entrypoint.sh），
# APP_ARGS 留给部署时追加参数。例如：
#   -e DB_URL="jdbc:mariadb://db:3306/alumin-link?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8"
ENTRYPOINT ["/app/docker-entrypoint.sh"]