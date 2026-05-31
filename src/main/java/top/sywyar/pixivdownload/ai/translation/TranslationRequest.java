package top.sywyar.pixivdownload.ai.translation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

/**
 * 一次「Pixiv 小说文本翻译」的 AI 请求体。把调用 {@link top.sywyar.pixivdownload.ai.AiService} 所需的
 * <b>业务变量</b>（目标语言、待翻译文本、可选随段同译的标题与简介、名词映射表）与 <b>输出规范</b>
 * （提示词 + 期望的 JSON 结构）封装在一起，与底层 OpenAI 兼容协议解耦。
 *
 * <p>提示词统一使用<b>同一份英文版本</b>（不随界面语言变化），并要求模型：
 * <ul>
 *   <li>先判断用户填写的目标语言是否为真实存在的人类语言；不存在时以状态码标记、不翻译；</li>
 *   <li>原样保留所有 Pixiv 标记符号（{@code [newpage]} / {@code [[rb:..]]} / {@code [uploadedimage:id]} 等），
 *       仅翻译其中的自然语言文本，以便译文仍可被 {@link top.sywyar.pixivdownload.novel.NovelMarkupParser} 渲染与合订；</li>
 *   <li>对随请求提供的名词映射表中<b>语言匹配</b>的条目，强制使用既定译名，保证专有名词一致；</li>
 *   <li>把遇到的、映射表中尚无的新专有名词回报到输出 JSON 的 {@code glossary} 数组，便于自动入库复用；</li>
 *   <li>当输入还附带 {@code sourceTitle} 时，按相同术语规则把标题翻译为<b>单行短标题</b>放入 JSON 的 {@code title} 字段；
 *       未附 Title 时该字段为空。这样章节正文与章节标题在<b>同一次 AI 请求</b>中翻译，保证术语与上下文一致；</li>
 *   <li>当输入还附带 {@code sourceDescription} 时，按相同术语规则把简介翻译并放入 JSON 的 {@code description}
 *       字段；未附 Description 时该字段为空。简介中的 {@code <br>} / {@code <a>} 等 HTML 标签必须原样保留，
 *       仅翻译其中的自然语言文本，与正文 markup 处理一致；</li>
 *   <li>严格输出 JSON，便于 {@link TranslationResponse#parse(String)} 解析。</li>
 * </ul>
 *
 * @param targetLanguage    用户填写的目标语言（自由文本，如 {@code 简体中文} / {@code english} / {@code 日本語}）
 * @param sourceText        待翻译的一段 Pixiv 小说原始 markup（整章或其中一个分段）
 * @param sourceTitle       可选随段同译的章节标题；仅在分段的首段附上、后续段传 {@code null} 以省 token
 * @param sourceDescription 可选随段同译的章节简介（Pixiv caption，HTML 受限）；仅在首段附上、后续段传 {@code null}
 * @param glossary          名词映射表条目（可含多种目标语言，模型仅对语言匹配项强制套用）；可为空
 */
public record TranslationRequest(String targetLanguage, String sourceText,
                                 String sourceTitle, String sourceDescription,
                                 List<GlossaryTerm> glossary) {

    /** 调用类型标签，供 {@link top.sywyar.pixivdownload.ai.AiService} 在日志中标识本次请求用途。 */
    public static final String CALL_TYPE = "translation";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 便捷构造：无标题、无简介、无名词映射表。 */
    public TranslationRequest(String targetLanguage, String sourceText) {
        this(targetLanguage, sourceText, null, null, List.of());
    }

    /** 便捷构造：无标题、无简介、含名词映射表。 */
    public TranslationRequest(String targetLanguage, String sourceText, List<GlossaryTerm> glossary) {
        this(targetLanguage, sourceText, null, null, glossary);
    }

    /** 便捷构造：含标题、无简介、含名词映射表。 */
    public TranslationRequest(String targetLanguage, String sourceText,
                              String sourceTitle, List<GlossaryTerm> glossary) {
        this(targetLanguage, sourceText, sourceTitle, null, glossary);
    }

    /**
     * 系统提示词（英文，固定）。明确翻译规则、标记保留规则、名词映射规则、标题 / 简介随段同译规则与 JSON 输出规范。
     * 业务变量（目标语言、映射表、待译文本、可选标题与简介）由 {@link #toMessages()} 在用户消息中给出，这里只定义不变的规则。
     */
    private static final String SYSTEM_PROMPT = """
            You are a professional literary translator for Pixiv novels.

            Your job: translate the user-provided novel text into the target language the user specifies,
            and (when also provided) translate the work title and the work description in the SAME request.

            Follow these rules strictly:
            1. First decide whether the requested target language is a real, existing human (natural) language.
               If it is NOT a real language (gibberish, random characters, or not a language at all),
               respond with status "invalid_language", leave "text" as an empty string and both "title" and
               "description" as null. Do not translate.
            2. If it IS a real language, translate the text into it and respond with status "ok".
            3. Preserve EVERY Pixiv markup token EXACTLY in place. Never translate, delete, reorder or alter
               the token syntax, ids, urls or numbers. The tokens are:
               [newpage], [chapter:TITLE], [[rb:BASE > RUBY]], [[jumpuri:TEXT > URL]],
               [jump:PAGE], [uploadedimage:ID], [pixivimage:ID].
               For [chapter:TITLE], [[rb:BASE > RUBY]] and [[jumpuri:TEXT > URL]] you MUST translate the
               human-readable parts (TITLE, BASE, RUBY, TEXT) while keeping the surrounding token structure,
               ids, urls and numbers unchanged.
            4. Keep line breaks and paragraph structure identical to the input. Do not add or remove blank lines.
            5. Translate faithfully and naturally; do not summarise, censor, add notes or commentary.
            6. A glossary of preferred term translations may be provided in the user message as a JSON array
               named "glossary"; each item is {"source": "...", "lang": "<BCP-47>", "target": "..."}.
               For every item whose "lang" equals the canonical code of THIS target language, you MUST render
               that "source" term exactly as its "target" everywhere it appears (in body, title AND description).
               Ignore items whose "lang" differs from the target language.
            7. Whenever you translate a proper noun, character name, place, organization or other recurring
               named term into the target language, and that term is NOT already covered by a matching-language
               glossary item, add it to the "glossary" array of your JSON output as
               {"source": "<original term>", "target": "<your translation>"} so it can be reused for
               consistency. Keep "source" in its original language. Use an empty array when there are none.
            8. If the user message includes a "Title to translate" line, ALSO translate it into the same
               target language: keep the original meaning, transliterate proper nouns when there is no
               idiomatic translation, do NOT add quotation marks, brackets, footnotes, or commentary, and
               output it as ONE LINE (no line breaks). Apply rule 6 to the title as well, so a term
               appearing in both the title and the body uses the SAME translation. Put the translated
               title into the "title" field of the JSON. If no "Title to translate" line is provided,
               set "title" to null.
            9. If the user message includes a "Description to translate" block, ALSO translate it into the
               same target language. The description may contain a restricted subset of HTML tags
               (typically <br>, <br/>, <a href="...">...</a>, <strong>, <em>); preserve every tag EXACTLY
               in place — do not translate, delete, reorder or alter tag names, attributes, urls or numbers —
               and only translate the human-readable text outside / inside the tags. Apply rule 6 to the
               description as well. Keep its line / paragraph structure identical to the input. Do NOT add
               quotation marks, footnotes or commentary. Put the translated description into the
               "description" field of the JSON. If no "Description to translate" block is provided,
               set "description" to null.
            10. Output STRICT JSON ONLY (no markdown, no code fences, no extra text) with EXACTLY these fields:
               {"status": "ok" | "invalid_language",
                "lang": "<canonical BCP-47 code of the target language, e.g. zh-CN, en-US, ja-JP>",
                "text": "<the translated text, or an empty string when status is invalid_language>",
                "title": "<the translated title, or null when no title was provided>",
                "description": "<the translated description, or null when no description was provided>",
                "glossary": [{"source": "...", "target": "..."}, ...]}
               The "lang" field must always be the canonical BCP-47 code of the target language
               (prefer region-qualified codes such as zh-CN, en-US, ja-JP).""";

    /** 把请求转换为 OpenAI 兼容协议的对话消息序列（系统规范 + 用户输入）。 */
    public List<AiChatMessage> toMessages() {
        String lang = targetLanguage == null ? "" : targetLanguage.trim();
        String text = sourceText == null ? "" : sourceText;
        StringBuilder userPrompt = new StringBuilder("Target language: ").append(lang);
        String glossaryJson = glossaryJson();
        if (glossaryJson != null) {
            userPrompt.append("\n\nGlossary (preferred term translations):\n").append(glossaryJson);
        }
        if (sourceTitle != null && !sourceTitle.isBlank()) {
            userPrompt.append("\n\nTitle to translate:\n").append(sourceTitle.trim());
        }
        if (sourceDescription != null && !sourceDescription.isBlank()) {
            userPrompt.append("\n\nDescription to translate:\n").append(sourceDescription.trim());
        }
        userPrompt.append("\n\nText to translate:\n").append(text);
        return List.of(
                AiChatMessage.system(SYSTEM_PROMPT),
                AiChatMessage.user(userPrompt.toString()));
    }

    /** 把映射表序列化为 JSON 数组字符串；为空（或序列化失败）时返回 {@code null}（不附带映射段）。 */
    private String glossaryJson() {
        if (glossary == null || glossary.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(glossary);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
