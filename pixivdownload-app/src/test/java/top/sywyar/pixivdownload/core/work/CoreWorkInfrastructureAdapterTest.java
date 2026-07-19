package top.sywyar.pixivdownload.core.work;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("核心作品基础设施适配器")
class CoreWorkInfrastructureAdapterTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("共享标签与文件名字典原样委托核心数据库")
    void delegatesSharedCatalogOperations() {
        PixivDatabase pixivDatabase = mock(PixivDatabase.class);
        CoreWorkCatalogAdapter adapter = new CoreWorkCatalogAdapter(pixivDatabase);
        when(pixivDatabase.upsertTagAndGetId("tag", "translation")).thenReturn(7L);
        when(pixivDatabase.upsertTagAndGetId("missing", null)).thenReturn(null);
        when(pixivDatabase.getOrCreateFileNameTemplateId("{artwork_id}")).thenReturn(11L);
        when(pixivDatabase.getOrCreateFileNameTemplateId(null)).thenReturn(1L);
        when(pixivDatabase.getOrCreateFileAuthorNameId("Writer")).thenReturn(13L);
        when(pixivDatabase.getOrCreateFileAuthorNameId("")).thenReturn(0L);

        assertThat(adapter.getOrCreateTagId("tag", "translation")).isEqualTo(7L);
        assertThat(adapter.getOrCreateTagId("missing", null)).isNull();
        assertThat(adapter.getOrCreateTemplateId("{artwork_id}")).isEqualTo(11L);
        assertThat(adapter.getOrCreateTemplateId(null)).isEqualTo(1L);
        assertThat(adapter.getOrCreateAuthorNameId("Writer")).isEqualTo(13L);
        assertThat(adapter.getOrCreateAuthorNameId("")).isZero();

        verify(pixivDatabase).upsertTagAndGetId("tag", "translation");
        verify(pixivDatabase).upsertTagAndGetId("missing", null);
        verify(pixivDatabase).getOrCreateFileNameTemplateId("{artwork_id}");
        verify(pixivDatabase).getOrCreateFileNameTemplateId(null);
        verify(pixivDatabase).getOrCreateFileAuthorNameId("Writer");
        verify(pixivDatabase).getOrCreateFileAuthorNameId("");
    }

    @Test
    @DisplayName("安全目录名保持既有 trim 与拒绝语义")
    void delegatesSafeDirectoryNameValidation() {
        DownloadPathGuardAdapter adapter = new DownloadPathGuardAdapter();

        assertThat(adapter.requireSafeDirectoryName(" reader ")).isEqualTo("reader");
        assertThat(catchThrowable(() -> adapter.requireSafeDirectoryName("../reader")))
                .isInstanceOf(LocalizedException.class)
                .extracting(error -> ((LocalizedException) error).getMessageCode())
                .isEqualTo("download.path.segment.invalid");
    }

    @Test
    @DisplayName("规范化后的候选路径只能位于下载根内")
    void requiresCandidateWithinNormalizedRoot() {
        DownloadPathGuardAdapter adapter = new DownloadPathGuardAdapter();
        Path root = tempDir.resolve("downloads");
        Path inside = root.resolve("draft").resolve("..").resolve("novel-1");
        Path outside = tempDir.resolve("other").resolve("novel-1");

        assertThatCode(() -> adapter.requireWithinRoot(root, inside)).doesNotThrowAnyException();

        Throwable thrown = catchThrowable(() -> adapter.requireWithinRoot(root, outside));
        assertThat(thrown).isInstanceOf(LocalizedException.class);
        assertThat(((LocalizedException) thrown).getMessageCode())
                .isEqualTo("download.path.segment.invalid");
    }
}
