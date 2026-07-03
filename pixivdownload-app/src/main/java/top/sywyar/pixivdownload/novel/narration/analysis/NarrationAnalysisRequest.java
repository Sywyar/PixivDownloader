package top.sywyar.pixivdownload.novel.narration.analysis;

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
              A. Attribute every sentence to the voice that should read it — a character for that
                 character's own spoken dialogue AND their inner voice (thoughts / 心声); the narrator
                 (id 0) only for objective, ownerless text.
              B. Annotate each sentence with a short delivery note (emotion / tone / pace) for THIS line.
              C. Discover NEW speaking characters not yet in the cast and design their voices.
              D. For EXISTING characters, EITHER propose a compatible refinement, OR flag a conflict, OR
                 leave them unchanged — see rules 5-7.

            Definitions:
            - The narrator is cast id 0. It is the voice for OBJECTIVE, OWNERLESS text ONLY — text not
              voiced from inside any single character's head: external scene-setting and description,
              the action and events of the world, speech tags ("X said", "she whispered"), and detached
              omniscient narration. Use id 0 when no specific character owns the line, and whenever the
              speaker is uncertain or not in the cast.
            - A character's INNER VOICE — their thoughts, inner monologue, feelings, wishes, memories and
              silent reactions (心声) that are NOT spoken aloud — belongs to THAT character and is read in
              their OWN voice, exactly like their spoken dialogue. Attribute such a sentence to that
              character's id, NOT to the narrator. Decide ownership by WHOSE subjective viewpoint the
              sentence comes from, regardless of grammatical person; a sentence that merely describes a
              character from the OUTSIDE (their visible action / appearance) is still the narrator.
            - FIRST-PERSON works: the narrating "I" is a CHARACTER (the protagonist), not the objective
              narrator. Attribute the protagonist's whole first-person stream — both their narration and
              their inner thoughts — to that ONE protagonist character so it stays a single consistent
              voice; reserve id 0 only for any genuinely detached / objective text (often there is little
              or none). Keep the protagonist as ONE character with a single stable id for the whole work.
              If they were first enrolled before their name was known (e.g. as "I" or an unnamed label) and
              their real name appears in this segment, do NOT create a second character — REUSE that same
              id and correct the name via "updatedCharacters" (rule 5). Other characters still speak and
              think in their own voices.
            - A voice "instruction" is a DETAILED English voice-design persona a TTS engine can
              synthesize as ONE stable voice. It MUST concretely specify EVERY trait below — never a
              vague one-liner: apparent gender; age or age-range; pitch register (low / mid / high);
              vocal timbre and quality (pick concrete descriptors, e.g. warm, clear, husky, smooth,
              bright, nasal, resonant, breathy, gravelly); pace / tempo; rhythm / cadence; baseline
              emotional affect and temperament; and articulation / accent (default to a neutral,
              accent-light standard register unless the text clearly implies otherwise). Describe a
              STABLE timbre — the SAME voice every time that character speaks — NOT a momentary mood.
              Write it in ENGLISH even though the sentences are not.
            - A "delivery" note is a SHORT English phrase (<= 8 words) for one line's emotion / tone /
              pace, e.g. "angry, faster" or "whispering, afraid". Empty string for a plain line. It
              modulates one line only; it never redefines the character's voice.

            Rules:
            1. Output EXACTLY one line object per input sentence. Never merge, split, drop, reorder,
               translate or rewrite sentences. "i" is the sentence's zero-based index in the segment.
            2. "speaker" is the cast id whose voice reads that sentence. Attribute a sentence to a
               CHARACTER (non-zero id) when it is EITHER that character's dialogue spoken aloud (typically
               inside quotation marks 「」, 『』, “”, "", etc. or carrying a speech tag) OR that character's
               inner voice — their thoughts, feelings, wishes, memories or silent reactions (心声). In a
               first-person work the narrating protagonist's narration ALSO goes to their character id
               (see the first-person rule in the definitions). Use the narrator (id 0) ONLY for objective,
               ownerless text — external description, the world's action, speech tags and detached
               narration — and whenever you are unsure. Decide by WHOSE subjective viewpoint owns the
               sentence, NOT merely by who it is about: a sentence that only describes a character from
               the outside (their visible action or appearance) is the narrator, not that character. When
               a sentence mixes a quoted line with a narration tag, give it to the character if the spoken
               words dominate, else to the narrator. Reuse existing ids for known voices.
            3. For a speaker NOT in the current cast, create them in "newCharacters" with a fresh integer
               id greater than every existing cast id (unique within this response) and use that SAME id
               as the line's "speaker". Only add characters who speak aloud or whose inner voice (心声) is
               read — including the first-person protagonist; merge aliases of one person into ONE
               character (name it per rule 4's alias format); never duplicate someone already in the cast.
            4. Each new character needs "name" (the appellation in the ORIGINAL language; a short label
               if unnamed). When the SAME character is known by MULTIPLE names or aliases, format the
               name as mainName(alias1, alias2): mainName is their most formal and complete name,
               immediately followed by half-width parentheses listing the remaining aliases separated by
               a comma and a space, every part in the original language. With a single name, output it
               ALONE without parentheses, and NEVER join multiple names with slashes, ampersands or any
               other separator outside this parenthesized form. The character also needs "gender"
               (male|female|unknown), "age"
               (child|teen|young|middle|elderly|unknown), and a DETAILED English "instruction" that
               specifies EVERY trait listed in the definition above (gender, age, pitch register,
               timbre & quality, pace, rhythm, baseline affect / temperament, articulation / accent).
               Use one or two full sentences — never a vague one-liner — so a TTS engine reproduces
               the SAME distinct voice every time. Make each character's voice clearly DISTINGUISHABLE
               from the narrator and from the other cast members (vary pitch, timbre, pace and
               temperament); do not give two characters near-identical instructions.
            5. Use "updatedCharacters" to refine an EXISTING character IN PLACE, keeping the SAME id (which
               keeps their voice consistent). Each entry has "id" plus either or both of:
                 - "instruction": a full, detailed refined English voice description — ONLY a COMPATIBLE
                   refinement (same person, same direction as the GIVEN instruction, just richer and
                   non-contradicting);
                 - "name": a corrected / enriched appellation in the ORIGINAL language (rule 4's alias
                   format) — use this ONLY when you now know a better name for the SAME person, e.g. a
                   first-person or previously-unnamed character whose real name is revealed in this
                   segment. NEVER use "name" to merge two genuinely DIFFERENT people.
               Do NOT change gender or age. Be conservative — most segments need none.
            6. Use "conflicts" when the GIVEN instruction of an existing character is, on clear and
               significant evidence in THIS segment, either:
                 - "contradiction": directly contradicted (e.g. the text shows a different gender, age, or
                   temperament than the stored instruction), or
                 - "incomplete": materially incomplete (missing strongly-evidenced traits that would
                   change how the voice should sound).
               Provide "id", "type" ("contradiction" | "incomplete"), a SHORT English "reason", and a
               "suggestion" (the full, detailed English instruction you would use, covering all the
               traits above). Do NOT silently overwrite —
               conflicts are for a human to resolve. Raise a conflict ONLY when evidence is clear and
               significant; otherwise stay silent or use "updatedCharacters".
            7. A character appears in AT MOST ONE of "newCharacters" / "updatedCharacters" / "conflicts"
               per response. Keep every voice SELF-CONSISTENT across the whole work; prefer reusing or
               refining over inventing. The narrator (id 0) may be refined or conflicted the same way.
            8. Output STRICT JSON ONLY (no markdown, no code fences, no commentary) with EXACTLY:
               {"lines":[{"i":0,"speaker":0,"delivery":""}],
                "newCharacters":[{"id":3,"name":"<original-language label>","gender":"<male|female|unknown>","age":"<child|teen|young|middle|elderly|unknown>","instruction":"<english voice description>"}],
                "updatedCharacters":[{"id":1,"name":"<optional corrected original-language name>","instruction":"<optional refined english voice description>"}],
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
