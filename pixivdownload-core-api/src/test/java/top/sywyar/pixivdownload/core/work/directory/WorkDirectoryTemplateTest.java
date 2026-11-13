package top.sywyar.pixivdownload.core.work.directory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkDirectoryTemplateTest {
    @Test
    @DisplayName("系列名称和序号缺省时省略目录层，零序号及名称中的花括号保留")
    void seriesNameTitleAndOrder() {
        var template = WorkDirectoryTemplate.parse("root/{series_name}/{series_title}/{series_order}/{artwork_id}");
        assertEquals(Path.of("root", "123").toAbsolutePath(), template.resolve(Map.of("artworkId", "123")));
        assertEquals(Path.of("root", "系列_{page}", "系列_{page}", "0", "123").toAbsolutePath(),
                template.resolve(Map.of("seriesName", "系列/{page}", "seriesTitle", "系列/{page}",
                        "seriesOrder", "0", "artworkId", "123")));
        assertThrows(DirectoryTemplateException.class, () -> WorkDirectoryTemplate.parse("root/{series_name}/{series_order}"));
    }
    @Test
    @DisplayName("配置校验保留位置和未知变量，异常不包含完整目录")
    void retainsConfigurationLocation() {
        var failure = assertThrows(DirectoryTemplateException.class,
                () -> WorkDirectoryTemplate.parse("private-directory/{unknown}/{artwork_id}", "download.mode-templates.user"));
        assertEquals("download.mode-templates.user", failure.configurationKey());
        assertEquals("unknown-variable", failure.reason());
        assertEquals("unknown", failure.variable());
        assertInstanceOf(DirectoryTemplateException.class, failure.getCause());
        assertTrue(failure.getMessage().contains("download.mode-templates.user"));
        assertTrue(failure.getMessage().contains("{unknown}"));
        assertFalse(failure.getMessage().contains("private-directory"));
    }

    @Test
    @DisplayName("最终目录使用标准变量拼写直接展开")
    void resolvesFinalDirectory() {
        var template = WorkDirectoryTemplate.parse("pixiv-download/{author_name}/{artwork_id}/pages");
        assertEquals(Path.of("pixiv-download"), template.fixedPrefix());
        assertEquals(Path.of("pixiv-download", "a_b", "123", "pages").toAbsolutePath(),
                template.resolve(Map.of("authorName", "a/b", "artworkId", "123")));
        var absolute = WorkDirectoryTemplate.parse(Path.of("pictures").toAbsolutePath() + "/novel-{artwork_id}");
        assertTrue(absolute.fixedPrefix().isAbsolute());
        assertEquals(Path.of("pictures", "novel-123").toAbsolutePath(), absolute.resolve(Map.of("artworkId", "123")));
    }

    @Test
    @DisplayName("普通作品省略分级段且来源用户名不同于作品作者")
    void omitsOnlyDeclaredOptionalSegments() {
        var template = WorkDirectoryTemplate.parse("pixiv-download/{username}/{R18}/{artwork_id}");
        assertEquals(Path.of("pixiv-download", "source", "123").toAbsolutePath(),
                template.resolve(Map.of("username", "source", "authorName", "author", "artworkId", "123")));
        assertEquals(Path.of("pixiv-download", "source", "R18G", "123").toAbsolutePath(),
                template.resolve(Map.of("username", "source", "R18", "R18G", "artworkId", "123")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "downloads/"})
    @DisplayName("缺省收藏夹、系列与分级省略空层并保留相对路径锚点")
    void missingOptionalValuesPreserveAnchor(String prefix) {
        var template = WorkDirectoryTemplate.parse(prefix + "{collection_name}/{collection_id}/{series_id}/{R18}/{artwork_id}");
        assertEquals(Path.of(prefix, "123").toAbsolutePath().normalize(),
                template.resolve(Map.of("artworkId", "123")));
        assertEquals(Path.of(prefix, "123").toAbsolutePath().normalize(),
                template.resolve(Map.of("artworkId", "123", "collectionName", " ",
                        "collectionId", "", "seriesId", "", "R18", "")));
        assertEquals(Path.of(prefix, "收藏_夹", "7", "42", "R18", "123").toAbsolutePath().normalize(),
                template.resolve(Map.of("collectionName", "收藏/夹", "collectionId", "7",
                        "seriesId", "42", "R18", "R18", "artworkId", "123")));
        var anchor = Path.of("collection-root").toAbsolutePath();
        assertEquals(anchor.resolve("123"), template.resolve(anchor, Map.of("artworkId", "123")));
    }

    @Test
    @DisplayName("系列变量使用下划线拼写，空值不删除同段的固定文字")
    void seriesAndFixedText() {
        var template = WorkDirectoryTemplate.parse("root/{series_id}/series-{series_id}/level-{R18}/{artwork_id}");
        assertEquals(Path.of("root", "series-", "level-", "123").toAbsolutePath(),
                template.resolve(Map.of("artworkId", "123")));
        assertEquals(Path.of("root", "42", "series-42", "level-", "123").toAbsolutePath(),
                template.resolve(Map.of("seriesId", "42", "artworkId", "123")));
        assertEquals("invalid-variable", assertThrows(DirectoryTemplateException.class,
                () -> template.resolve(Map.of("seriesId", "4/2", "artworkId", "123"))).reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"artwork_id", "author_name", "username"})
    @DisplayName("可空上下文变量不改变作品身份及其它必需变量的缺值校验")
    void missingRequiredValueFails(String required) {
        var template = WorkDirectoryTemplate.parse("root/{" + required + "}/{artwork_id}");
        var failure = assertThrows(DirectoryTemplateException.class,
                () -> template.resolve(required.equals("artwork_id") ? Map.of() : Map.of("artworkId", "123")));
        assertEquals("missing-variable", failure.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"artworkId", "authorId", "artworkTitle", "authorName", "seriesId",
            "collectionId", "collectionName", "ARTWORK_ID", "artworkid"})
    @DisplayName("目录模板拒绝非标准变量并保留出错变量名")
    void rejectsVariableAliases(String token) {
        var failure = assertThrows(DirectoryTemplateException.class,
                () -> WorkDirectoryTemplate.parse("root/{" + token + "}/{artwork_id}"));
        assertEquals("unknown-variable", failure.reason());
        assertEquals(token, failure.variable());
    }

    @Test
    @DisplayName("变量中的路径符与花括号不参与递归求值")
    void variableValuesCannotIntroducePathsOrVariables() {
        var template = WorkDirectoryTemplate.parse("downloads/{artwork_title}/{artwork_id}");
        assertEquals(Path.of("downloads", ".._.._{author_id}", "123").toAbsolutePath(),
                template.resolve(Map.of("artworkTitle", "../../{author_id}", "artworkId", "123")));
        var failure = assertThrows(DirectoryTemplateException.class,
                () -> template.resolve(Map.of("artworkTitle", "..", "artworkId", "123")));
        assertEquals("missing-variable", failure.reason());
    }

    @ParameterizedTest
    @ValueSource(strings = {"downloads", "{author_id}{artwork_id}", "{artwork_id}-{artwork_title}",
            "../{artwork_id}", "root/{artwork_id}/../other", "root/{unknown}/{artwork_id}",
            "root/{page}/{artwork_id}", "root/{artwork_id", "C:relative/{artwork_id}"})
    @DisplayName("拒绝碰撞模板、路径穿越和不适用变量")
    void rejectsAmbiguousOrUnsafeTemplates(String template) {
        assertThrows(DirectoryTemplateException.class, () -> WorkDirectoryTemplate.parse(template));
    }

    @Test
    @DisplayName("作品身份不会因清洗或长度截断丢失")
    void identityIsValidatedAndNeverTruncated() {
        var template = WorkDirectoryTemplate.parse("root/{artwork_id}");
        assertEquals("12345678901234567890", template.resolve(Map.of("artworkId", "12345678901234567890")).getFileName().toString());
        assertEquals("invalid-variable", assertThrows(DirectoryTemplateException.class,
                () -> template.resolve(Map.of("artworkId", "1/23"))).reason());
    }

    @Test @DisplayName("固定前缀改写保留完整模板，UNC 服务器和共享名必须固定")
    void preservesSuffixAndFixedUncAnchor() {
        var template = WorkDirectoryTemplate.parse("pictures/{author_name}/{artwork_id}/pages");
        assertEquals("new-root/{author_name}/{artwork_id}/pages", template.withFixedPrefix("new-root"));
        if (java.io.File.separatorChar == '\\') {
            var unc = WorkDirectoryTemplate.parse("//server/share/{artwork_id}");
            assertTrue(unc.fixedPrefix().isAbsolute());
            assertEquals(Path.of("//server/share/123"), unc.resolve(Map.of("artworkId", "123")));
            assertThrows(DirectoryTemplateException.class, () -> WorkDirectoryTemplate.parse("//{author_name}/share/{artwork_id}"));
        }
    }
}
