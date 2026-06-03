package top.sywyar.pixivdownload.ai.narration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 「有声书选角」的 AI 响应体，对应 {@link CastAnalysisRequest} 约定的 JSON 输出规范
 * （{@code {"narrator":{"instruction":..},"characters":[{id,name,gender,age,instruction}]}}）。
 *
 * <p>{@link #parse(String)} 解析模型回复（带代码围栏兜底）；{@link #roster()} 把结果整理成稳定名册：
 * 旁白恒为 {@code id 0} 居首、角色按 id 去重并归一 gender/age，缺失的 instruction 用兜底音色补齐，
 * 保证调用方拿到至少含一名旁白的可用名册。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CastAnalysisResponse(
        @JsonProperty("narrator") RawNarrator narrator,
        @JsonProperty("characters") List<RawCharacter> characters
) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Set<String> GENDERS = Set.of("male", "female", "unknown");
    private static final Set<String> AGES = Set.of("child", "teen", "young", "middle", "elderly", "unknown");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawNarrator(@JsonProperty("instruction") String instruction) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawCharacter(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("gender") String gender,
            @JsonProperty("age") String age,
            @JsonProperty("instruction") String instruction
    ) {
    }

    /** 选角是否给出了有效旁白音色（其余角色缺失也能用旁白兜底，故以旁白为准）。 */
    public boolean ok() {
        return narrator != null && narrator.instruction() != null && !narrator.instruction().isBlank();
    }

    /**
     * 整理为稳定名册：旁白（id 0）居首，随后是去重后的角色（按 id；忽略 id≤0 / 重复 / 无 instruction 的条目）。
     * 旁白 instruction 缺失时用 {@link NarrationCharacter#DEFAULT_NARRATOR_INSTRUCTION} 兜底。从不返回空列表。
     */
    public List<NarrationCharacter> roster() {
        List<NarrationCharacter> out = new ArrayList<>();
        out.add(NarrationCharacter.narrator(narrator == null ? null : narrator.instruction()));
        if (characters != null) {
            Set<Integer> seen = new LinkedHashSet<>();
            seen.add(NarrationCharacter.NARRATOR_ID);
            for (RawCharacter c : characters) {
                if (c == null || c.id() == null || c.id() <= NarrationCharacter.NARRATOR_ID) {
                    continue;
                }
                if (!seen.add(c.id())) {
                    continue;
                }
                if (c.instruction() == null || c.instruction().isBlank()) {
                    continue;
                }
                out.add(new NarrationCharacter(
                        c.id(),
                        c.name() == null || c.name().isBlank() ? "#" + c.id() : c.name().trim(),
                        normalize(c.gender(), GENDERS),
                        normalize(c.age(), AGES),
                        c.instruction().trim(),
                        false));
            }
        }
        return out;
    }

    private static String normalize(String value, Set<String> allowed) {
        if (value == null) {
            return "unknown";
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(v) ? v : "unknown";
    }

    /** 解析模型回复的 JSON 文本；无法解析时抛 {@link IllegalArgumentException}。 */
    public static CastAnalysisResponse parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty AI response");
        }
        try {
            return MAPPER.readValue(content, CastAnalysisResponse.class);
        } catch (Exception first) {
            String extracted = extractJsonObject(content);
            if (extracted != null) {
                try {
                    return MAPPER.readValue(extracted, CastAnalysisResponse.class);
                } catch (Exception ignored) {
                    // fall through to throwing the original failure
                }
            }
            throw new IllegalArgumentException("unparseable AI response: " + first.getMessage(), first);
        }
    }

    /** 截取首个 '{' 到末个 '}' 的子串，容忍模型在 JSON 外包裹少量说明文字 / 代码围栏。 */
    private static String extractJsonObject(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return content.substring(start, end + 1);
        }
        return null;
    }
}
