package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载工作台布局偏好调查（PostHog API Survey）的静态资源契约守卫：
 * 页面加载顺序 / 唯一性、i18n 键集合、资源归属、公开配置与 vendor SDK 的
 * 供应链一致性（精确版本单一声明、许可证 / integrity 元数据在场、无未固定 CDN）。
 * 运行态行为由 {@code src/test/js/pixiv-layout-feedback.test.js} 通过真实脚本执行验证，
 * 配置生成器行为由 {@code src/test/js/layout-survey-config-generator.test.js} 验证。
 */
@DisplayName("下载工作台布局偏好调查静态契约守卫")
class LayoutSurveyContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String BATCH_HTML = STATIC_ROOT + "pixiv-batch.html";
    private static final String BATCH_ALT_HTML = STATIC_ROOT + "pixiv-batch-alt.html";
    private static final String SURVEY_CSS = STATIC_ROOT + "pixiv-layout-feedback/pixiv-layout-feedback.css";
    private static final String SURVEY_JS = STATIC_ROOT + "pixiv-layout-feedback/pixiv-layout-feedback.js";
    private static final String PUBLIC_CONFIG_JS = STATIC_ROOT + "pixiv-layout-feedback/public-config.js";
    private static final String I18N_ZH = "i18n/web/layout-feedback.properties";
    private static final String I18N_EN = "i18n/web/layout-feedback_en.properties";
    private static final String VENDOR_DIR = STATIC_ROOT + "vendor/posthog-js/";
    private static final String LICENSES_DIR = "META-INF/licenses/posthog-js/";
    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script\\s+[^>]*src=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_TAG = Pattern.compile(
            "<link\\s+[^>]*href=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE);

    private static String read(String resource) throws IOException {
        try (InputStream in = LayoutSurveyContractTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new NoSuchFileException(resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> scriptSources(String html) {
        List<String> sources = new ArrayList<>();
        Matcher matcher = SCRIPT_SRC.matcher(html);
        while (matcher.find()) {
            sources.add(matcher.group(1));
        }
        return sources;
    }

    private static List<String> linkHrefs(String html) {
        List<String> hrefs = new ArrayList<>();
        Matcher matcher = LINK_TAG.matcher(html);
        while (matcher.find()) {
            hrefs.add(matcher.group(1));
        }
        return hrefs;
    }

    private static int countOccurrences(String source, String token) {
        int count = 0;
        int from = 0;
        while ((from = source.indexOf(token, from)) >= 0) {
            count++;
            from += token.length();
        }
        return count;
    }

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("scripts"))
                    && Files.isDirectory(current.resolve("pixivdownload-app"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("无法定位仓库根目录");
    }

    private static Path pluginResourcesRoot() {
        Path workingDirectory = Path.of("").toAbsolutePath().normalize();
        Path direct = workingDirectory.resolve("src/main/resources");
        if (Files.isDirectory(direct)) {
            return direct;
        }
        return workingDirectory.resolve("pixivdownload-plugin-download-workbench/src/main/resources");
    }

    private static Set<String> i18nKeys(String resource) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(read(resource)));
        return new LinkedHashSet<>(properties.stringPropertyNames());
    }

    @Test
    @DisplayName("中英文隐私文案与真实身份模式一致：scoped 调查标识、multi 浏览器匿名、raw identity 不发送")
    void privacyCopyMatchesIdentityModel() throws IOException {
        String zh = read(I18N_ZH);
        String en = read(I18N_EN);
        String js = read(SURVEY_JS);

        // 中文明确「匿名调查标识」（不再笼统声称 solo 为匿名浏览器标识）
        assertThat(zh).contains("匿名调查标识");
        assertThat(zh).contains("单人模式");
        assertThat(zh).contains("随机安装身份");
        assertThat(zh).contains("当前调查 ID");
        assertThat(zh).contains("多人模式");
        assertThat(zh).contains("匿名浏览器标识");
        assertThat(zh).contains("原始安装身份");

        // 英文语义一致
        assertThat(en).contains("anonymous survey identifier");
        assertThat(en).contains("single-user mode");
        assertThat(en).contains("installation identity");
        assertThat(en).contains("survey id");
        assertThat(en).contains("multi-user mode");
        assertThat(en).contains("anonymous browser identifier");
        assertThat(en).contains("raw installation identity");

        // JS 弹窗 fallback 与中文 i18n 语义一致
        assertThat(js).contains("匿名调查标识");
        assertThat(js).contains("原始安装身份");
        assertThat(js).doesNotContain("匿名浏览器标识；");
    }

    @Test
    @DisplayName("CHANGELOG 调查条目与实际身份模型一致")
    void changelogMatchesIdentityModel() throws IOException {
        Path repoRoot = repoRoot();
        String changelog = Files.readString(repoRoot.resolve("CHANGELOG.md"), StandardCharsets.UTF_8);
        assertThat(changelog).contains("匿名调查标识");
        assertThat(changelog).contains("随机安装身份与当前调查 ID 单向派生");
        assertThat(changelog).contains("匿名浏览器标识");
        assertThat(changelog).contains("原始安装身份");
        assertThat(changelog).doesNotContain("按安装身份去重");
    }

    @Test
    @DisplayName("调查脚本不再使用 distinct_id 初始化配置，改用 bootstrap.distinctID")
    void sdkIdentityUsesBootstrapDistinctId() throws IOException {
        String js = read(SURVEY_JS);
        assertThat(js).contains("bootstrap.distinctID");
        assertThat(js).contains("isIdentifiedID: false");
        assertThat(js).contains("get_distinct_id");
        assertThat(js).doesNotContain("sdkConfig.distinct_id =");
        assertThat(js).doesNotContain("posthog.identify(");
        assertThat(js).doesNotContain("posthog.reset(");
        assertThat(js).doesNotContain("opt_out_capturing(");
    }

    @Test
    @DisplayName("两个页面恰好加载一次调查 CSS / 公开配置 / 业务脚本，且配置先于业务脚本")
    void pagesLoadSurveyAssetsExactlyOnceInOrder() throws IOException {
        for (String htmlResource : List.of(BATCH_HTML, BATCH_ALT_HTML)) {
            String html = read(htmlResource);
            String css = "/pixiv-layout-feedback/pixiv-layout-feedback.css";
            String config = "/pixiv-layout-feedback/public-config.js";
            String script = "/pixiv-layout-feedback/pixiv-layout-feedback.js";

            assertThat(countOccurrences(html, css)).as(htmlResource + " 调查 CSS 恰好一次").isEqualTo(1);
            assertThat(countOccurrences(html, config)).as(htmlResource + " 公开配置恰好一次").isEqualTo(1);
            assertThat(countOccurrences(html, script)).as(htmlResource + " 调查业务脚本恰好一次").isEqualTo(1);

            List<String> scripts = scriptSources(html);
            assertThat(scripts.indexOf(config))
                    .as(htmlResource + " 公开配置必须加载")
                    .isGreaterThanOrEqualTo(0);
            assertThat(scripts.indexOf(script))
                    .as(htmlResource + " 调查业务脚本必须加载")
                    .isGreaterThan(scripts.indexOf(config))
                    .describedAs("公开配置必须在调查业务脚本之前加载");
        }
    }

    @Test
    @DisplayName("两个页面不包含重复的调查 DOM，业务资源属于 download-workbench 插件")
    void pagesHaveNoDuplicateSurveyDomAndResourcesBelongToPlugin() throws IOException {
        for (String htmlResource : List.of(BATCH_HTML, BATCH_ALT_HTML)) {
            String html = read(htmlResource);
            assertThat(html).as(htmlResource + " 不应内联调查 DOM")
                    .doesNotContain("plf-backdrop")
                    .doesNotContain("plf-dialog")
                    .doesNotContain("data-plf-layout")
                    .doesNotContain("data-plf-action");
        }
        // 资源按模块归属：全部位于 download-workbench 插件的 src/main/resources 下
        Path resources = pluginResourcesRoot();
        assertThat(resources.resolve("static/pixiv-layout-feedback")).isDirectory();
        assertThat(resources.resolve("static/vendor/posthog-js")).isDirectory();
        assertThat(resources.resolve("i18n/web/layout-feedback.properties")).isRegularFile();
        assertThat(resources.resolve("i18n/web/layout-feedback_en.properties")).isRegularFile();
    }

    @Test
    @DisplayName("中英文调查文案键集合一致且非空")
    void i18nKeySetsMatch() throws IOException {
        Set<String> zh = i18nKeys(I18N_ZH);
        Set<String> en = i18nKeys(I18N_EN);
        assertThat(zh).as("中文文案键集合").isNotEmpty();
        assertThat(en).as("英文文案键集合").isNotEmpty();
        assertThat(zh).as("中英文键集合一致").isEqualTo(en);
        for (String key : zh) {
            assertThat(read(I18N_ZH)).as("中文值非空: " + key).contains(key + "=");
            assertThat(read(I18N_EN)).as("英文值非空: " + key).contains(key + "=");
        }
        assertThat(zh).contains("layout-feedback.title")
                .contains("layout-feedback.description")
                .contains("layout-feedback.option-landscape")
                .contains("layout-feedback.option-portrait")
                .contains("layout-feedback.option-alt")
                .contains("layout-feedback.current-layout")
                .contains("layout-feedback.suggestion-label")
                .contains("layout-feedback.suggestion-placeholder")
                .contains("layout-feedback.suggestion-counter")
                .contains("layout-feedback.privacy")
                .contains("layout-feedback.snooze")
                .contains("layout-feedback.never")
                .contains("layout-feedback.submit")
                .contains("layout-feedback.submitting")
                .contains("layout-feedback.submit-success")
                .contains("layout-feedback.submit-failed")
                .contains("layout-feedback.error-required")
                .contains("layout-feedback.survey-unavailable")
                .contains("layout-feedback.close");
    }

    @Test
    @DisplayName("公开配置模板不含任何管理密钥字段，源码默认 enabled=false")
    void publicConfigTemplateIsPublicAndDisabledByDefault() throws IOException {
        // 读源码模板（target/classes 可能被本地 -D 注入覆盖，源码默认必须是 disabled）
        String config = Files.readString(
                pluginResourcesRoot().resolve("static/pixiv-layout-feedback/public-config.js"),
                StandardCharsets.UTF_8);
        assertThat(config)
                .contains("window.PixivLayoutFeedbackPublicConfig = Object.freeze({")
                .contains("enabled: false")
                .contains("projectToken: \"\"")
                .contains("surveyId: \"\"")
                .contains("apiHost: \"\"")
                .contains("uiHost: \"\"")
                .doesNotContain("personalApiKey")
                .doesNotContain("serviceAccountToken")
                .doesNotContain("personal_api_key")
                .doesNotContain("service_account_token")
                .doesNotContain("apiKey:");
        assertThat(config).as("必须注明公开配置不是 Secret").contains("PUBLIC client configuration");
    }

    @Test
    @DisplayName("宿主不新增 PostHog 配置、不新增后端配置接口、不硬编码真实值")
    void hostHasNoPostHogConfigOrBackendEndpoint() throws IOException {
        Path repoRoot = repoRoot();
        Path appJava = repoRoot.resolve("pixivdownload-app/src/main/java");
        Path appResources = repoRoot.resolve("pixivdownload-app/src/main/resources");

        assertThat(readAppTemplate(repoRoot))
                .as("DefaultConfigTemplate 不得新增 PostHog 项")
                .doesNotContain("posthog")
                .doesNotContain("layout-survey")
                .doesNotContain("layoutSurvey");
        assertThat(readApplicationProperties(appResources))
                .as("application.properties 不得硬编码真实调查值")
                .doesNotContain("posthog")
                .doesNotContain("pixiv.layout-survey");
        assertThat(findIn(appJava, "layout-feedback-config"))
                .as("不得新增 /api/app/layout-feedback-config 后端接口")
                .isEmpty();
        assertThat(findIn(appJava, "LayoutSurvey"))
                .as("不得新增 LayoutSurvey 后端配置类")
                .isEmpty();
        assertThat(findIn(appJava, "pixiv.layout-survey"))
                .as("宿主代码不得读取 pixiv.layout-survey 属性")
                .isEmpty();
    }

    private static String readAppTemplate(Path repoRoot) throws IOException {
        return Files.readString(repoRoot.resolve(
                "pixivdownload-app/src/main/java/top/sywyar/pixivdownload/config/DefaultConfigTemplate.java"),
                StandardCharsets.UTF_8);
    }

    private static String readApplicationProperties(Path appResources) throws IOException {
        Path file = appResources.resolve("application.properties");
        if (!Files.isRegularFile(file)) {
            return "";
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static List<Path> findIn(Path root, String needle) throws IOException {
        List<Path> hits = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            return hits;
        }
        try (var stream = Files.walk(root)) {
            stream.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8).contains(needle);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(hits::add);
        }
        return hits;
    }

    private static Path pluginModuleRoot() {
        return pluginResourcesRoot().getParent().getParent().getParent();
    }

    @Test
    @DisplayName("posthog-js 精确版本单一声明，vendor 路径 / 许可证 / integrity 一致")
    void posthogJsVersionIsPinnedAndVendoredConsistently() throws IOException {
        Path pom = pluginModuleRoot().resolve("pom.xml");
        String pomText = Files.readString(pom, StandardCharsets.UTF_8);
        Matcher versionMatcher = Pattern.compile(
                "<posthog-js\\.version>([^<]+)</posthog-js\\.version>").matcher(pomText);
        assertThat(versionMatcher.find()).as("pom 必须声明 posthog-js.version").isTrue();
        String version = versionMatcher.group(1);
        assertThat(version).as("版本必须是精确版本").matches("[0-9]+\\.[0-9]+\\.[0-9]+");
        assertThat(version)
                .as("禁止 ^ / ~ / latest")
                .doesNotContain("^")
                .doesNotContain("~")
                .doesNotContain("latest");
        assertThat(countOccurrences(pomText, "<posthog-js.version>"))
                .as("Maven 中只能有一个精确版本来源")
                .isEqualTo(1);

        String surveyJs = read(SURVEY_JS);
        Matcher constantMatcher = Pattern.compile(
                "var POSTHOG_JS_VERSION = '([^']+)';").matcher(surveyJs);
        assertThat(constantMatcher.find()).as("调查脚本必须声明精确版本常量").isTrue();
        assertThat(constantMatcher.group(1)).as("脚本常量必须与 Maven 版本一致").isEqualTo(version);

        String vendorFile = VENDOR_DIR + version + "/array.full.js";
        assertThat(read(vendorFile)).as("vendor 文件必须存在且非空").isNotBlank();
        assertThat(surveyJs)
                .as("SDK 加载路径以固定 vendor 版本拼接（无 CDN）")
                .contains("'/vendor/posthog-js/' + POSTHOG_JS_VERSION + '/array.full.js'")
                .doesNotContain("unpkg")
                .doesNotContain("jsdelivr")
                .doesNotContain("cdn.")
                .doesNotContain("i.posthog.com/static");

        Path licenses = pluginResourcesRoot().resolve(LICENSES_DIR);
        assertThat(licenses.resolve("LICENSE")).as("上游许可证文件必须存在").isRegularFile();
        assertThat(licenses.resolve("SOURCE.txt")).as("来源信息必须存在").isRegularFile();
        assertThat(licenses.resolve("INTEGRITY.txt")).as("integrity 元数据必须存在").isRegularFile();
        String integrity = Files.readString(licenses.resolve("INTEGRITY.txt"), StandardCharsets.UTF_8);
        assertThat(integrity).contains("posthog-js " + version)
                .contains("dist.integrity")
                .contains("SHA-256")
                .contains("array.full.js");
    }

    @Test
    @DisplayName("原有双布局契约与调查资源互不干扰")
    void dualLayoutContractRemainsIntact() throws IOException {
        String html = read(BATCH_HTML);
        assertThat(html)
                .contains("data-batch-layout-style=\"landscape\"")
                .contains("data-batch-layout-style=\"portrait\"")
                .contains("href=\"/pixiv-batch/pixiv-batch-layout-workbench.css\"")
                .contains("href=\"/pixiv-batch/pixiv-batch-layout-classic.css\"");
        assertThat(linkHrefs(html)).contains("/pixiv-layout-feedback/pixiv-layout-feedback.css");
    }

    @Test
    @DisplayName("下载页语言命名空间包含 layout-feedback，插件声明同名 i18n contribution")
    void pagesAndPluginDeclareLayoutFeedbackNamespace() throws IOException {
        assertThat(read("static/pixiv-batch/batch-core.js"))
                .contains("'layout-feedback'");
        assertThat(read("static/pixiv-batch-alt/alt-init.js"))
                .contains("'layout-feedback'");
        assertThat(read("static/pixiv-batch-alt/alt-extensions.js"))
                .contains("'layout-feedback'");
        String pluginSource = Files.readString(
                pluginModuleRoot()
                        .resolve("src/main/java/top/sywyar/pixivdownload/download/DownloadWorkbenchPlugin.java"),
                StandardCharsets.UTF_8);
        assertThat(pluginSource)
                .contains("new I18nContribution(\"layout-feedback\", \"i18n.web.layout-feedback\"")
                .contains("\"/pixiv-layout-feedback/\"")
                .contains("\"/vendor/posthog-js/\"");
    }

    @Test
    @DisplayName("调查业务脚本暴露冻结的公共 API 且不含不安全模式")
    void surveyModuleExposesFrozenApiWithoutUnsafePatterns() throws IOException {
        String js = read(SURVEY_JS);
        assertThat(js)
                .contains("global.PixivLayoutFeedback = Object.freeze({")
                .contains("init: init")
                .contains("open: open")
                .contains("destroy: destroy")
                .contains("currentLayoutId: currentLayoutId")
                .doesNotContain("innerHTML")
                .doesNotContain("eval(")
                .doesNotContain("document.write");
        assertThat(read(SURVEY_CSS))
                .as("样式复用变量并适配 reduced motion")
                .contains("var(--surface")
                .contains("var(--text")
                .contains("var(--line")
                .contains("var(--brand")
                .contains("@media (prefers-reduced-motion: reduce)");
    }
}
