package top.sywyar.pixivdownload.ai.translation;

import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

/**
 * 一次「Pixiv 小说文本翻译」的 AI 请求体。把调用 {@link top.sywyar.pixivdownload.ai.AiService} 所需的
 * <b>业务变量</b>（目标语言、待翻译文本）与 <b>输出规范</b>（提示词 + 期望的 JSON 结构）封装在一起，
 * 与底层 OpenAI 兼容协议解耦。
 *
 * <p>提示词统一使用<b>同一份英文版本</b>（不随界面语言变化），并要求模型：
 * <ul>
 *   <li>先判断用户填写的目标语言是否为真实存在的人类语言；不存在时以状态码标记、不翻译；</li>
 *   <li>原样保留所有 Pixiv 标记符号（{@code [newpage]} / {@code [[rb:..]]} / {@code [uploadedimage:id]} 等），
 *       仅翻译其中的自然语言文本，以便译文仍可被 {@link top.sywyar.pixivdownload.novel.NovelMarkupParser} 渲染与合订；</li>
 *   <li>严格输出 JSON，便于 {@link TranslationResponse#parse(String)} 解析。</li>
 * </ul>
 *
 * @param targetLanguage 用户填写的目标语言（自由文本，如 {@code 简体中文} / {@code english} / {@code 日本語}）
 * @param sourceText     待翻译的一段 Pixiv 小说原始 markup（整章或其中一个分段）
 */
public record TranslationRequest(String targetLanguage, String sourceText) {

    /**
     * 系统提示词（英文，固定）。明确翻译规则、标记保留规则与 JSON 输出规范。
     * {@code {target}} 由 {@link #toMessages()} 在用户消息中给出，这里只定义不变的规则。
     */
    private static final String SYSTEM_PROMPT = """
            You are a professional literary translator for Pixiv novels.

            Your job: translate the user-provided novel text into the target language the user specifies.

            Follow these rules strictly:
            1. First decide whether the requested target language is a real, existing human (natural) language.
               If it is NOT a real language (gibberish, random characters, or not a language at all),
               respond with status "invalid_language" and leave "text" as an empty string. Do not translate.
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
            6. Output STRICT JSON ONLY (no markdown, no code fences, no extra text) with EXACTLY these fields:
               {"status": "ok" | "invalid_language",
                "lang": "<canonical BCP-47 code of the target language, e.g. zh-CN, en-US, ja-JP>",
                "text": "<the translated text, or an empty string when status is invalid_language>"}
               The "lang" field must always be the canonical BCP-47 code of the target language
               (prefer region-qualified codes such as zh-CN, en-US, ja-JP).""";

    /** 把请求转换为 OpenAI 兼容协议的对话消息序列（系统规范 + 用户输入）。 */
    public List<AiChatMessage> toMessages() {
        String lang = targetLanguage == null ? "" : targetLanguage.trim();
        String text = sourceText == null ? "" : sourceText;
        String userPrompt = "Target language: " + lang + "\n\nText to translate:\n" + text;
        return List.of(
                AiChatMessage.system(SYSTEM_PROMPT),
                AiChatMessage.user(userPrompt));
    }
}
