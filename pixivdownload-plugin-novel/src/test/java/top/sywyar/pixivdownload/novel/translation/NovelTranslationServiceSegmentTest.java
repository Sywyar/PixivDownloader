package top.sywyar.pixivdownload.novel.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.db.NovelGlossaryEntry;
import top.sywyar.pixivdownload.novel.db.NovelRecord;
import top.sywyar.pixivdownload.novel.testsupport.NovelTestMessages;
import top.sywyar.pixivdownload.novel.translation.ai.SourceLanguageProbeRequest;
import top.sywyar.pixivdownload.novel.translation.ai.TranslationRequest;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("小说分段翻译")
class NovelTranslationServiceSegmentTest {

    @ParameterizedTest
    @CsvSource({"998, true, true", "999, true, false", "1000, false, false"})
    @DisplayName("每段请求遵守术语条目上限，超出发送容量的新术语仍完整回写")
    void boundsEachRequestWithoutDroppingNewTerms(int initialCount, boolean firstIncluded, boolean secondIncluded)
            throws Exception {
        AiChatClient ai = mock(AiChatClient.class);
        NovelDatabase novels = mock(NovelDatabase.class);
        NovelGlossaryService glossaries = mock(NovelGlossaryService.class);
        NovelRecord record = mock(NovelRecord.class);
        when(novels.getNovel(42L)).thenReturn(record);
        when(record.rawContent()).thenReturn("first\nsecond");
        when(glossaries.entries(7L)).thenReturn(IntStream.range(0, initialCount)
                .mapToObj(i -> new NovelGlossaryEntry("source-" + i, "en", "target-" + i)).toList());
        when(ai.chat(eq(SourceLanguageProbeRequest.CALL_TYPE), any(), any()))
                .thenReturn(new AiChatResult("{\"same\":false}", "stop", null, null, null));
        when(ai.chat(eq(TranslationRequest.CALL_TYPE), any(), any())).thenReturn(
                new AiChatResult("""
                        {"status":"ok","lang":"en","text":"first translated",
                         "glossary":[{"source":"new-1","target":"one"},{"source":"new-2","target":"two"}]}
                        """, "stop", null, null, null),
                new AiChatResult("{\"status\":\"ok\",\"lang\":\"en\",\"text\":\"second translated\"}",
                        "stop", null, null, null));
        var service = new NovelTranslationService(ai, novels, glossaries, NovelTestMessages.messageResolver(), () -> true);

        assertThat(service.translateChapter(42L, "English", 5, true, "en", 7L, true, false, false).status())
                .isEqualTo(NovelTranslationService.Status.OK);

        ArgumentCaptor<List<AiChatMessage>> requests = ArgumentCaptor.forClass(List.class);
        verify(ai, times(2)).chat(eq(TranslationRequest.CALL_TYPE), requests.capture(), any());
        assertThat(requestGlossary(requests.getAllValues().get(0)).size()).isEqualTo(initialCount);
        JsonNode second = requestGlossary(requests.getAllValues().get(1));
        assertThat(second.size()).isEqualTo(1000);
        var sources = new java.util.ArrayList<String>();
        second.forEach(term -> sources.add(term.path("source").asText()));
        assertThat(sources).contains("source-0", "source-" + (initialCount - 1));
        assertThat(sources.contains("new-1")).isEqualTo(firstIncluded);
        assertThat(sources.contains("new-2")).isEqualTo(secondIncluded);
        verify(novels).saveTranslation(42L, "en", "first translated\nsecond translated", null, null);
        verify(glossaries).mergeAiTerms(7L, "en", List.of(
                new NovelGlossaryEntry("new-1", "en", "one"), new NovelGlossaryEntry("new-2", "en", "two")));
    }

    private static JsonNode requestGlossary(List<AiChatMessage> messages) throws Exception {
        String user = messages.get(1).content();
        return new ObjectMapper().readTree(user.substring(user.indexOf('['), user.lastIndexOf(']') + 1));
    }

    @Test
    @DisplayName("分段字数 <=0：整篇作为单个分段")
    void wholeTextWhenNoSplit() {
        String raw = "第一段\n第二段\n第三段";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 0);
        assertEquals(1, segments.size());
        assertEquals(raw, segments.get(0));
    }

    @Test
    @DisplayName("按段落累积到阈值切分，且以 \\n 拼接可精确还原原文")
    void splitsOnLineBoundariesAndReconstructs() {
        String raw = "para1\npara2\npara3\npara4";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 6);
        assertTrue(segments.size() >= 2, "应被切成多个分段");
        // 仅在换行处切分：原样拼接必须等于原文（恒等翻译下无损）
        assertEquals(raw, String.join("\n", segments));
    }

    @Test
    @DisplayName("无换行的超长单行：不在行内截断，作为一个分段")
    void singleLongLineStaysOneSegment() {
        String raw = "这是一整段没有任何换行的很长的文本用于验证不会被从中间切断";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 5);
        assertEquals(1, segments.size());
        assertEquals(raw, segments.get(0));
    }

    @Test
    @DisplayName("分段边界落在空行后：拼接仍精确还原原文（不丢空行）")
    void preservesBlankLinesAtSegmentBoundaries() {
        // 阈值 1 字让 "a" 立刻成为单独一段；下一行是空行（曾被吞掉），再下一行是 "b"
        String raw = "a\n\nb";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 1);
        assertEquals(raw, String.join("\n", segments));
    }

    @Test
    @DisplayName("尾随换行不丢：a\\n 拼回仍为 a\\n")
    void preservesTrailingNewline() {
        String raw = "a\n";
        List<String> segments = NovelTranslationService.splitIntoSegments(raw, 1);
        assertEquals(raw, String.join("\n", segments));
    }
}
