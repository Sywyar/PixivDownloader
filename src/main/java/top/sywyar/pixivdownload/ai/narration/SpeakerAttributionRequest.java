package top.sywyar.pixivdownload.ai.narration;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

/**
 * 一次「逐句说话人归属」的 AI 请求体（两段式中的<b>第二段</b>）。给定<b>已冻结的名册</b>
 * （{@link CastAnalysisRequest} 产出）与一批待朗读的句子，判定每句由名册里的哪个 id 朗读：对白用说话角色
 * 的 id、叙述 / 描写 / 名册外的说话人一律归旁白（id 0）。
 *
 * <p>同时可为每句给出一小段英文 {@code delivery}（情绪 / 语气微调），它<b>不</b>改动角色的基底音色画像，
 * 只调制这一句。把说话人判定与音色画像分离（这里只引用名册 id、不重述音色），是音色跨句一致的关键。
 *
 * <p>本请求<b>不</b>负责断句——句子由调用方切好按顺序传入，数组下标即每句的 {@code i}。提示词固定英文，
 * 期望严格 JSON 以便 {@link SpeakerAttributionResponse#parse(String)} 解析。
 *
 * @param cast      已冻结的名册（仅用于把句子映射到既有 id；本请求只取 id/name/gender/age 作上下文）
 * @param sentences 本批待归属的句子，按朗读顺序排列
 */
public record SpeakerAttributionRequest(List<NarrationCharacter> cast, List<String> sentences) {

    /** 调用类型标签，供 {@link top.sywyar.pixivdownload.ai.AiService} 在日志中标识本次请求用途。 */
    public static final String CALL_TYPE = "tts.narration.attribution";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You assign each sentence of a novel passage to the voice that should read it, for a
            multi-voice audiobook. You are given a fixed CAST (roster of voices, each with an id)
            and a JSON array of sentences. For EACH sentence decide which cast id speaks it: use the
            speaking character's id for spoken dialogue, and id 0 (the narrator) for all narration,
            description, and any line whose speaker is not in the cast.

            You may add a short English "delivery" note (at most 8 words) capturing the emotion,
            tone or pace of THIS specific line (e.g. "angry, faster" or "whispering, afraid"). Leave
            "delivery" as an empty string for a plain line. The delivery note never changes the
            character's base voice; it only modulates this one line.

            Rules:
            1. Output EXACTLY one object per input sentence; never merge, split, drop, reorder,
               translate or rewrite sentences.
            2. "i" is the zero-based index of the sentence in the input array.
            3. "speaker" MUST be an id that exists in the cast; if unsure, use 0 (the narrator).
            4. "delivery" is a SHORT English phrase or an empty string; no parentheses, no quotes,
               and never the sentence text itself.
            5. Output STRICT JSON ONLY (no markdown, no code fences, no commentary) with EXACTLY:
               {"segments":[{"i":0,"speaker":0,"delivery":""}]}
               one object per input sentence, in ascending "i" order.""";

    /** 把请求转换为 OpenAI 兼容协议的对话消息序列（系统规范 + 名册上下文 + 句子数组）。 */
    public List<AiChatMessage> toMessages() {
        return List.of(
                AiChatMessage.system(SYSTEM_PROMPT),
                AiChatMessage.user("Cast (fixed roster of voices):\n" + castJson()
                        + "\n\nSentences to assign (JSON array; each sentence's array index is its \"i\"):\n"
                        + sentencesJson()));
    }

    /** 名册压缩视图（仅 id/name/gender/age，省去音色画像以控 token）。 */
    private String castJson() {
        List<CastEntry> entries = (cast == null ? List.<NarrationCharacter>of() : cast).stream()
                .map(c -> new CastEntry(c.id(), c.name(), c.gender(), c.age()))
                .toList();
        try {
            return MAPPER.writeValueAsString(entries);
        } catch (Exception e) {
            return "[{\"id\":0,\"name\":\"Narrator\"}]";
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
                             @JsonProperty("gender") String gender, @JsonProperty("age") String age) {
    }
}
