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
 * 页面加载顺序 / 唯一性、i18n 键集合、资源归属、发行激活位与 PostHog 插件消费边界。
 * 运行态行为由 {@code src/test/js/pixiv-layout-feedback.test.js} 通过真实脚本执行验证，
 * PostHog SDK 由 {@code pixivdownload-plugin-posthog} 自身测试守护，四个调查参数由本插件持有。
 */
@DisplayName("下载工作台布局偏好调查静态契约守卫")
class LayoutSurveyContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String BATCH_HTML = STATIC_ROOT + "pixiv-batch.html";
    private static final String BATCH_ALT_HTML = STATIC_ROOT + "pixiv-batch-alt.html";
    private static final String SURVEY_CSS = STATIC_ROOT + "pixiv-layout-feedback/pixiv-layout-feedback.css";
    private static final String SURVEY_JS = STATIC_ROOT + "pixiv-layout-feedback/pixiv-layout-feedback.js";
    private static final String RELEASE_ACTIVATION = STATIC_ROOT
            + "pixiv-layout-feedback/release-activation.js";
    private static final String I18N_ZH = "i18n/web/layout-feedback.properties";
    private static final String I18N_EN = "i18n/web/layout-feedback_en.properties";
    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script\\s+[^>]*src=\"([^\"]+)\"[^>]*>", Pattern.CASE_INSENSITIVE);

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
    @DisplayName("中英文隐私文案与身份模型一致：仅收集填写内容与随机安装身份（去重），不发送其他信息")
    void privacyCopyMatchesIdentityModel() throws IOException {
        String zh = read(I18N_ZH);
        String en = read(I18N_EN);
        String js = read(SURVEY_JS);

        // 中文简短声明：PostHog SDK + 只收集填写内容与随机安装身份的单向散列匿名标识
        assertThat(zh).contains("PostHog SDK");
        assertThat(zh).contains("只收集您填写的内容");
        assertThat(zh).contains("单向散列");
        assertThat(zh).contains("不可逆");
        assertThat(zh).contains("随机安装身份");
        assertThat(zh).contains("避免重复弹窗");
        assertThat(zh).contains("不会发送其他任何信息");

        // 英文语义一致
        assertThat(en).contains("PostHog SDK");
        assertThat(en).contains("what you fill in");
        assertThat(en).contains("installation identity");
        assertThat(en).contains("duplicate pop-ups");
        assertThat(en).contains("No other information is sent");

        // JS 弹窗 fallback 与中文 i18n 语义一致
        assertThat(js).contains("PostHog SDK");
        assertThat(js).contains("随机安装身份");
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
    @DisplayName("调查脚本把 scoped identity 交给 PostHog 插件并验证实际 SDK identity")
    void surveyDelegatesAndVerifiesScopedIdentity() throws IOException {
        String js = read(SURVEY_JS);
        assertThat(js).contains("createSurveyClient")
                .contains("ownerKey: POSTHOG_OWNER_KEY")
                .contains("posthog: POSTHOG")
                .contains("distinctId: serverIdentityAvailable && serverDistinctId ? serverDistinctId : ''")
                .contains("get_distinct_id");
        assertThat(js).doesNotContain("sdkConfig.distinct_id =");
        assertThat(js).doesNotContain("posthog.identify(");
        assertThat(js).doesNotContain("posthog.reset(");
        assertThat(js).doesNotContain("opt_out_capturing(");
    }

    @Test
    @DisplayName("Java 枚举小写 wire value 与前端视图校验字面量两端一致（无各自硬编码假协议）")
    void javaWireValuesMatchFrontendLiterals() throws IOException {
        String js = read(SURVEY_JS);
        // 前端 applyServerView 只接受小写状态字面量；每个 Java wire value 必须真实出现。
        for (top.sywyar.pixivdownload.download.state.LayoutFeedbackDecision decision :
                top.sywyar.pixivdownload.download.state.LayoutFeedbackDecision.values()) {
            String wire = decision.wireName();
            assertThat(js).as("前端必须接受 Java 小写 wire value: " + wire)
                    .contains("data.status !== '" + wire + "'")
                    .contains("state.status === '" + wire + "'");
        }
        // 前端不得为兼容服务端而硬编码大写枚举名（大写只允许出现在 Java 旧值兼容入口）。
        assertThat(js).as("前端不得包含大写旧枚举名").doesNotContain("'SUBMITTED'")
                .doesNotContain("'NEVER'")
                .doesNotContain("'SNOOZED'");
        // 命令字面量同样与小写 wire 对齐：submitted / never / snooze / record_seen。
        assertThat(js).contains("'record_seen'").contains("'snooze'")
                .contains("command === 'submitted'").contains("command === 'never'");
    }

    @Test
    @DisplayName("前端所有状态 GET 的 fetch init 携带 cache: 'no-store'")
    void frontendStateGetsUseNoStore() throws IOException {
        String js = read(SURVEY_JS);
        int noStoreUses = countOccurrences(js, "cache: 'no-store'");
        assertThat(noStoreUses).as("loadServerContext / refreshServerContext 至少两处 no-store").isGreaterThanOrEqualTo(2);
        assertThat(js).contains("SERVER_STATE_URL");
    }

    @Test
    @DisplayName("新版工作台按发行激活位、PostHog 适配器、业务脚本的顺序各加载一次；经典页不加载")
    void pagesLoadSurveyAssetsExactlyOnceInOrder() throws IOException {
        // 调查只在 pixiv-batch-alt.html 以「首次下载完成」触发；经典下载页不参与。
        String alt = read(BATCH_ALT_HTML);
        String css = "/pixiv-layout-feedback/pixiv-layout-feedback.css";
        String activation = "/pixiv-layout-feedback/release-activation.js";
        String adapter = "/pixiv-posthog/pixiv-posthog.js";
        String script = "/pixiv-layout-feedback/pixiv-layout-feedback.js";

        assertThat(countOccurrences(alt, css)).as(BATCH_ALT_HTML + " 调查 CSS 恰好一次").isEqualTo(1);
        assertThat(countOccurrences(alt, activation)).as(BATCH_ALT_HTML + " 发行激活位恰好一次").isEqualTo(1);
        assertThat(countOccurrences(alt, adapter)).as(BATCH_ALT_HTML + " PostHog 适配器恰好一次").isEqualTo(1);
        assertThat(countOccurrences(alt, script)).as(BATCH_ALT_HTML + " 调查业务脚本恰好一次").isEqualTo(1);

        List<String> scripts = scriptSources(alt);
        assertThat(scripts.indexOf(activation))
                .as(BATCH_ALT_HTML + " 发行激活位必须加载")
                .isGreaterThanOrEqualTo(0);
        assertThat(scripts.indexOf(adapter))
                .as(BATCH_ALT_HTML + " PostHog 适配器必须加载")
                .isGreaterThan(scripts.indexOf(activation));
        assertThat(scripts.indexOf(script))
                .as(BATCH_ALT_HTML + " 调查业务脚本必须加载")
                .isGreaterThan(scripts.indexOf(adapter))
                .describedAs("PostHog 适配器必须在调查业务脚本之前加载");

        String batch = read(BATCH_HTML);
        assertThat(batch).as(BATCH_HTML + " 不再加载调查资源")
                .doesNotContain(css)
                .doesNotContain(activation)
                .doesNotContain(adapter)
                .doesNotContain(script);
    }

    @Test
    @DisplayName("普通源码构建默认关闭调查，发布者自持四个 PostHog 参数")
    void sourceBuildIsDisabledAndPublisherOwnsPostHogParameters() throws IOException {
        String js = read(SURVEY_JS);
        assertThat(read(RELEASE_ACTIVATION))
                .contains("global.PixivLayoutFeedbackOfficialRelease = false;");
        assertThat(js)
                .contains("var POSTHOG = Object.freeze({")
                .contains("projectToken: 'phc_nBnHrYwgVVN6CvzAsQ5r4NxuSJyVPmceeHwwcpcgbG3k'")
                .contains("surveyId: '019fce31-c9ce-0000-934a-375b3ddbbd6c'")
                .contains("apiHost: 'https://layout-survey.sywyar.top'")
                .contains("uiHost: 'https://us.posthog.com'")
                .contains("ownerKey: POSTHOG_OWNER_KEY")
                .contains("posthog: POSTHOG")
                .contains("global.PixivLayoutFeedbackOfficialRelease !== true");
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
    @DisplayName("原有双布局契约与调查资源互不干扰")
    void dualLayoutContractRemainsIntact() throws IOException {
        String html = read(BATCH_HTML);
        assertThat(html)
                .contains("data-batch-layout-style=\"landscape\"")
                .contains("data-batch-layout-style=\"portrait\"")
                .contains("href=\"/pixiv-batch/pixiv-batch-layout-workbench.css\"")
                .contains("href=\"/pixiv-batch/pixiv-batch-layout-classic.css\"");
    }

    @Test
    @DisplayName("新版工作台语言命名空间包含 layout-feedback，插件声明同名 i18n contribution；经典下载页不再声明")
    void pagesAndPluginDeclareLayoutFeedbackNamespace() throws IOException {
        assertThat(read("static/pixiv-batch/batch-core.js"))
                .as("经典下载页不再声明调查命名空间")
                .doesNotContain("'layout-feedback'");
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
                .doesNotContain("\"/vendor/posthog-js/\"");
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
