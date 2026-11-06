package top.sywyar.pixivdownload.novel.export;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import top.sywyar.pixivdownload.core.work.model.WorkAssetFile;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.service.WorkAssetService;
import top.sywyar.pixivdownload.core.work.service.WorkMetadataRepository;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService.NovelFormat;
import top.sywyar.pixivdownload.novel.testsupport.NovelTestMessages;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("单篇小说导出")
class NovelExportServiceTest {
    @TempDir Path root;
    private final NovelDatabase novels = mock(NovelDatabase.class);
    private final WorkAssetService assets = mock(WorkAssetService.class);
    private final NovelRecord novel = mock(NovelRecord.class);
    private final NovelExportService service = new NovelExportService(novels,
            mock(WorkMetadataRepository.class), assets, NovelTestMessages.messageResolver());

    private void existingNovel() {
        when(novels.getNovel(42)).thenReturn(novel);
        when(novel.novelId()).thenReturn(42L);
        when(novel.title()).thenReturn("Original title");
        when(novel.rawContent()).thenReturn("Original body");
        when(novel.xLanguage()).thenReturn("ja");
        when(novel.folder()).thenReturn(root.toString());
    }

    @ParameterizedTest
    @EnumSource(NovelFormat.class)
    @DisplayName("三种格式分别导出原文或精确引擎译文且不改写下载文件")
    void exportsExactReadingVersion(NovelFormat format) throws Exception {
        existingNovel();
        Path downloaded = root.resolve("downloaded.txt");
        Files.writeString(downloaded, "Keep existing file");
        when(novels.hasTranslation(42, "zh-CN@youdao")).thenReturn(true);
        when(novels.hasTranslation(42, "zh-CN@ai")).thenReturn(true);
        when(novels.getTranslationContent(42, "zh-CN@youdao")).thenReturn("有道译文");
        when(novels.getTranslationContent(42, "zh-CN@ai")).thenReturn("AI 译文");
        when(novels.getTranslationTitle(42, "zh-CN@youdao")).thenReturn("译文标题");
        var original = service.export(42, format, null);
        var youdao = service.export(42, format, "zh-CN@youdao");
        var ai = service.export(42, format, "zh-CN@ai");
        assertThat(body(original, format)).contains("Original body").doesNotContain("有道译文", "AI 译文");
        assertThat(body(youdao, format)).contains("有道译文").doesNotContain("Original body", "AI 译文");
        assertThat(body(ai, format)).contains("AI 译文").doesNotContain("Original body", "有道译文");
        assertThat(youdao.filename()).contains("译文标题", "youdao").endsWith("." + format.ext());
        assertThat(Files.readString(downloaded)).isEqualTo("Keep existing file");
        try (var files = Files.list(root)) {
            assertThat(files.toList()).containsExactly(downloaded);
        }
    }

    @Test
    @DisplayName("仅翻译元数据时保留原文正文，EPUB 包含本地封面、插图及目标语言")
    void packagesLocalAssetsAndPartialTranslation() throws Exception {
        existingNovel();
        when(novel.rawContent()).thenReturn("Original body[uploadedimage:7]");
        when(novels.hasTranslation(42, "zh-CN@youdao")).thenReturn(true);
        when(novels.getTranslationTitle(42, "zh-CN@youdao")).thenReturn("译文标题");
        when(novels.getTranslationDescription(42, "zh-CN@youdao")).thenReturn("译文简介");
        when(novels.getNovelImageIds(42)).thenReturn(List.of("7"));
        when(novels.getNovelImageExt(42, "7")).thenReturn("png");
        Files.write(root.resolve("embed_7.png"), new byte[]{1, 2, 3});
        Path cover = root.resolve("original-cover.jpg");
        Files.write(cover, new byte[]{4, 5, 6});
        WorkAssetFile coverAsset = mock(WorkAssetFile.class);
        when(coverAsset.path()).thenReturn(cover);
        when(coverAsset.extension()).thenReturn("jpg");
        when(assets.existingThumbnail(WorkType.NOVEL, 42, 0)).thenReturn(Optional.of(coverAsset));
        var entries = unzip(service.export(42, NovelFormat.EPUB, "zh-CN@youdao").content());
        assertThat(entries.get("mimetype")).isEqualTo("application/epub+zip".getBytes(StandardCharsets.UTF_8));
        assertThat(entries.get("OEBPS/images/embed_7.png")).containsExactly(1, 2, 3);
        assertThat(entries.get("OEBPS/images/cover.jpg")).containsExactly(4, 5, 6);
        assertThat(new String(entries.get("OEBPS/chapter-1.xhtml"), StandardCharsets.UTF_8))
                .contains("Original body", "images/embed_7.png");
        assertThat(new String(entries.get("OEBPS/content.opf"), StandardCharsets.UTF_8))
                .contains("译文标题", "译文简介", "zh-CN", "urn:pixiv:novel:42");
    }

    @Test
    @DisplayName("缺失作品、软删除与不存在的引擎译文不产生附件")
    void refusesUnavailableContent() throws Exception {
        assertThat(service.export(42, NovelFormat.TXT, null)).isNull();
        existingNovel();
        assertThat(service.export(42, NovelFormat.TXT, "zh-CN@youdao")).isNull();
        when(novel.deleted()).thenReturn(true);
        assertThat(service.export(42, NovelFormat.TXT, null)).isNull();
        verifyNoInteractions(assets);
    }

    @Test
    @DisplayName("原文和译文 HTML 均保留已下载插图的相对引用")
    void htmlReferencesLocalIllustrations() throws Exception {
        existingNovel();
        when(novel.rawContent()).thenReturn("Original body[uploadedimage:7]");
        when(novels.hasTranslation(42, "zh-CN@youdao")).thenReturn(true);
        when(novels.getTranslationContent(42, "zh-CN@youdao")).thenReturn("Translated body[uploadedimage:7]");
        when(novels.getNovelImageIds(42)).thenReturn(List.of("7"));
        when(novels.getNovelImageExt(42, "7")).thenReturn("png");
        Path image = Files.write(root.resolve("embed_7.png"), new byte[]{1, 2, 3});
        assertThat(body(service.export(42, NovelFormat.HTML, null), NovelFormat.HTML))
                .contains("Original body", "<img", "src=\"embed_7.png\"");
        assertThat(body(service.export(42, NovelFormat.HTML, "zh-CN@youdao"), NovelFormat.HTML))
                .contains("Translated body", "<img", "src=\"embed_7.png\"").doesNotContain("Original body");
        assertThat(Files.readAllBytes(image)).containsExactly(1, 2, 3);
    }

    private static String body(NovelExportService.Export exported, NovelFormat format) throws Exception {
        return new String(format == NovelFormat.EPUB
                ? unzip(exported.content()).get("OEBPS/chapter-1.xhtml") : exported.content(), StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                entries.put(entry.getName(), zip.readAllBytes());
        }
        return entries;
    }
}
