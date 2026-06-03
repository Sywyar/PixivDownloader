package top.sywyar.pixivdownload.ai.narration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 「逐句说话人归属」的 AI 响应体，对应 {@link SpeakerAttributionRequest} 约定的 JSON 输出规范
 * （{@code {"segments":[{"i":..,"speaker":..,"delivery":..}]}}）。
 *
 * <p>模型输出可能不规范（缺项、下标缺失 / 越界、引用名册外的 speaker、数量对不上），调用方应用
 * {@link #normalizedTo(int, Set)} 拿到<b>严格对齐到输入句数</b>的结果：每句恰好一条、按下标升序、speaker
 * 不在名册内即归旁白（id 0）、缺失的句子归旁白。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SpeakerAttributionResponse(@JsonProperty("segments") List<RawSegment> segments) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawSegment(
            @JsonProperty("i") Integer index,
            @JsonProperty("speaker") Integer speaker,
            @JsonProperty("delivery") String delivery
    ) {
    }

    /**
     * 归一后的单句说话人归属（输出形态）。
     *
     * @param index     句子在输入中的下标
     * @param speakerId 名册里的说话人 id（{@code 0} 为旁白）
     * @param delivery  逐句情绪 / 语气微调（短英文，可为空串）
     */
    public record SentenceVoice(int index, int speakerId, String delivery) {

        public static SentenceVoice narratorAt(int index) {
            return new SentenceVoice(index, NarrationCharacter.NARRATOR_ID, "");
        }
    }

    public boolean ok() {
        return segments != null && !segments.isEmpty();
    }

    /**
     * 严格对齐到 {@code count} 句：返回恰好 {@code count} 条、下标升序。优先按合法 {@code i} 归位；缺失 / 越界
     * {@code i} 的对象按出现顺序回填空槽；仍空的句子归旁白。{@code speaker} 不在 {@code validSpeakerIds} 内时归旁白。
     */
    public List<SentenceVoice> normalizedTo(int count, Set<Integer> validSpeakerIds) {
        if (count <= 0) {
            return List.of();
        }
        SentenceVoice[] slots = new SentenceVoice[count];
        List<RawSegment> leftovers = new ArrayList<>();
        if (segments != null) {
            for (RawSegment raw : segments) {
                if (raw == null) {
                    continue;
                }
                Integer i = raw.index();
                if (i != null && i >= 0 && i < count && slots[i] == null) {
                    slots[i] = normalizeOne(raw, i, validSpeakerIds);
                } else {
                    leftovers.add(raw);
                }
            }
        }
        int cursor = 0;
        for (RawSegment raw : leftovers) {
            while (cursor < count && slots[cursor] != null) {
                cursor++;
            }
            if (cursor >= count) {
                break;
            }
            slots[cursor] = normalizeOne(raw, cursor, validSpeakerIds);
            cursor++;
        }
        List<SentenceVoice> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(slots[i] != null ? slots[i] : SentenceVoice.narratorAt(i));
        }
        return out;
    }

    private static SentenceVoice normalizeOne(RawSegment raw, int index, Set<Integer> validSpeakerIds) {
        int speaker = raw.speaker() != null && validSpeakerIds != null && validSpeakerIds.contains(raw.speaker())
                ? raw.speaker()
                : NarrationCharacter.NARRATOR_ID;
        String delivery = raw.delivery() == null ? "" : raw.delivery().trim();
        return new SentenceVoice(index, speaker, delivery);
    }

    /** 解析模型回复的 JSON 文本；无法解析时抛 {@link IllegalArgumentException}。 */
    public static SpeakerAttributionResponse parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty AI response");
        }
        try {
            return MAPPER.readValue(content, SpeakerAttributionResponse.class);
        } catch (Exception first) {
            String extracted = extractJsonObject(content);
            if (extracted != null) {
                try {
                    return MAPPER.readValue(extracted, SpeakerAttributionResponse.class);
                } catch (Exception ignored) {
                    // fall through to throwing the original failure
                }
            }
            throw new IllegalArgumentException("unparseable AI response: " + first.getMessage(), first);
        }
    }

    private static String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }
}
