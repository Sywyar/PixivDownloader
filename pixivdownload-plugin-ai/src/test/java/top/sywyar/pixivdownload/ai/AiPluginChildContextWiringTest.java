package top.sywyar.pixivdownload.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.ai.controller.AiStatusController;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护插件子上下文与宿主父上下文的装配边界：宿主 {@link AiService} 门面与插件自有
 * {@link OpenAiCompatibleAiClient} 同为 {@link AiChatClient}，子上下文中的注入必须收敛到插件自有 client。
 */
@DisplayName("AI 插件子上下文装配")
class AiPluginChildContextWiringTest {

    @Test
    @DisplayName("父上下文暴露宿主 AiService 门面时，插件子上下文以自有 client 装配并注入")
    void childContextPrefersPluginOwnAiChatClientOverHostFacade() {
        try (AnnotationConfigApplicationContext parent = parentContext();
             AnnotationConfigApplicationContext child = childContext(parent)) {

            Map<String, AiChatClient> clients = child.getBeansOfType(AiChatClient.class);
            assertThat(clients).containsOnlyKeys("openAiCompatibleAiClient");
            assertThat(clients.get("openAiCompatibleAiClient"))
                    .isInstanceOf(OpenAiCompatibleAiClient.class);

            AiChatClient resolved = child.getBean(AiChatClient.class);
            assertThat(resolved).isSameAs(clients.get("openAiCompatibleAiClient"));

            AiStatusController statusController = child.getBean(AiStatusController.class);
            assertThat(statusController.status().configured()).isFalse();
        }
    }

    private static AnnotationConfigApplicationContext parentContext() {
        AnnotationConfigApplicationContext parent = new AnnotationConfigApplicationContext();
        parent.register(ParentStubs.class);
        parent.refresh();
        return parent;
    }

    private static AnnotationConfigApplicationContext childContext(
            AnnotationConfigApplicationContext parent) {
        AnnotationConfigApplicationContext child = new AnnotationConfigApplicationContext();
        child.setParent(parent);
        child.register(AiPluginConfiguration.class);
        child.refresh();
        return child;
    }

    @Configuration(proxyBeanMethods = false)
    static class ParentStubs {

        @Bean
        AiChatClient aiService() {
            return new HostFacadeAiClient();
        }

        @Bean
        MessageResolver messages() {
            return new TestMessages();
        }

        @Bean
        LocaleBundlePolicy localeBundlePolicy() {
            // 模拟宿主提供的策略（真实宿主为 CatalogLocaleBundlePolicy）
            return new LocaleBundlePolicy() {
                @Override
                public Locale normalize(Locale requested) {
                    return requested == null ? Locale.getDefault() : requested;
                }

                @Override
                public List<String> resourceSuffixChain(Locale requested) {
                    return List.of("en", "");
                }
            };
        }

        @Bean
        OutboundHttpClientFactory outboundHttpClientFactory() {
            return profile -> new StubHttpClient();
        }
    }

    /** 模拟宿主 {@code AiService} 门面：与插件自有 client 不同，{@link #isConfigured()} 恒为 {@code true}。 */
    private static final class HostFacadeAiClient implements AiChatClient {

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public AiChatResult chat(String callType, List<AiChatMessage> messages,
                                 AiChatOptions options) throws AiClientException {
            throw new UnsupportedOperationException();
        }

        @Override
        public AiChatResult chatTest(String callType, AiClientSettings settings,
                                     List<AiChatMessage> messages,
                                     AiChatOptions options) throws AiClientException {
            throw new UnsupportedOperationException();
        }
    }

    private static final class TestMessages implements MessageResolver {

        @Override
        public String get(String code, Object... args) {
            return code;
        }

        @Override
        public String get(Locale locale, String code, Object... args) {
            return code;
        }

        @Override
        public String getOrDefault(String code, String defaultMessage, Object... args) {
            return defaultMessage;
        }

        @Override
        public String getOrDefault(Locale locale, String code, String defaultMessage, Object... args) {
            return defaultMessage;
        }

        @Override
        public String getForLog(String code, Object... args) {
            return code;
        }
    }

    private static final class StubHttpClient implements OutboundHttpClient {

        @Override
        public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
            return new OutboundHttpStreamResponse(
                    204, "", Map.of(), InputStream.nullInputStream());
        }

        @Override
        public void close() {
        }
    }
}
