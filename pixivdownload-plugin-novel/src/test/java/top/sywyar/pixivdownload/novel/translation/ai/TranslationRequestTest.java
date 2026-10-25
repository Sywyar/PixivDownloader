package top.sywyar.pixivdownload.novel.translation.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("翻译请求消息构造")
class TranslationRequestTest {

    @Test
    @DisplayName("无映射表时用户消息不含 Glossary 段")
    void noGlossarySection() {
        List<AiChatMessage> messages = new TranslationRequest("简体中文", "本文").toMessages();
        String user = messages.get(messages.size() - 1).content();
        assertFalse(user.contains("Glossary"));
        assertTrue(user.contains("Target language: 简体中文"));
        assertTrue(user.contains("本文"));
    }

    @Test
    @DisplayName("含映射表时把条目以 JSON 注入用户消息")
    void glossarySectionInjected() {
        TranslationRequest req = new TranslationRequest("简体中文", "ハルヒが来た",
                List.of(new GlossaryTerm("ハルヒ", "zh-CN", "春日")));
        List<AiChatMessage> messages = req.toMessages();
        String user = messages.get(messages.size() - 1).content();
        assertTrue(user.contains("Glossary"));
        assertTrue(user.contains("ハルヒ"));
        assertTrue(user.contains("春日"));
        assertTrue(user.contains("zh-CN"));
    }

    @Test
    @DisplayName("系统提示词固定为英文且要求回报新名词")
    void systemPromptDescribesGlossary() {
        List<AiChatMessage> messages = new TranslationRequest("english", "text").toMessages();
        String system = messages.get(0).content();
        assertEquals(AiChatMessage.ROLE_SYSTEM, messages.get(0).role());
        assertTrue(system.contains("glossary"));
    }

    @Test
    @DisplayName("正文和标题请求保留术语 JSON 字段、转义及参考标题顺序")
    void serializesOptionalLists() {
        List<GlossaryTerm> terms = Arrays.asList(new GlossaryTerm("春\"日", "zh-CN", "译\n名"), null);
        String glossary = "[{\"source\":\"春\\\"日\",\"lang\":\"zh-CN\",\"target\":\"译\\n名\"},null]";
        String body = new TranslationRequest(" zh-CN ", " 本文\n", " 标题 ", " 简介 ", terms)
                .toMessages().get(1).content();
        assertEquals("Target language: zh-CN\n\nGlossary (preferred term translations):\n" + glossary
                + "\n\nTitle to translate:\n标题\n\nDescription to translate:\n简介\n\nText to translate:\n 本文\n", body);

        String title = new TitleTranslationRequest(" zh-CN ", " 标题 ", " 简介 ", terms,
                List.of(new TitleTranslationRequest.TitleReference("原文", "译文")))
                .toMessages().get(1).content();
        assertEquals("Target language: zh-CN\n\nGlossary (preferred term translations):\n" + glossary
                + "\n\nReference chapter titles (already translated in this series):\n"
                + "[{\"source\":\"原文\",\"target\":\"译文\"}]"
                + "\n\nTitle to translate:\n标题\n\nDescription to translate:\n简介", title);
    }

    @Test
    @DisplayName("可选列表为空时只保留待翻译内容")
    void omitsEmptyLists() {
        assertEquals("Target language: \n\nText to translate:\n",
                new TranslationRequest(null, null, null, null, null).toMessages().get(1).content());
        assertEquals("Target language: \n\nTitle to translate:\n",
                new TitleTranslationRequest(null, null, null, List.of(), null).toMessages().get(1).content());
    }

    @Test
    @DisplayName("可选列表序列化失败不阻止正文和标题请求构造")
    void omitsUnserializableLists() {
        List<GlossaryTerm> brokenTerms = new AbstractList<>() {
            @Override public GlossaryTerm get(int index) { throw new IllegalStateException("unavailable"); }
            @Override public int size() { return 1; }
        };
        assertEquals("Target language: en\n\nText to translate:\ntext",
                new TranslationRequest("en", "text", brokenTerms).toMessages().get(1).content());
        assertEquals("Target language: en\n\nTitle to translate:\ntitle",
                new TitleTranslationRequest("en", "title", brokenTerms, List.of()).toMessages().get(1).content());
    }
}
