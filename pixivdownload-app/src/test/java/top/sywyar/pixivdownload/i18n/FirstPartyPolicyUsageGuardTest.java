package top.sywyar.pixivdownload.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检查所列第一方装配点显式传入 {@link LocaleBundlePolicy}，并保留旧插件策略入口。
 * 源码扫描只识别约定的调用写法与参数名，不证明全部依赖隔离。
 * 路径相对模块 basedir（surefire 工作目录 = pixivdownload-app）。
 */
@DisplayName("已列第一方 i18n 调用点显式使用 host 策略")
class FirstPartyPolicyUsageGuardTest {

    private static final List<String> FIRST_PARTY_CONFIGS = List.of(
            "pixivdownload-plugin-ai/src/main/java/top/sywyar/pixivdownload/ai/AiPluginConfiguration.java",
            "pixivdownload-plugin-download-workbench/src/main/java/top/sywyar/pixivdownload/download/DownloadWorkbenchPluginConfiguration.java",
            "pixivdownload-plugin-gallery-tools/src/main/java/top/sywyar/pixivdownload/duplicate/DuplicatePluginConfiguration.java",
            "pixivdownload-plugin-mail/src/main/java/top/sywyar/pixivdownload/mail/MailPluginConfiguration.java",
            "pixivdownload-plugin-novel/src/main/java/top/sywyar/pixivdownload/novel/NovelPluginConfiguration.java",
            "pixivdownload-plugin-push/src/main/java/top/sywyar/pixivdownload/push/PushPluginConfiguration.java",
            "pixivdownload-plugin-tts/src/main/java/top/sywyar/pixivdownload/tts/TtsPluginConfiguration.java"
    );

    private static final Pattern CALL = Pattern.compile("ResourceBundleMessageResolver\\.of\\(");
    private static final Pattern POLICY_ARG = Pattern.compile("\\blocaleBundlePolicy\\b");

    @Test
    @DisplayName("每个第一方插件装配点的 resolver 调用都显式传策略参数")
    void firstPartyCallSitesPassExplicitPolicy() throws IOException {
        Path repo = Path.of("..");
        for (String rel : FIRST_PARTY_CONFIGS) {
            Path file = repo.resolve(rel);
            assertThat(file).as("第一方装配文件必须存在: " + rel).exists();
            String src = Files.readString(file, StandardCharsets.UTF_8);
            Matcher matcher = CALL.matcher(src);
            int calls = 0;
            while (matcher.find()) {
                calls += 1;
                int open = src.indexOf('(', matcher.start());
                int close = matchingCloseParen(src, open);
                String args = src.substring(open + 1, close);
                assertThat(POLICY_ARG.matcher(args).find())
                        .as(rel + " 的 ResourceBundleMessageResolver.of 调用必须显式传 LocaleBundlePolicy: " + args)
                        .isTrue();
            }
            assertThat(calls).as(rel + " 必须存在 resolver 装配调用").isGreaterThan(0);
            assertThat(src).as(rel + " 不得引用 legacy 策略").doesNotContain("LegacyLocaleBundlePolicy");
        }
    }

    @Test
    @DisplayName("旧插件的 legacy 策略单例仍可访问")
    void legacyPolicyRemainsAvailable() {
        assertThat(LegacyLocaleBundlePolicy.INSTANCE).isNotNull();
    }

    private static int matchingCloseParen(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth += 1;
            } else if (c == ')') {
                depth -= 1;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new AssertionError("unbalanced parens in source");
    }
}
