package top.sywyar.pixivdownload.ai.narration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「AI 听小说」合并单次分析的 AI 响应体，对应 {@link NarrationAnalysisRequest} 约定的 JSON 输出规范
 * （{@code {"lines":[..],"newCharacters":[..],"updatedCharacters":[..],"conflicts":[..]}}）。
 *
 * <p>{@link #parse(String)} 解析模型回复（带代码围栏兜底）。模型输出可能不规范（缺项、下标缺失 / 越界、
 * 引用名册外的 speaker、数量对不上），调用方应：
 * <ul>
 *   <li>用 {@link #normalizedTo(int, Set)} 拿到<b>严格对齐到输入句数</b>的逐句结果：每句恰好一条、按下标升序、
 *       speaker 不在合法 id 集合内即归旁白（0）、缺失的句子归旁白；</li>
 *   <li>用 {@link #newCharacters()} 拿到去重 / 归一后的新角色（带模型分配的临时 id）；</li>
 *   <li>用 {@link #updatedCharacters()} 拿到「已有角色 id → 补充后的英文画像」；</li>
 *   <li>用 {@link #conflicts()} 拿到冲突上报（仅保留合法 type + 非空 suggestion 的条目）。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NarrationAnalysisResponse(
        @JsonProperty("lines") List<RawLine> rawLines,
        @JsonProperty("newCharacters") List<RawCharacter> rawNewCharacters,
        @JsonProperty("updatedCharacters") List<RawUpdate> rawUpdatedCharacters,
        @JsonProperty("conflicts") List<RawConflict> rawConflicts
) {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Set<String> GENDERS = Set.of("male", "female", "unknown");
    private static final Set<String> AGES = Set.of("child", "teen", "young", "middle", "elderly", "unknown");
    private static final Set<String> CONFLICT_TYPES =
            Set.of(NarrationConflict.TYPE_CONTRADICTION, NarrationConflict.TYPE_INCOMPLETE);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawLine(
            @JsonProperty("i") Integer index,
            @JsonProperty("speaker") Integer speaker,
            @JsonProperty("delivery") String delivery
    ) {
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawUpdate(
            @JsonProperty("id") Integer id,
            @JsonProperty("name") String name,
            @JsonProperty("instruction") String instruction
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawConflict(
            @JsonProperty("id") Integer id,
            @JsonProperty("type") String type,
            @JsonProperty("reason") String reason,
            @JsonProperty("suggestion") String suggestion
    ) {
    }

    /**
     * 严格对齐到 {@code count} 句：返回恰好 {@code count} 条、下标升序。优先按合法 {@code i} 归位；缺失 / 越界
     * {@code i} 的对象按出现顺序回填空槽；仍空的句子归旁白。{@code speaker} 不在 {@code validSpeakerIds}
     * 内时归旁白（{@code validSpeakerIds} 应为「当前名册 id ∪ 本响应新角色临时 id」）。
     */
    public List<NarrationLineVoice> normalizedTo(int count, Set<Integer> validSpeakerIds) {
        if (count <= 0) {
            return List.of();
        }
        NarrationLineVoice[] slots = new NarrationLineVoice[count];
        List<RawLine> leftovers = new ArrayList<>();
        if (rawLines != null) {
            for (RawLine raw : rawLines) {
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
        for (RawLine raw : leftovers) {
            while (cursor < count && slots[cursor] != null) {
                cursor++;
            }
            if (cursor >= count) {
                break;
            }
            slots[cursor] = normalizeOne(raw, cursor, validSpeakerIds);
            cursor++;
        }
        List<NarrationLineVoice> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(slots[i] != null ? slots[i] : NarrationLineVoice.narratorAt(i));
        }
        return out;
    }

    private static NarrationLineVoice normalizeOne(RawLine raw, int index, Set<Integer> validSpeakerIds) {
        int speaker = raw.speaker() != null && validSpeakerIds != null && validSpeakerIds.contains(raw.speaker())
                ? raw.speaker()
                : NarrationCharacter.NARRATOR_ID;
        String delivery = raw.delivery() == null ? "" : raw.delivery().trim();
        return new NarrationLineVoice(index, speaker, delivery);
    }

    /**
     * 去重 / 归一后的新角色（带模型分配的临时 id）：忽略 id 为空 / ≤0、缺 name、缺 instruction 的条目；
     * 同一临时 id 仅保留首个；gender/age 归一到受控枚举（未知 → {@code unknown}）。来源恒为 AI 生成
     * （{@code narrator=false}、{@code editedByUser=false}）。
     */
    public List<NarrationCharacter> newCharacters() {
        List<NarrationCharacter> out = new ArrayList<>();
        if (rawNewCharacters == null) {
            return out;
        }
        Set<Integer> seen = new LinkedHashSet<>();
        for (RawCharacter c : rawNewCharacters) {
            if (c == null || c.id() == null || c.id() <= NarrationCharacter.NARRATOR_ID) {
                continue;
            }
            if (c.name() == null || c.name().isBlank()) {
                continue;
            }
            if (c.instruction() == null || c.instruction().isBlank()) {
                continue;
            }
            if (!seen.add(c.id())) {
                continue;
            }
            out.add(new NarrationCharacter(
                    c.id(),
                    c.name().trim(),
                    normalize(c.gender(), GENDERS),
                    normalize(c.age(), AGES),
                    c.instruction().trim(),
                    false,
                    false));
        }
        return out;
    }

    /**
     * 对已有角色的兼容性补充：「角色 id → 补充后的完整英文画像」。忽略 id 为空、instruction 为空的条目；
     * 同一 id 取最后一条。
     */
    public Map<Integer, String> updatedCharacters() {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (rawUpdatedCharacters == null) {
            return out;
        }
        for (RawUpdate u : rawUpdatedCharacters) {
            if (u == null || u.id() == null) {
                continue;
            }
            if (u.instruction() == null || u.instruction().isBlank()) {
                continue;
            }
            out.put(u.id(), u.instruction().trim());
        }
        return out;
    }

    /**
     * 对已有角色的<b>受控改名</b>：「角色 id → 更准确的称谓（原文语言）」，复用 {@code updatedCharacters} 条目里
     * 可选的 {@code name} 字段。典型场景是第一人称主角先以「I / 未命名」临时称谓入册、真实姓名在后段才揭晓——模型
     * 据此按<b>同一 id</b>（保持音色一致）给出新名，落库层据此并入同一角色而非插入重复角色。忽略 id 为空、name
     * 为空的条目；同一 id 取最后一条。
     */
    public Map<Integer, String> renamedCharacters() {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (rawUpdatedCharacters == null) {
            return out;
        }
        for (RawUpdate u : rawUpdatedCharacters) {
            if (u == null || u.id() == null) {
                continue;
            }
            if (u.name() == null || u.name().isBlank()) {
                continue;
            }
            out.put(u.id(), u.name().trim());
        }
        return out;
    }

    /**
     * 已有角色的冲突上报：仅保留 id 非空、type 合法（{@code contradiction} / {@code incomplete}）、
     * suggestion 非空的条目（reason 可空，归一为空串）。
     */
    public List<NarrationConflict> conflicts() {
        List<NarrationConflict> out = new ArrayList<>();
        if (rawConflicts == null) {
            return out;
        }
        for (RawConflict c : rawConflicts) {
            if (c == null || c.id() == null) {
                continue;
            }
            String type = c.type() == null ? "" : c.type().trim().toLowerCase(Locale.ROOT);
            if (!CONFLICT_TYPES.contains(type)) {
                continue;
            }
            if (c.suggestion() == null || c.suggestion().isBlank()) {
                continue;
            }
            String reason = c.reason() == null ? "" : c.reason().trim();
            out.add(new NarrationConflict(c.id(), type, reason, c.suggestion().trim()));
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
    public static NarrationAnalysisResponse parse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty AI response");
        }
        try {
            return MAPPER.readValue(content, NarrationAnalysisResponse.class);
        } catch (Exception first) {
            String extracted = extractJsonObject(content);
            if (extracted != null) {
                try {
                    return MAPPER.readValue(extracted, NarrationAnalysisResponse.class);
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
