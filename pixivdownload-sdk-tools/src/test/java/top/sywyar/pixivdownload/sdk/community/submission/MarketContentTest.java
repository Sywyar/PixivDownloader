package top.sywyar.pixivdownload.sdk.community.submission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.sdk.community.content.MarketContent;
import top.sywyar.pixivdownload.sdk.community.content.MarketLink;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

@DisplayName("市场文档、链接与精确版本说明")
class MarketContentTest {
    @Test
    @DisplayName("提取保留分组、围栏和外置引用，正式版不混入预发布版本")
    void extractsExactVersion() {
        String log = "# Changelog\r\n\r\n## [Unreleased]\r\n- pending\r\n"
                + "## [v2.7.3-rc.1] - 2030.1.2\r\n- candidate\r\n"
                + "## [2.7.3] - 2030.1.3\r\n\r\n### Features\r\n- [说明][guide]\r\n"
                + "```markdown\r\n## [v2.7.3]\r\n```\r\n"
                + "~~~\r\n## [v1.0.0]\r\n~~~\r\n"
                + "## [v2.7.2] - 2030.1.1\r\n- old\r\n\r\n[guide]: https://example.org/guide\r\n";
        assertThat(extract(log, "2.7.3")).isEqualTo("## [2.7.3] - 2030.1.3\n\n### Features\n- [说明][guide]\n"
                + "```markdown\n## [v2.7.3]\n```\n~~~\n## [v1.0.0]\n~~~\n\n[guide]: https://example.org/guide\n");
        assertThat(extract(log, "2.7.3-rc.1")).contains("- candidate").doesNotContain("### Features", "- old", "- pending");
        assertThatThrownBy(() -> extract(log, "2.7.4")).isInstanceOf(ContractException.class)
                .hasMessageContaining("CHANGELOG_VERSION_MISSING");
        assertThatThrownBy(() -> extract(log + "\n## [v2.7.3]\n- duplicate", "2.7.3"))
                .isInstanceOf(ContractException.class).hasMessageContaining("CHANGELOG_VERSION_DUPLICATED");
        assertThatThrownBy(() -> ChangelogSections.utf8(new byte[]{(byte) 0xc3, 0x28})).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> ChangelogSections.utf8(new byte[MarketContent.DOCUMENT_BYTES + 1]))
                .isInstanceOf(ContractException.class).hasMessageContaining("LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("新字段可选，显式空链接保留，未知字段继续拒绝")
    void roundTripsOptionalContract() throws Exception {
        var old = fixture();
        var parsed = read(old);
        assertThat(parsed.content()).isNull();
        assertThat(parsed.market().links()).isNull();
        ((ObjectNode) old.get("market")).putArray("links");
        assertThat(read(old).market().links()).isEmpty();
        var content = new MarketContent(Map.of("en", document()), null, null);
        old.set("content", CommunityJson.strictTree(CommunityJson.encode(content), 65536));
        parsed = read(old);
        assertThat(parsed.content().readme().get("en").asset().sha256()).isEqualTo("a".repeat(64));
        assertThat(CommunityJson.strictTree(CommunityJson.encode(parsed.content()), 65536).has("changelog")).isFalse();
        ((ObjectNode) old.get("content")).put("executable", true);
        assertThatThrownBy(() -> read(old)).isInstanceOf(ContractException.class);
    }

    @Test
    @DisplayName("链接和资源路径拒绝凭据、非法协议、空用途、重复与根目录逃逸")
    void validatesLinksAndResources() {
        MarketLink.validate(List.of(new MarketLink("repository", "https://example.org/repository", null),
                new MarketLink("custom", "https://example.org/help", Map.of("en", "Help"))));
        for (var link : List.of(new MarketLink("custom", "https://example.org", Map.of("en", " ")),
                new MarketLink("repository", "javascript:alert(1)", null),
                new MarketLink("issues", "https://user:password@example.org", null))) {
            assertThatThrownBy(() -> MarketLink.validate(List.of(link))).isInstanceOf(ContractException.class);
        }
        var link = new MarketLink("documentation", "https://example.org", null);
        assertThatThrownBy(() -> MarketLink.validate(List.of(link, link))).isInstanceOf(ContractException.class);
        assertThat(MarketContent.resourcePath("docs/README.md", "../images/icon.png")).isEqualTo("images/icon.png");
        for (String invalid : List.of("../../outside.png", "file:///tmp/x", "//host/image.png", "..%2f..%2fx", "\\evil")) {
            assertThatThrownBy(() -> MarketContent.resourcePath("docs/README.md", invalid)).isInstanceOf(ContractException.class);
        }
        assertThatThrownBy(() -> new MarketContent(Map.of("ja", document()), null, null).validate("en"))
                .isInstanceOf(ContractException.class).hasMessageContaining("LOCALE_DEFAULT_MISSING");
        var asset = document().asset();
        assertThatThrownBy(() -> asset.verify(new byte[]{1})).isInstanceOf(ContractException.class);
        assertThatThrownBy(() -> new MarketContent.Asset(asset.name(), asset.url(), asset.mediaType(),
                MarketContent.DOCUMENT_BYTES + 1, asset.sha256()).validate(false)).isInstanceOf(ContractException.class);
    }

    private static MarketContent.Document document() {
        return new MarketContent.Document("markdown", new MarketContent.Asset("content-" + "a".repeat(64) + ".md",
                "https://example.org/README.en.md", "text/markdown", 10, "a".repeat(64)), "README.md", Map.of());
    }
    private static String extract(String text, String version) {
        return ChangelogSections.extract(text.getBytes(StandardCharsets.UTF_8), version);
    }
    private static ObjectNode fixture() throws Exception {
        try (var input = MarketContentTest.class.getResourceAsStream("/community/v1/vectors/submission.json")) {
            return (ObjectNode) CommunityJson.parse(CommunityJson.Kind.SUBMISSION, input.readAllBytes()).value();
        }
    }
    private static VersionSubmission read(ObjectNode node) {
        return VersionSubmission.read(CommunityJson.parse(CommunityJson.Kind.SUBMISSION, CommunityJson.encode(node)));
    }
}
