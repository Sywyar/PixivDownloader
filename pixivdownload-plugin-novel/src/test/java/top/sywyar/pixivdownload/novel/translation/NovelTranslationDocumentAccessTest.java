package top.sywyar.pixivdownload.novel.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments;
import top.sywyar.pixivdownload.translation.api.NovelTranslationDocuments.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DisplayName("小说跨插件分片传输与原子保存")
class NovelTranslationDocumentAccessTest {
    private final NovelDatabase novels = mock(NovelDatabase.class);
    private final AtomicLong now = new AtomicLong();
    private final NovelTranslationDocumentAccess access = new NovelTranslationDocumentAccess(novels,
            mock(NovelGlossaryService.class), mock(NovelMergeService.class), () -> true, now::get);

    @Test
    @DisplayName("超过单次调用预算的章节可完整传输，包括分片边界上的代理对")
    void transfersLongChapterWithoutLoss() {
        String source = "字".repeat(NovelTranslationDocuments.CHUNK_CHARS - 1) + "😀" + "文".repeat(600_000);
        NovelRecord novel = mock(NovelRecord.class);
        when(novel.rawContent()).thenReturn(source);
        when(novels.getNovel(7)).thenReturn(novel);
        Document doc = access.openSource(Kind.NOVEL, 7);
        StringBuilder received = new StringBuilder();
        for (int offset = 0; offset < doc.bodyLength(); offset += NovelTranslationDocuments.CHUNK_CHARS)
            received.append(access.read(doc.transfer(), Field.BODY, offset,
                    Math.min(NovelTranslationDocuments.CHUNK_CHARS, doc.bodyLength() - offset)));
        access.close(doc.transfer());
        assertThat(received.toString()).isEqualTo(source);
        UUID write = access.beginWrite(Kind.NOVEL, 7, "en", source.length(), -1, -1);
        for (int offset = 0; offset < source.length(); offset += NovelTranslationDocuments.CHUNK_CHARS)
            access.append(write, Field.BODY, offset, source.substring(offset,
                    Math.min(source.length(), offset + NovelTranslationDocuments.CHUNK_CHARS)));
        verify(novels, never()).saveTranslation(anyLong(), any(), any(), any(), any());
        access.commit(write);
        verify(novels).saveTranslation(7, "en", source, null, null);
        assertThatThrownBy(() -> access.commit(write)).hasMessage("TRANSLATION_TRANSFER_UNAVAILABLE");
    }

    @Test
    @DisplayName("不完整或乱序分片不得覆盖译文，关闭释放容量")
    void rejectsIncompleteAndOutOfOrderWrites() {
        UUID write = access.beginWrite(Kind.NOVEL, 7, "en", 3, -1, -1);
        assertThatThrownBy(() -> access.append(write, Field.BODY, 1, "a")).hasMessage("TRANSLATION_WRITE_RANGE");
        access.append(write, Field.BODY, 0, "a");
        assertThatThrownBy(() -> access.commit(write)).hasMessage("TRANSLATION_WRITE_INCOMPLETE");
        access.close(write);
        verifyNoInteractions(novels);
    }

    @Test
    @DisplayName("累计字符预算、并发传输和停机拒绝；消费者遗留会话可惰性回收")
    void boundsAndReclaimsTransfers() {
        UUID large = access.beginWrite(Kind.NOVEL, 7, "en", 64 * 1024 * 1024, -1, -1);
        assertThatThrownBy(() -> access.beginWrite(Kind.NOVEL, 8, "en", 1, -1, -1)).hasMessage("TRANSLATION_TRANSFER_CAPACITY");
        access.close(large);
        for (int i = 0; i < 64; i++) access.beginWrite(Kind.NOVEL, 7, "en", 0, -1, -1);
        assertThatThrownBy(() -> access.beginWrite(Kind.NOVEL, 7, "en", 0, -1, -1)).hasMessage("TRANSLATION_TRANSFER_CAPACITY");
        now.addAndGet(Duration.ofMinutes(6).toNanos());
        UUID reclaimed = access.beginWrite(Kind.NOVEL, 7, "en", 1, -1, -1);
        access.append(reclaimed, Field.BODY, 0, "a");
        access.shutdown();
        assertThatThrownBy(() -> access.commit(reclaimed)).hasMessage("TRANSLATION_TRANSFER_UNAVAILABLE");
        assertThatThrownBy(() -> access.beginWrite(Kind.NOVEL, 7, "en", 1, -1, -1)).hasMessage("TRANSLATION_TRANSFER_CAPACITY");
    }
}
