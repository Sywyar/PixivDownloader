# syntax=docker/dockerfile:1

# Build prerequisite:
#   scripts/assemble-plugin-distribution.ps1 -DefaultDownloader -OutputDir build/dist/default-downloader ...
#
# The image consumes a signed distribution directory. Do not copy Maven target plugin
# jars here: required plugins must include .sha256, .sig, provenance, and manifest
# sidecars so PluginRuntimeManager can verify them before PF4J loads them.
# Optional plugins, including gallery and duplicate in full-offline distributions, are copied
# with plugins/ as-is; only required download-workbench is enforced below.
FROM eclipse-temurin:17-jre

ARG PIXIVDOWNLOADER_DISTRIBUTION=build/dist/default-downloader

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ffmpeg curl; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY ${PIXIVDOWNLOADER_DISTRIBUTION}/PixivDownload-*.jar app.jar
COPY ${PIXIVDOWNLOADER_DISTRIBUTION}/plugins/ plugins/
COPY ${PIXIVDOWNLOADER_DISTRIBUTION}/plugins-manifest.json plugins-manifest.json
COPY ${PIXIVDOWNLOADER_DISTRIBUTION}/SHA256SUMS SHA256SUMS

RUN set -eux; \
    required_plugin="$(find plugins -maxdepth 1 -type f -name 'pixivdownload-plugin-download-workbench-*.jar' | head -n 1)"; \
    test -n "$required_plugin"; \
    test -f "$required_plugin.sha256"; \
    test -f "$required_plugin.sig"; \
    test -f "plugins/provenance/$(basename "$required_plugin").pixiv-plugin-provenance"; \
    test -f plugins-manifest.json; \
    test -f SHA256SUMS

# Default server.port (config.yaml can change it; update probes/compose ports together).
EXPOSE 6999

# Containers are headless; GuiLauncher automatically follows the --no-gui path.
ENTRYPOINT ["java", "-Djava.awt.headless=true", "-jar", "app.jar"]

# Health uses the public actuator endpoint allowed by AuthFilter.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD curl -fsS http://localhost:6999/actuator/health || exit 1
