package top.sywyar.pixivdownload.sdk.community.submission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MarketPublicationFilesTest {
    @TempDir Path root;

    @Test @DisplayName("官方策展明确来源后按版本冻结说明及完整日志，空链接保持为空")
    void publication() throws Exception {
        Files.writeString(root.resolve("README.html"), "<h1>Original</h1><script>blocked()</script>", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("CHANGELOG.md"), "## [7.4.2]\n\n### Added\n\n- Current\n\n## [7.4.1]\n\n- Previous\n", StandardCharsets.UTF_8);
        Path curation = root.resolve("curation.json");
        Files.writeString(curation, """
                {"example":{"defaultLocale":"en","links":[],"documentationSources":{
                  "readme":{"en":"README.html"},"releaseNotes":{"en":"CHANGELOG.md"},
                  "changelog":{"en":"CHANGELOG.md"}}}}
                """, StandardCharsets.UTF_8);
        Path output = root.resolve("publication");
        String base = "https://github.com/example/plugins/releases/download/example-v7.4.2/";
        String sourceBase = "https://github.com/example/source/blob/" + "c".repeat(40) + "/";
        MarketPublicationFiles.prepare(root, curation, "example", "7.4.2", base, output, sourceBase);
        var result = MarketPublicationFiles.verify(output.resolve(MarketPublicationFiles.METADATA), output, base);
        assertThat(result.links()).isEmpty();
        assertThat(result.content().readme().get("en").sourceUrl()).isEqualTo(sourceBase + "README.html");
        assertThat(result.content().releaseNotes().get("en").sourceUrl()).isEqualTo(sourceBase + "CHANGELOG.md");
        assertThat(result.content().assets()).hasSize(3);
        assertThat(Files.readString(output.resolve(result.content().readme().get("en").asset().name())))
                .contains("<script>blocked()</script>");
        assertThat(Files.readString(output.resolve(result.content().releaseNotes().get("en").asset().name())))
                .contains("Current").doesNotContain("Previous");
        assertThat(Files.readString(output.resolve(result.content().changelog().get("en").asset().name())))
                .contains("Current", "Previous");
        assertThatThrownBy(() -> MarketPublicationFiles.verify(output.resolve(MarketPublicationFiles.METADATA), output,
                "https://github.com/another/repo/releases/download/other/"))
                .isInstanceOf(ContractException.class);
        Files.writeString(output.resolve(result.content().readme().get("en").asset().name()), "changed", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> MarketPublicationFiles.verify(output.resolve(MarketPublicationFiles.METADATA), output, base))
                .isInstanceOf(ContractException.class);
    }
}
