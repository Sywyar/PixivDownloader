package top.sywyar.pixivdownload.translation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.ai.AiChatClient;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DisplayName("独立翻译引擎与完整保存")
class TranslationEngineTest {
    @Test
    @DisplayName("AI 的默认分段配置经实际入口生效，不套用普通翻译请求上限")
    void aiInheritsItsSegmentSetting() throws Exception {
        var config = new TranslationConfig();
        config.setAiSegmentSize(3);
        when(documents.snapshot(anyString())).thenReturn(documents);
        when(documents.getNovel(42)).thenReturn(new TranslationDocuments.Content(42, null, "one\ntwo", "title", "description"));
        when(ai.chat(eq(top.sywyar.pixivdownload.translation.ai.SourceLanguageProbeRequest.CALL_TYPE), any(), any()))
                .thenReturn(new top.sywyar.pixivdownload.ai.model.AiChatResult("{\"same\":false}", "stop", null, null, null));
        when(ai.chat(eq(top.sywyar.pixivdownload.translation.ai.TranslationRequest.CALL_TYPE), any(), any()))
                .thenReturn(new top.sywyar.pixivdownload.ai.model.AiChatResult("{\"status\":\"ok\",\"lang\":\"en\",\"text\":\"translated\"}", "stop", null, null, null));
        var service = new NovelTranslationService(config, ai, documents, machine, TranslationTestMessages.messageResolver(), () -> true);
        assertThat(service.translateChapter(42, "English", 0, true, "en", null, true, false, false).status())
                .isEqualTo(AiNovelTranslationService.Status.OK);
        verify(ai, times(2)).chat(eq(top.sywyar.pixivdownload.translation.ai.TranslationRequest.CALL_TYPE), any(), any());
        verify(documents).saveTranslation(42, "en", "translated\ntranslated", null, null);
        verifyNoInteractions(machine);
    }

    @Test
    @DisplayName("每个引擎使用自己的超时、代理与连接预算，客户端随 owner 关闭")
    void httpProfilesFollowEachEngine() {
        var config = new TranslationConfig();
        config.getYoudao().setReadTimeoutSeconds(17);
        config.getYoudao().setRequestConcurrency(20);
        config.getYoudao().setUseProxy(true);
        config.getLibretranslate().setReadTimeoutSeconds(45);
        var factory = mock(top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory.class);
        var first = mock(top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient.class);
        var second = mock(top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient.class);
        when(factory.open(any())).thenReturn(first, second);
        var configuration = new TranslationPluginConfiguration();
        configuration.youdaoHttp(factory, config).close();
        configuration.libretranslateHttp(factory, config).close();
        var profiles = org.mockito.ArgumentCaptor.forClass(top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientProfile.class);
        verify(factory, times(2)).open(profiles.capture());
        var youdao = profiles.getAllValues().get(0);
        var libre = profiles.getAllValues().get(1);
        assertThat(youdao.readTimeout()).isEqualTo(java.time.Duration.ofSeconds(17));
        assertThat(youdao.maxConnectionsPerRoute()).isGreaterThanOrEqualTo(20);
        assertThat(youdao.route()).isEqualTo(top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute.configuredProxy());
        assertThat(libre.readTimeout()).isEqualTo(java.time.Duration.ofSeconds(45));
        assertThat(libre.route()).isEqualTo(top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRoute.direct());
        verify(first).close();
        verify(second).close();
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    @DisplayName("中文环境下失败日志保留执行关联和厂商错误码，但不包含正文或凭据")
    void logsProviderFailureWithoutPrivateContent(org.springframework.boot.test.system.CapturedOutput output) {
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
        try {
            var service = service();
            when(machine.translate(eq("libretranslate"), eq("body"), eq("zh-CN"), eq(0), anyString(), eq("body")))
                    .thenThrow(new MachineTranslationClient.Failure("translation.service-not-enabled", "provider_rejected", 200, "110"));
            assertThat(service.translateChapter(42, "zh-CN", 0, true, null, null, true, false, false).status())
                    .isEqualTo(AiNovelTranslationService.Status.ERROR);
            assertThat(output.getAll()).contains("Translation started:", "Translation failed:", "documentId=42",
                    "engine=libretranslate", "target=zh-CN", "stage=body", "providerCode=110", "httpStatus=200", "durationMs=");
            assertThat(output.getAll()).doesNotContain("fixture-secret", "翻译失败");
            verify(documents, never()).saveTranslation(anyLong(), any(), any(), any(), any());
        } finally {
            java.util.Locale.setDefault(previous);
        }
    }
    private final AiChatClient ai = mock(AiChatClient.class);
    private final TranslationDocuments documents = mock(TranslationDocuments.class);
    private final MachineTranslationClient machine = mock(MachineTranslationClient.class);
    private NovelTranslationService service() {
        var config = new TranslationConfig();
        config.setEngine("libretranslate");
        when(documents.snapshot(anyString())).thenReturn(documents);
        when(documents.getNovel(42)).thenReturn(new TranslationDocuments.Content(42, null, "body", "title", "description"));
        return new NovelTranslationService(config, ai, documents, machine, TranslationTestMessages.messageResolver(), () -> true);
    }
    @Test
    @DisplayName("没有 AI 提供者仍可翻译并保存，语言解析不调用 AI")
    void translatesWithoutAi() {
        var service = service();
        when(machine.translate(eq("libretranslate"), anyString(), eq("en-US"), eq(0), anyString(), anyString()))
                .thenAnswer(call -> "translated " + call.getArgument(1));
        assertThat(service.resolveLangCode("English")).isEqualTo("en-US");
        assertThat(service.translateChapter(42, "English", 0, false, null, null, true, true, true).status())
                .isEqualTo(AiNovelTranslationService.Status.OK);
        verify(documents).saveTranslation(42, "en-US", "translated body", "translated title", "translated description");
        verifyNoInteractions(ai);
    }
    @Test
    @DisplayName("元数据翻译失败不写入已完成正文，也不覆盖既有译文")
    void doesNotSavePartialTranslation() {
        var service = service();
        when(machine.translate(eq("libretranslate"), eq("body"), eq("en-US"), eq(0), anyString(), eq("body"))).thenReturn("translated body");
        when(machine.translate(eq("libretranslate"), eq("title"), eq("en-US"), eq(0), anyString(), eq("title"))).thenThrow(new IllegalStateException("translation.remote-error"));
        assertThat(service.translateChapter(42, "en", 0, true, null, null, true, true, true).status())
                .isEqualTo(AiNovelTranslationService.Status.ERROR);
        verify(documents, never()).saveTranslation(anyLong(), any(), any(), any(), any());
        verifyNoInteractions(ai);
    }
    @Test
    @DisplayName("已译内容跳过；非 AI 引擎明确拒绝 AI 术语表")
    void skipsExistingAndRejectsUnsupportedGlossary() {
        var service = service();
        when(documents.translated(42, "en-US", true, true, true)).thenReturn(true);
        assertThat(service.translateChapter(42, "en", 0, false, null, null, true, true, true).status())
                .isEqualTo(AiNovelTranslationService.Status.SKIPPED);
        assertThat(service.translateChapter(42, "en", 0, true, null, 7L, true, true, true).status())
                .isEqualTo(AiNovelTranslationService.Status.ERROR);
        verifyNoInteractions(ai, machine);
    }
}
