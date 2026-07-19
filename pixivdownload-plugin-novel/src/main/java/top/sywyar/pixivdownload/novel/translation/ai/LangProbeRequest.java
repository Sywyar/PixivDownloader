package top.sywyar.pixivdownload.novel.translation.ai;

import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

/**
 * 一次「语言名 → 规范 BCP-47 代码」的轻量 AI 探测请求体。用于在系列批量翻译开始前，把用户输入的自由
 * 文本目标语言（如「简体中文」「english」「日本語」）转换为与 {@link TranslationResponse#lang()} 同一规范
 * 的代码（如 {@code zh-CN} / {@code en-US} / {@code ja-JP}），从而让首章也能直接走 DB 跳过、不必为
 * 解析语言再发一次完整翻译请求。
 *
 * <p>提示词使用与 {@link TranslationRequest} 一致的固定英文版本，要求严格 JSON 输出（与
 * {@link LangProbeResponse#parse(String)} 配套）。
 */
public record LangProbeRequest(String targetLanguage) {

    /** 调用类型标签，供 {@link top.sywyar.pixivdownload.ai.AiChatClient} 标识本次请求用途。 */
    public static final String CALL_TYPE = "translation.lang-probe";

    private static final String SYSTEM_PROMPT = """
            You convert a free-text language name into a canonical BCP-47 language code.

            Rules:
            1. If the input is a real, existing human (natural) language, return its canonical BCP-47 code.
               Prefer region-qualified codes such as zh-CN (Simplified Chinese), zh-TW (Traditional Chinese),
               en-US (English), ja-JP (Japanese), ko-KR (Korean), fr-FR, de-DE, ru-RU.
            2. If the input is NOT a real language (gibberish, random characters, or not a language at all),
               return an empty string and mark it invalid.
            3. Do not translate, summarise, or comment. Only output the code.
            4. Output STRICT JSON ONLY (no markdown, no code fences, no extra text) with EXACTLY these fields:
               {"code": "<BCP-47 code, or empty string when invalid>",
                "valid": true | false}
            """;

    /** 把请求转换为 OpenAI 兼容协议的对话消息序列（系统规范 + 用户输入）。 */
    public List<AiChatMessage> toMessages() {
        String lang = targetLanguage == null ? "" : targetLanguage.trim();
        String userPrompt = "Language: " + lang;
        return List.of(
                AiChatMessage.system(SYSTEM_PROMPT),
                AiChatMessage.user(userPrompt));
    }
}
