package top.sywyar.pixivdownload.novel.translation.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

/**
 * 一次「Pixiv 小说 / 系列标题翻译」的 AI 请求体。仅用于<b>独立</b>翻译某一标题的场景（最典型的是「翻译整个系列」
 * 后单独翻译系列名）；章节标题本身已与正文随同一个 {@link TranslationRequest} 一起翻译，不再走此请求。
 *
 * <p>除目标语言 + 待译标题外，还可附带：
 * <ul>
 *   <li>{@link GlossaryTerm} 名词映射表条目，让 AI 对专有名词遵循既定译名；</li>
 *   <li>{@link TitleReference} 同系列内已存在的「原标题 → 该语言已译标题」对，作为译名风格参考，使新译的系列名
 *       与已译章节标题的风格一致（例如所有章节都用「魔王」一词时，系列名不要冒出来「魔神」）。</li>
 *   <li>{@code sourceDescription} 系列简介：与系列名在<b>同一次请求</b>内一起翻译，避免再多一次 AI 调用。
 *       简介仅依赖名词映射表保证术语一致，不参考章节标题（描述性长文与单行短标题不属于同一文体）。</li>
 * </ul>
 *
 * <p>提示词使用固定的英文版本（不随界面语言变化），要求模型：
 * <ul>
 *   <li>对真实人类语言才翻译；非真实语言时以状态码标记、不翻译；</li>
 *   <li>对映射表中<b>语言匹配</b>的条目强制套用译名；</li>
 *   <li>参考已译章节标题的风格与命名，以与同系列其它标题保持一致；</li>
 *   <li>仅返回单行短标题（不带引号 / 括号 / 注释 / markdown）；</li>
 *   <li>若附带简介，保留其中的受限 HTML 标签（{@code <br>} / {@code <a>} 等），仅翻译自然语言文本；</li>
 *   <li>严格输出 JSON，便于 {@link TitleTranslationResponse#parse(String)} 解析。</li>
 * </ul>
 *
 * @param targetLanguage    用户填写的目标语言（自由文本，如 {@code 简体中文} / {@code english}）
 * @param sourceTitle       待翻译的单行短标题
 * @param sourceDescription 可选：与标题在同一次请求里一起翻译的系列简介；{@code null} 时不译简介
 * @param glossary          名词映射表条目（可含多种目标语言，模型仅对语言匹配项强制套用）；可为空
 * @param referenceTitles   同系列内既有的「原标题 → 该语言已译标题」对（仅传该语言已译完成的章节）；可为空
 */
public record TitleTranslationRequest(String targetLanguage, String sourceTitle,
                                      String sourceDescription,
                                      List<GlossaryTerm> glossary,
                                      List<TitleReference> referenceTitles) {

    /** 调用类型标签，供 {@link top.sywyar.pixivdownload.ai.AiChatClient} 标识本次请求用途。 */
    public static final String CALL_TYPE = "translation.title";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一对「原标题 → 目标语言已译标题」参考样例，供 AI 学习同系列的命名 / 风格习惯。 */
    public record TitleReference(String source, String target) {}

    /** 便捷构造：无简介、无映射表、无参考标题。 */
    public TitleTranslationRequest(String targetLanguage, String sourceTitle) {
        this(targetLanguage, sourceTitle, null, List.of(), List.of());
    }

    /** 便捷构造：无简介，含映射表与参考标题（与早期签名兼容）。 */
    public TitleTranslationRequest(String targetLanguage, String sourceTitle,
                                   List<GlossaryTerm> glossary,
                                   List<TitleReference> referenceTitles) {
        this(targetLanguage, sourceTitle, null, glossary, referenceTitles);
    }

    private static final String SYSTEM_PROMPT = """
            You translate Pixiv novel / novel-series titles into the requested language.

            Rules:
            1. First decide whether the requested target language is a real, existing human (natural) language.
               If it is NOT a real language, respond with status "invalid_language", an empty "title" and a
               null "description".
            2. If it IS a real language, translate the title into it and respond with status "ok".
            3. The input is a SHORT single-line title (work title or series title). Translate it as a natural
               title in the target language: keep the original meaning, transliterate proper nouns when there is
               no idiomatic translation, do NOT add quotation marks, brackets, footnotes, or commentary.
            4. Output the title as ONE LINE only — no markdown, no code fences, no leading or trailing
               whitespace, no line breaks.
            5. A glossary of preferred term translations may be provided in the user message as a JSON array
               named "glossary"; each item is {"source": "...", "lang": "<BCP-47>", "target": "..."}.
               For every item whose "lang" equals the canonical code of THIS target language, you MUST render
               that "source" term exactly as its "target" anywhere it appears (in the title AND, when
               provided, in the description). Ignore items whose "lang" differs from the target language.
            6. A list of "Reference chapter titles" may also be provided as a JSON array of
               {"source": "<original chapter title>", "target": "<already-translated chapter title in THIS
               target language>"}. Treat them as authoritative samples of how this novel series is being
               translated: stay consistent with their terminology, character / place naming, register and
               style. Do NOT copy a chapter title as the answer unless the input title is genuinely identical.
               These references apply to the title ONLY. Do NOT use them as samples for description style;
               descriptions are long-form prose and should rely purely on the glossary for term consistency.
            7. If the user message includes a "Description to translate" block, ALSO translate it into the
               same target language. The description may contain a restricted subset of HTML tags
               (typically <br>, <br/>, <a href="...">...</a>, <strong>, <em>); preserve every tag EXACTLY
               in place — do not translate, delete, reorder or alter tag names, attributes, urls or numbers —
               and only translate the human-readable text outside / inside the tags. Apply rule 5 to the
               description. Keep its line / paragraph structure identical to the input. Do NOT add
               quotation marks, footnotes or commentary. Put the translated description into the
               "description" field of the JSON. If no "Description to translate" block is provided,
               set "description" to null.
            8. Output STRICT JSON ONLY (no markdown, no code fences, no extra text) with EXACTLY these fields:
               {"status": "ok" | "invalid_language",
                "lang": "<canonical BCP-47 code of the target language, e.g. zh-CN, en-US, ja-JP>",
                "title": "<the translated title, or an empty string when status is invalid_language>",
                "description": "<the translated description, or null when no description was provided>"}
               The "lang" field must always be the canonical BCP-47 code of the target language
               (prefer region-qualified codes such as zh-CN, en-US, ja-JP).""";

    /** 把请求转换为 OpenAI 兼容协议的对话消息序列（系统规范 + 用户输入）。 */
    public List<AiChatMessage> toMessages() {
        String lang = targetLanguage == null ? "" : targetLanguage.trim();
        String title = sourceTitle == null ? "" : sourceTitle.trim();
        StringBuilder userPrompt = new StringBuilder("Target language: ").append(lang);
        String glossaryJson = jsonOf(glossary);
        if (glossaryJson != null) {
            userPrompt.append("\n\nGlossary (preferred term translations):\n").append(glossaryJson);
        }
        String refJson = jsonOf(referenceTitles);
        if (refJson != null) {
            userPrompt.append("\n\nReference chapter titles (already translated in this series):\n").append(refJson);
        }
        userPrompt.append("\n\nTitle to translate:\n").append(title);
        if (sourceDescription != null && !sourceDescription.isBlank()) {
            userPrompt.append("\n\nDescription to translate:\n").append(sourceDescription.trim());
        }
        return List.of(
                AiChatMessage.system(SYSTEM_PROMPT),
                AiChatMessage.user(userPrompt.toString()));
    }

    private static String jsonOf(List<?> list) {
        if (list == null || list.isEmpty()) return null;
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
