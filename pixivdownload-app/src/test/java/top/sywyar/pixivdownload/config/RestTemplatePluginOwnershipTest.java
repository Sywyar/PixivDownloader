package top.sywyar.pixivdownload.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("父容器 RestTemplate 配置的插件所有权边界")
class RestTemplatePluginOwnershipTest {

    private static final Set<String> CORE_REST_TEMPLATE_BEANS = Set.of(
            "restTemplate",
            "pixivCredentialRestTemplate",
            "downloadRestTemplate",
            "pixivImageRestTemplate");

    private static final Set<String> PLUGIN_PRIVATE_REST_TEMPLATE_BEANS = Set.of(
            "aiRestTemplate",
            "aiProxyRestTemplate",
            "pushRestTemplate",
            "pushProxyRestTemplate",
            "ttsMetadataRestTemplate",
            "narrationTtsRestTemplate",
            "narrationTtsProxyRestTemplate",
            "narrationTtsProbeRestTemplate",
            "narrationTtsProbeProxyRestTemplate");

    @Test
    @DisplayName("父配置声明核心客户端且不声明 AI、Push、TTS 私有客户端")
    void parentConfigurationDoesNotDeclarePluginPrivateRestTemplates() {
        Set<String> beanNames = declaredRestTemplateBeanNames();

        assertThat(beanNames).isNotEmpty();
        assertThat(beanNames).containsAll(CORE_REST_TEMPLATE_BEANS);
        assertThat(beanNames).doesNotContainAnyElementsOf(PLUGIN_PRIVATE_REST_TEMPLATE_BEANS);
    }

    private static Set<String> declaredRestTemplateBeanNames() {
        Set<String> beanNames = new LinkedHashSet<>();
        for (Method method : RestTemplateConfig.class.getDeclaredMethods()) {
            Bean bean = method.getDeclaredAnnotation(Bean.class);
            if (bean == null || !RestTemplate.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            boolean hasName = addExplicitNames(beanNames, bean.name());
            hasName = addExplicitNames(beanNames, bean.value()) || hasName;
            if (!hasName) {
                beanNames.add(method.getName());
            }
        }
        return Set.copyOf(beanNames);
    }

    private static boolean addExplicitNames(Set<String> beanNames, String[] candidates) {
        boolean found = false;
        for (String candidate : candidates) {
            if (!candidate.isBlank()) {
                beanNames.add(candidate);
                found = true;
            }
        }
        return found;
    }
}
