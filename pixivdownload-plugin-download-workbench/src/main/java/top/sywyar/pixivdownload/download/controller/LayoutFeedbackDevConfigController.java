package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.LayoutFeedbackIdentityDeriver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 布局偏好调查公开配置 {@code /pixiv-layout-feedback/public-config.js} 的服务端点。
 * <p>
 * 显式插件开发模式（JVM 属性 {@code pixivdownload.plugin-dev.enabled=true}）下，若软件运行根目录
 * {@code scripts/properties/posthog.properties} 包含四项有效公开值，则按打包生成器同一校验规则
 * 生成 {@code enabled=true} 的配置注入给前端（本地联调无需重新打包）；否则原样透传插件 jar 内
 * 构建生成的 public-config.js（生产构建由 {@code scripts/generate-layout-survey-public-config.ps1}
 * 在打包时注入，本端点只做逐字节等价回退，不改变生产行为）。
 * <p>
 * 四项值均为公开客户端配置，不是 Secret；本类不读取任何管理凭证。
 */
@Slf4j
@RestController
@RequestMapping("/pixiv-layout-feedback/public-config.js")
public class LayoutFeedbackDevConfigController {

    static final String DEV_MODE_PROPERTY = "pixivdownload.plugin-dev.enabled";
    static final String DEV_ROOT_PROPERTY = "pixivdownload.plugin-dev.root";

    private static final String POSTHOG_PROPERTIES = "scripts/properties/posthog.properties";
    private static final String BUNDLED_CONFIG = "static/pixiv-layout-feedback/public-config.js";
    private static final List<String> REQUIRED_KEYS = List.of(
            "pixiv.layout-survey.project-token",
            "pixiv.layout-survey.survey-id",
            "pixiv.layout-survey.api-host",
            "pixiv.layout-survey.ui-host");
    private static final MediaType JS_CONTENT_TYPE =
            MediaType.parseMediaType("application/javascript;charset=UTF-8");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 开发者模式从 posthog.properties 读出的有效公开配置。 */
    record DevConfig(String projectToken, String surveyId, String apiHost, String uiHost) {
    }

    @GetMapping
    public ResponseEntity<byte[]> publicConfig() {
        DevConfig injected = resolveDevConfig(devRoot());
        return injected != null ? ok(render(injected)) : bundledConfig();
    }

    /** 开发者模式运行根目录：优先 {@code pixivdownload.plugin-dev.root}，缺失时取进程工作目录。 */
    static Path devRoot() {
        String configured = System.getProperty(DEV_ROOT_PROPERTY);
        String root = configured == null || configured.isBlank() ? "" : configured.trim();
        return Path.of(root).toAbsolutePath();
    }

    /** 开发者模式下读取并校验运行根目录的 posthog.properties；无效或缺失返回 null（回退 bundled 配置）。 */
    static DevConfig resolveDevConfig(Path devRoot) {
        if (!Boolean.parseBoolean(System.getProperty(DEV_MODE_PROPERTY))) {
            return null;
        }
        Path file = devRoot.resolve(POSTHOG_PROPERTIES);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Map<String, String> values = parseProperties(file);
            if (values == null) {
                log.warn("layout survey dev config is invalid: {}", file);
                return null;
            }
            String projectToken = values.get(REQUIRED_KEYS.get(0));
            String surveyId = values.get(REQUIRED_KEYS.get(1));
            String apiHost = values.get(REQUIRED_KEYS.get(2));
            String uiHost = values.get(REQUIRED_KEYS.get(3));
            if (isPlaceholder(projectToken) || isPlaceholder(surveyId)
                    || isPlaceholder(apiHost) || isPlaceholder(uiHost)) {
                log.warn("layout survey dev config still contains placeholder values: {}", file);
                return null;
            }
            if (!validToken(projectToken) || !LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
                log.warn("layout survey dev config token or survey id is invalid: {}", file);
                return null;
            }
            String normalizedApiHost = validHost(apiHost);
            String normalizedUiHost = validHost(uiHost);
            if (normalizedApiHost == null || normalizedUiHost == null) {
                log.warn("layout survey dev config api-host or ui-host is invalid: {}", file);
                return null;
            }
            return new DevConfig(projectToken, surveyId, normalizedApiHost, normalizedUiHost);
        } catch (IOException e) {
            log.warn("Failed to read layout survey dev config: {}", file, e);
            return null;
        }
    }

    /**
     * 与 {@code scripts/generate-layout-survey-public-config.ps1} 相同语法的 properties 读取：
     * 仅接受四项键、每行首个 {@code =} 分隔、无重复、值非空、无续行；任何违规整份视为无效。
     */
    private static Map<String, String> parseProperties(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            if (line.endsWith("\\")) {
                return null;
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                return null;
            }
            String key = line.substring(0, separator).trim();
            String value = line.substring(separator + 1).trim();
            if (!REQUIRED_KEYS.contains(key) || values.containsKey(key) || value.isEmpty()) {
                return null;
            }
            values.put(key, value);
        }
        return values.keySet().containsAll(REQUIRED_KEYS) ? values : null;
    }

    private static boolean isPlaceholder(String value) {
        return value.equals("project-token") || value.equals("survey-id")
                || value.equals("api-host") || value.equals("ui-host");
    }

    private static boolean validToken(String value) {
        if (value == null || value.length() > 512) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch == 0x7F) {
                return false;
            }
        }
        return true;
    }

    /** 与打包生成器一致的 WebHost 校验：绝对 URL、https 或回环 http、无 userinfo / fragment；返回去尾斜杠形式。 */
    private static String validHost(String value) {
        if (value == null || value.length() > 1024) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!uri.isAbsolute() || uri.getUserInfo() != null || uri.getFragment() != null) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        boolean https = "https".equals(scheme);
        boolean loopbackHttp = "http".equals(scheme) && isLoopbackHost(host);
        if (!https && !loopbackHttp) {
            return null;
        }
        String normalized = uri.toString();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isLoopbackHost(String host) {
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("localhost") || lower.startsWith("127.") || lower.equals("::1");
    }

    static byte[] render(DevConfig config) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("enabled", true);
        fields.put("projectToken", config.projectToken());
        fields.put("surveyId", config.surveyId());
        fields.put("apiHost", config.apiHost());
        fields.put("uiHost", config.uiHost());
        try {
            String json = JSON.writeValueAsString(fields);
            return ("window.PixivLayoutFeedbackPublicConfig = Object.freeze(" + json + ");\n")
                    .getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("layout survey public config serialization failed", e);
        }
    }

    private static ResponseEntity<byte[]> bundledConfig() {
        try (InputStream in = LayoutFeedbackDevConfigController.class.getClassLoader()
                .getResourceAsStream(BUNDLED_CONFIG)) {
            if (in == null) {
                log.warn("Bundled layout survey public config is missing: {}", BUNDLED_CONFIG);
                return ResponseEntity.notFound().build();
            }
            return ok(in.readAllBytes());
        } catch (IOException e) {
            log.warn("Failed to read bundled layout survey public config: {}", BUNDLED_CONFIG, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private static ResponseEntity<byte[]> ok(byte[] body) {
        return ResponseEntity.ok()
                .contentType(JS_CONTENT_TYPE)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }
}
