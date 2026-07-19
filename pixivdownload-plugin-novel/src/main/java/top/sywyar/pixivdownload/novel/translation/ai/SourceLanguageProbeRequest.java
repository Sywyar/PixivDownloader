package top.sywyar.pixivdownload.novel.translation.ai;

import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.List;

/**
 * 一次「源语言是否等于目标语言」的轻量 AI 探测请求体。在正式整章翻译之前调用一次：把<b>正文开头的一小段
 * 样本文本</b>连同已解析出的<b>目标语言代码</b>一起发给模型，让模型判定样本所属语言是否与目标语言相同。
 * 相同则说明该小说原文已经是目标语言，调用方可直接跳过整章翻译，省下一次完整翻译请求。
 *
 * <p>提示词使用与 {@link TranslationRequest} 一致的固定英文版本，要求严格 JSON 输出（与
 * {@link SourceLanguageProbeResponse#parse(String)} 配套）。
 */
public record SourceLanguageProbeRequest(String targetLanguageCode, String sample) {

    /** 调用类型标签，供 {@link top.sywyar.pixivdownload.ai.AiChatClient} 标识本次请求用途。 */
    public static final String CALL_TYPE = "translation.source-lang-probe";

    private static final String SYSTEM_PROMPT = """
            You detect the language of a SAMPLE text and decide whether it is already written
            in the SAME language as a given TARGET language.

            Rules:
            1. Identify the natural language or languages of the SAMPLE text.
            2. Decide whether all substantial natural-language prose in the SAMPLE is already
               written in the TARGET language. They count as the "same" when an ordinary reader
               would consider the SAMPLE already in the TARGET language; ignore a few mixed-in
               proper nouns, numbers, or foreign loanwords. If the SAMPLE contains a substantial
               passage in another language, return same=false even if the beginning matches.
               Treat regional variants of one written language as the same (e.g. en-US vs en-GB),
               and treat Simplified vs Traditional Chinese (zh-CN vs zh-TW) as the same language
               for this purpose.
            3. Do not translate, summarise, or comment. Judge the language only.
            4. Output STRICT JSON ONLY (no markdown, no code fences, no extra text) with EXACTLY
               these fields:
               {"sourceLang": "<BCP-47 code of the SAMPLE language, or mixed>",
                "same": true | false}
            """;

    /** 把请求转换为 OpenAI 兼容协议的对话消息序列（系统规范 + 目标语言代码 + 正文样本）。 */
    public List<AiChatMessage> toMessages() {
        String code = targetLanguageCode == null ? "" : targetLanguageCode.trim();
        String text = sample == null ? "" : sample.trim();
        String userPrompt = "TARGET language: " + code + "\n\nSAMPLE:\n" + text;
        return List.of(
                AiChatMessage.system(SYSTEM_PROMPT),
                AiChatMessage.user(userPrompt));
    }
}
