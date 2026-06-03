package top.sywyar.pixivdownload.ai.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.narration.SpeakerAttributionResponse.SentenceVoice;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("逐句说话人归属响应解析与对齐")
class SpeakerAttributionResponseTest {

    private static final Set<Integer> CAST = Set.of(0, 1, 2);

    @Test
    @DisplayName("解析标准 JSON：下标/说话人/情绪微调")
    void parseStandard() {
        SpeakerAttributionResponse r = SpeakerAttributionResponse.parse(
                "{\"segments\":[{\"i\":0,\"speaker\":0,\"delivery\":\"\"},"
                        + "{\"i\":1,\"speaker\":1,\"delivery\":\"angry, faster\"}]}");
        List<SentenceVoice> list = r.normalizedTo(2, CAST);
        assertEquals(0, list.get(0).speakerId());
        assertEquals("", list.get(0).delivery());
        assertEquals(1, list.get(1).speakerId());
        assertEquals("angry, faster", list.get(1).delivery());
    }

    @Test
    @DisplayName("名册外 speaker 归旁白(0)")
    void unknownSpeakerFallsBackToNarrator() {
        SpeakerAttributionResponse r = SpeakerAttributionResponse.parse(
                "{\"segments\":[{\"i\":0,\"speaker\":99,\"delivery\":\"x\"}]}");
        assertEquals(0, r.normalizedTo(1, CAST).get(0).speakerId());
    }

    @Test
    @DisplayName("缺失的句子归旁白；对齐到输入句数")
    void missingSentencesFallBackToNarrator() {
        SpeakerAttributionResponse r = SpeakerAttributionResponse.parse(
                "{\"segments\":[{\"i\":0,\"speaker\":2,\"delivery\":\"\"}]}");
        List<SentenceVoice> list = r.normalizedTo(3, CAST);
        assertEquals(3, list.size());
        assertEquals(2, list.get(0).speakerId());
        assertEquals(0, list.get(1).speakerId());
        assertEquals(0, list.get(2).speakerId());
    }

    @Test
    @DisplayName("模型省略 i 但保持顺序：按出现顺序回填")
    void fillsByOrderWhenIndexMissing() {
        SpeakerAttributionResponse r = SpeakerAttributionResponse.parse(
                "{\"segments\":[{\"speaker\":1,\"delivery\":\"a\"},{\"speaker\":2,\"delivery\":\"b\"}]}");
        List<SentenceVoice> list = r.normalizedTo(2, CAST);
        assertEquals(1, list.get(0).speakerId());
        assertEquals(2, list.get(1).speakerId());
    }

    @Test
    @DisplayName("容忍代码围栏")
    void parseWrapped() {
        SpeakerAttributionResponse r = SpeakerAttributionResponse.parse(
                "```json\n{\"segments\":[{\"i\":0,\"speaker\":1,\"delivery\":\"\"}]}\n```");
        assertEquals(1, r.normalizedTo(1, CAST).get(0).speakerId());
    }

    @Test
    @DisplayName("空内容或无法解析时抛出 IllegalArgumentException")
    void parseEmptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> SpeakerAttributionResponse.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SpeakerAttributionResponse.parse("nope"));
    }
}
