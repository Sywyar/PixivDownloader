package top.sywyar.pixivdownload.ai.narration;

import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

/**
 * 一次「有声书选角」的 AI 请求体（两段式中的<b>第一段</b>）。读入一段小说正文，产出稳定的<b>说话人音色
 * 名册</b>：旁白 + 每个有台词的角色，各配一段固定的英文 Control Instruction 音色画像。
 *
 * <p>这是「同一个人描述必须准确」的来源——名册一次生成、按章节缓存、被
 * {@link SpeakerAttributionRequest} 逐句引用，从而同一角色的所有台词复用同一音色。本请求<b>不</b>判定逐句
 * 说话人（那是第二段 {@link SpeakerAttributionRequest} 的事），也<b>不</b>负责断句。
 *
 * <p>遵循 {@link top.sywyar.pixivdownload.ai.AiService} 实体范式：固定英文提示词、{@link #toMessages()}、
 * 期望严格 JSON 以便 {@link CastAnalysisResponse#parse(String)} 解析。音色画像<b>统一英文</b>（即便原文是
 * 中文 / 日文）。正文按 {@link #MAX_CAST_CHARS} 截断取头部样本以控制 token。
 *
 * @param passage             用于选角的小说正文（建议为整章或其头部样本；调用方提供）
 * @param knownCharacterNames 已在花名册中的角色名（原文称谓）；模型须复用这些名字、不重述其音色，仅新增未在册者。
 *                            用于跨章 / 系列共享花名册时让已选角角色保持稳定，可为空
 */
public record CastAnalysisRequest(String passage, List<String> knownCharacterNames) {

    /** 调用类型标签，供 {@link top.sywyar.pixivdownload.ai.AiService} 在日志中标识本次请求用途。 */
    public static final String CALL_TYPE = "tts.narration.cast";

    /** 选角正文的字符上限，超出取头部样本，控制 token 体量。 */
    public static final int MAX_CAST_CHARS = 8000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 便捷构造：无「已有角色」上下文（首次选角 / 无持久化花名册）。 */
    public CastAnalysisRequest(String passage) {
        this(passage, List.of());
    }

    private static final String SYSTEM_PROMPT = """
            You are a casting director for an audiobook. You read a passage of a novel (in any
            language) and design the speaking voices for a multi-voice narration.

            Identify the NARRATOR voice plus every distinct CHARACTER who speaks dialogue in the
            passage. For each, write one English "instruction": a vivid voice-design description a
            TTS engine can use to synthesize that voice, covering apparent gender, age, vocal
            timbre, pace and temperament. Keep each description SELF-CONSISTENT and specific enough
            to be reused for ALL of that character's lines — it is the same voice every time.

            Rules:
            1. The narrator is always present; it has id 0 (do not list it among "characters").
            2. Number characters with stable ids starting at 1, in order of first appearance.
            3. "name" is how the character is referred to in the text (their name or appellation),
               in the ORIGINAL language of the passage; use a short label if unnamed
               (e.g. "the old man").
            4. "gender" is one of: male, female, unknown.
               "age" is one of: child, teen, young, middle, elderly, unknown.
            5. Every "instruction" MUST be written in ENGLISH regardless of the passage language.
               Describe ONLY the voice; never include any sentence text.
            6. Merge aliases of the same person into ONE character. Do not invent characters who
               never speak. Use an empty "characters" array when the passage is pure narration.
            7. If a "Known cast names" list is provided in the user message, any speaker matching one
               of those names MUST reuse that EXACT name; do not rename or re-describe known
               characters. Only add genuinely NEW speakers not already in that list.
            8. Output STRICT JSON ONLY (no markdown, no code fences, no commentary) with EXACTLY:
               {"narrator":{"instruction":"<english voice description>"},
                "characters":[{"id":1,"name":"<original-language label>","gender":"<male|female|unknown>",
                "age":"<child|teen|young|middle|elderly|unknown>","instruction":"<english voice description>"}]}""";

    /** 把请求转换为 OpenAI 兼容协议的对话消息序列（系统规范 + 可选已有角色 + 用于选角的正文样本）。 */
    public List<AiChatMessage> toMessages() {
        StringBuilder user = new StringBuilder();
        String known = knownNamesJson();
        if (known != null) {
            user.append("Known cast names (reuse these EXACT names; only add new speakers):\n")
                    .append(known).append("\n\n");
        }
        user.append("Passage to cast (identify the speakers and design their voices):\n").append(sample());
        return List.of(AiChatMessage.system(SYSTEM_PROMPT), AiChatMessage.user(user.toString()));
    }

    /** 已有角色名的 JSON 数组；为空时返回 {@code null}（不附带该段）。 */
    private String knownNamesJson() {
        if (knownCharacterNames == null || knownCharacterNames.isEmpty()) {
            return null;
        }
        List<String> safe = knownCharacterNames.stream()
                .filter(n -> n != null && !n.isBlank()).map(String::trim).toList();
        if (safe.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(safe);
        } catch (Exception e) {
            return null;
        }
    }

    /** 取正文头部样本（不超过 {@link #MAX_CAST_CHARS}）。 */
    private String sample() {
        String text = passage == null ? "" : passage;
        return text.length() <= MAX_CAST_CHARS ? text : text.substring(0, MAX_CAST_CHARS);
    }
}
