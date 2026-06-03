package top.sywyar.pixivdownload.ai.narration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

/**
 * 一次「AI 听小说」<b>合并单次按段分析</b>的 AI 请求体。它取代了早期的两段式（选角 + 逐句归属两次调用）：
 * 把一段连续句子连同<b>当前花名册</b>（每个角色的 id + 英文音色 instruction）一次性发给模型，模型在<b>一次</b>
 * 调用里同时完成——
 * <ol>
 *   <li>给本段<b>每句</b>判定说话人（名册 id；旁白 / 不确定归 0）并附一小段情绪 {@code delivery}；</li>
 *   <li>发现本段出现的<b>新角色</b>并设计其英文音色画像；</li>
 *   <li>对已有角色给出<b>兼容性补充</b>（性格更丰富、方向不变）；</li>
 *   <li>对已有角色上报<b>冲突</b>（当前画像被本段证据完全相反 / 明显不完整），但绝不擅自覆盖。</li>
 * </ol>
 *
 * <p>每段都携带花名册，是「同一角色跨句 / 跨章音色一致」的关键：模型复用既有 id、只新增 / 补充，避免分批生成时
 * 音色漂移。遵循 {@link top.sywyar.pixivdownload.ai.AiService} 实体范式：固定英文提示词、{@link #toMessages()}、
 * 期望严格 JSON 以便 {@link NarrationAnalysisResponse#parse(String)} 解析。音色画像 / delivery <b>统一英文</b>，
 * 目标句子保持原文。<b>不</b>负责断句——句子由调用方切好按顺序传入，数组下标即每句的 {@code i}。
 *
 * @param roster    当前花名册（旁白居首，id 0）；模型据此复用 / 补充 / 上报冲突，仅取 id/name/gender/age/instruction 作上下文
 * @param sentences 本段待分析的连续句子，按朗读顺序排列
 * @param nextId    分配给新角色的起始 id（= 花名册最大 id + 1），模型应给新角色分配 ≥ 此值的 id
 */
public record NarrationAnalysisRequest(List<NarrationCharacter> roster, List<String> sentences, int nextId) {

    /** 调用类型标签，供 {@link top.sywyar.pixivdownload.ai.AiService} 在日志中标识本次请求用途。 */
    public static final String CALL_TYPE = "tts.narration.analysis";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are the analysis engine for a multi-voice audiobook ("AI 听小说"). You receive
            (1) the CURRENT CAST — a roster of voices already established for this work, each with a
            stable integer id and an English voice-design "instruction" — and (2) a SEGMENT of
            consecutive sentences from a novel (in any language). In ONE pass, for the segment:
              A. Attribute every sentence to the voice that should read it.
              B. Annotate each sentence with a short delivery note (emotion / tone / pace) for THIS line.
              C. Discover NEW speaking characters not yet in the cast and design their voices.
              D. For EXISTING characters, EITHER propose a compatible refinement, OR flag a conflict, OR
                 leave them unchanged — see rules 5-7.

            Definitions:
            - The narrator is always cast id 0. Use it for narration, description, and any line whose
              speaker is uncertain or not in the cast.
            - A voice "instruction" is an English persona a TTS engine can synthesize: apparent gender,
              age, vocal timbre, pace, temperament. It is the SAME voice every time that character
              speaks. Write it in ENGLISH even though the sentences are not.
            - A "delivery" note is a SHORT English phrase (<= 8 words) for one line's emotion / tone /
              pace, e.g. "angry, faster" or "whispering, afraid". Empty string for a plain line. It
              modulates one line only; it never redefines the character's voice.

            Rules:
            1. Output EXACTLY one line object per input sentence. Never merge, split, drop, reorder,
               translate or rewrite sentences. "i" is the sentence's zero-based index in the segment.
            2. "speaker" is the cast id reading that sentence. Reuse existing ids for known characters;
               use 0 for narration or when unsure.
            3. For a speaker NOT in the current cast, create them in "newCharacters" with a fresh integer
               id greater than every existing cast id (unique within this response) and use that SAME id
               as the line's "speaker". Only add characters who actually speak; merge aliases of one
               person into ONE character; never duplicate someone already in the cast.
            4. Each new character needs "name" (appellation in the ORIGINAL language; a short label if
               unnamed), "gender" (male|female|unknown), "age"
               (child|teen|young|middle|elderly|unknown), and an English "instruction".
            5. Use "updatedCharacters" ONLY for a COMPATIBLE refinement of an existing character: the same
               person and the same direction as the GIVEN instruction, just richer and non-contradicting.
               Provide "id" and a full refined English "instruction". Do NOT change name, gender or age.
               Be conservative — most segments need none.
            6. Use "conflicts" when the GIVEN instruction of an existing character is, on clear and
               significant evidence in THIS segment, either:
                 - "contradiction": directly contradicted (e.g. the text shows a different gender, age, or
                   temperament than the stored instruction), or
                 - "incomplete": materially incomplete (missing strongly-evidenced traits that would
                   change how the voice should sound).
               Provide "id", "type" ("contradiction" | "incomplete"), a SHORT English "reason", and a
               "suggestion" (the full English instruction you would use). Do NOT silently overwrite —
               conflicts are for a human to resolve. Raise a conflict ONLY when evidence is clear and
               significant; otherwise stay silent or use "updatedCharacters".
            7. A character appears in AT MOST ONE of "newCharacters" / "updatedCharacters" / "conflicts"
               per response. Keep every voice SELF-CONSISTENT across the whole work; prefer reusing or
               refining over inventing. The narrator (id 0) may be refined or conflicted the same way.
            8. Output STRICT JSON ONLY (no markdown, no code fences, no commentary) with EXACTLY:
               {"lines":[{"i":0,"speaker":0,"delivery":""}],
                "newCharacters":[{"id":3,"name":"<original-language label>","gender":"<male|female|unknown>","age":"<child|teen|young|middle|elderly|unknown>","instruction":"<english voice description>"}],
                "updatedCharacters":[{"id":1,"instruction":"<refined english voice description>"}],
                "conflicts":[{"id":2,"type":"<contradiction|incomplete>","reason":"<short english reason>","suggestion":"<full english voice description>"}]}
               "lines" MUST cover every input sentence in ascending "i". Use empty arrays for
               "newCharacters" / "updatedCharacters" / "conflicts" when there are none.""";

    /** 把请求转换为 OpenAI 兼容协议的对话消息序列（系统规范 + 当前花名册 + nextId + 本段句子数组）。 */
    public List<AiChatMessage> toMessages() {
        String user = "Current cast (reuse by id; refine or flag conflicts only when clearly warranted):\n"
                + rosterJson()
                + "\n\nAssign ids >= " + nextId + " to any new speaker.\n\n"
                + "Segment sentences (JSON array; the array index is each sentence's \"i\"):\n"
                + sentencesJson();
        return List.of(AiChatMessage.system(SYSTEM_PROMPT), AiChatMessage.user(user));
    }

    /** 花名册视图（id/name/gender/age/instruction）；模型需据 instruction 判断是否补充 / 上报冲突。 */
    private String rosterJson() {
        List<CastEntry> entries = (roster == null ? List.<NarrationCharacter>of() : roster).stream()
                .filter(c -> c != null)
                .map(c -> new CastEntry(c.id(), c.name(), c.gender(), c.age(), c.controlInstruction()))
                .toList();
        try {
            return MAPPER.writeValueAsString(entries);
        } catch (Exception e) {
            return "[{\"id\":0,\"name\":\"Narrator\",\"gender\":\"unknown\",\"age\":\"unknown\","
                    + "\"instruction\":\"" + NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION + "\"}]";
        }
    }

    private String sentencesJson() {
        List<String> safe = (sentences == null ? List.<String>of() : sentences).stream()
                .map(s -> s == null ? "" : s).toList();
        try {
            return MAPPER.writeValueAsString(safe);
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < safe.size(); i++) {
                sb.append(i).append(": ").append(safe.get(i).replace("\n", " ")).append('\n');
            }
            return sb.toString();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record CastEntry(@JsonProperty("id") int id, @JsonProperty("name") String name,
                             @JsonProperty("gender") String gender, @JsonProperty("age") String age,
                             @JsonProperty("instruction") String instruction) {
    }
}
