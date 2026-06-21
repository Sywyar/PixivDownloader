# syntax=docker/dockerfile:1

# ──────────────────────────────────────────────────────────────────────────
# builder：编译 fat-jar。需要 PowerShell（pwsh）以执行 generate-sources 阶段的
# build-userscript-bundle.ps1，生成合并版「Pixiv All-in-One.user.js」，与 GitHub
# 发布产物保持一致。pwsh 仅存在于本阶段，不进入最终运行镜像。
# ──────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS builder

# 安装 PowerShell（按基础镜像实际发行版动态选择 Microsoft apt 源）。
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends wget apt-transport-https ca-certificates; \
    . /etc/os-release; \
    wget -q "https://packages.microsoft.com/config/${ID}/${VERSION_ID}/packages-microsoft-prod.deb" -O /tmp/ms-prod.deb; \
    dpkg -i /tmp/ms-prod.deb; \
    rm -f /tmp/ms-prod.deb; \
    apt-get update; \
    apt-get install -y --no-install-recommends powershell; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# 先拷贝聚合 pom 与各子模块 pom 预热依赖缓存，提高后续重建命中率。
# 多模块 reactor 下 go-offline 需要全部子模块 pom 在场，否则会因子模块缺失而失败。
COPY pom.xml .
COPY pixivdownload-plugin-api/pom.xml pixivdownload-plugin-api/
COPY pixivdownload-core-api/pom.xml pixivdownload-core-api/
COPY pixivdownload-plugin-runtime/pom.xml pixivdownload-plugin-runtime/
COPY pixivdownload-app/pom.xml pixivdownload-app/
RUN mvn -B -q dependency:go-offline

# 拷贝源码与构建期资源（油猴脚本、打包脚本等）。
COPY . .

# 跳过测试以缩短镜像构建时间；userscript bundle 在 generate-sources 阶段由 pwsh 生成。
RUN mvn -B -DskipTests package

# ──────────────────────────────────────────────────────────────────────────
# runtime：仅含 JRE + ffmpeg（动图转换强依赖，不可省）+ curl（容器探针）。
# ──────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ffmpeg curl; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=builder /build/pixivdownload-app/target/PixivDownload-*.jar app.jar

# 默认 server.port（config.yaml 可改；如改端口需同步调整探针/compose 暴露端口）。
EXPOSE 6999

# 容器内无显示器：-Djava.awt.headless=true 强制无头，GuiLauncher 自动走 --no-gui 路径。
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "app.jar"]

# 探针走公开的 actuator health 端点（AuthFilter 已白名单放行，不受鉴权/维护窗口影响）。
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:6999/actuator/health || exit 1
