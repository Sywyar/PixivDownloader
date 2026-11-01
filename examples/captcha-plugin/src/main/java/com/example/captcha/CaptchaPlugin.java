package com.example.captcha;

import java.time.Duration;
import java.util.List;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.http.*;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.api.web.page.*;

public final class CaptchaPlugin extends Plugin implements PixivPluginProvider {
    public CaptchaPlugin(PluginWrapper wrapper) { super(wrapper); }
    public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return "captcha-example"; }
            public String displayName() { return "name"; }
            public String description() { return "description"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<String> credentialKeys() {
                return List.of("captcha-example.altcha-secret", "captcha-example.turnstile-secret");
            }
            public List<WebRouteContribution> routes() {
                return List.of(WebRouteContribution.publicRoute("/captcha-example/**"));
            }
            public List<StaticResourceContribution> staticResources() {
                return List.of(new StaticResourceContribution("classpath:/web/", "/captcha-example/"));
            }
            public List<I18nContribution> i18n() {
                return List.of(new I18nContribution("captcha-example", "i18n.web.captcha-example"));
            }
            public List<GuiConfigContribution> guiConfigContributions() {
                return List.of(new GuiConfigContribution(
                        List.of(new GuiConfigGroupContribution("captcha-example", "name", "captcha-example", 500, true)),
                        List.of(field("altcha-secret", GuiConfigFieldType.PASSWORD, 0),
                                field("turnstile-sitekey", GuiConfigFieldType.STRING, 1),
                                field("turnstile-secret", GuiConfigFieldType.PASSWORD, 2),
                                field("turnstile-hostname", GuiConfigFieldType.STRING, 3),
                                field("turnstile-test-mode", GuiConfigFieldType.BOOL, 4))));
            }
        };
    }
    @Configuration(proxyBeanMethods = false)
    public static class Wiring {
        @Bean(destroyMethod = "close") OutboundHttpClient captchaHttp(OutboundHttpClientFactory factory) {
            return factory.open(new OutboundHttpClientProfile(Duration.ofSeconds(3), Duration.ofSeconds(5),
                    OutboundHttpRoute.inherit(), OutboundHttpRedirectPolicy.NEVER, OutboundHttpCookiePolicy.DISABLED, 4, 4));
        }
        @Bean OperationVerification altcha(PluginCredentials credentials) { return new AltchaVerification(credentials, false); }
        @Bean OperationVerification altchaPbkdf2(PluginCredentials credentials) { return new AltchaVerification(credentials, true); }
        @Bean OperationVerification turnstile(PluginCredentials credentials, Environment environment, OutboundHttpClient captchaHttp) {
            return new TurnstileVerification(credentials, environment, captchaHttp);
        }
        @Bean PageContribution captchaPages() {
            return new PageContribution() {
                public void validate(String type, String configuration) {
                    if (components().stream().noneMatch(component -> component.type().equals(type)))
                        throw new IllegalArgumentException("PAGE_COMPONENT_UNAVAILABLE");
                }
                public List<PageComponent> components() {
                    return List.of(component("altcha"), component("turnstile"), component("altcha-pbkdf2"));
                }
            };
        }
    }
    private static PageComponent component(String method) {
        return new PageComponent("captcha-example:" + method, 1, method, "captcha-example",
                "/captcha-example/" + (method.startsWith("altcha") ? "altcha" : method) + ".mjs", "", "{\"type\":\"object\",\"additionalProperties\":false}", "{}",
                true, List.of(), List.of(), new PageInteraction.Participant("captcha-" + method, OperationVerification.INTERACTION));
    }
    private static GuiConfigFieldContribution field(String name, GuiConfigFieldType type, int order) {
        return new GuiConfigFieldContribution(
                "captcha-example." + name,
                "captcha-example",
                name,
                "configuration-help",
                "captcha-example",
                type,
                type == GuiConfigFieldType.BOOL ? "false" : "",
                order,
                type == GuiConfigFieldType.PASSWORD,
                GuiConfigEffect.HOT_RELOAD,
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }
}
