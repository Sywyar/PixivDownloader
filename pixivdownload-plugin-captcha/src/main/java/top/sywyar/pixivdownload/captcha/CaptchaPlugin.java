package top.sywyar.pixivdownload.captcha;

import java.time.Duration;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.pf4j.Plugin;
import org.pf4j.PluginWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.LocaleBundlePolicy;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;
import top.sywyar.pixivdownload.plugin.api.credential.PluginCredentials;
import top.sywyar.pixivdownload.plugin.api.http.*;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.web.*;
import top.sywyar.pixivdownload.plugin.api.web.page.*;

public final class CaptchaPlugin extends Plugin implements PixivPluginProvider {
    private static final List<GuiConfigContribution> GUI_CONFIG = createGuiConfigContributions();

    public CaptchaPlugin(PluginWrapper wrapper) { super(wrapper); }
    public PixivFeaturePlugin featurePlugin() {
        return new PixivFeaturePlugin() {
            public String id() { return "captcha"; }
            public String displayName() { return "plugin.name"; }
            public String description() { return "plugin.summary"; }
            public PluginKind kind() { return PluginKind.FEATURE; }
            public List<String> credentialKeys() {
                return List.of("captcha.altcha-secret", "captcha.turnstile-secret", "captcha.recaptcha-secret");
            }
            public List<WebRouteContribution> routes() {
                return List.of(WebRouteContribution.publicRoute("/captcha/**"),
                        new WebRouteContribution("/api/captcha/images/**", AccessPolicy.PUBLIC, java.util.Set.of(HttpMethod.GET), false));
            }
            public List<StaticResourceContribution> staticResources() {
                return List.of(new StaticResourceContribution("classpath:/web/", "/captcha/"));
            }
            public List<I18nContribution> i18n() {
                return List.of(new I18nContribution("captcha", "i18n.web.captcha"));
            }
            public List<GuiConfigContribution> guiConfigContributions() {
                return GUI_CONFIG;
            }
        };
    }
    @Configuration(proxyBeanMethods = false)
    public static class Wiring {
        private final CaptchaDiagnostics diagnostics;
        public Wiring(PluginCredentials credentials, MessageResolver messages, LocaleBundlePolicy locales) {
            diagnostics = new CaptchaDiagnostics(ResourceBundleMessageResolver.of(messages,
                    CaptchaPlugin.class.getClassLoader(), locales, "i18n.web.captcha"));
            if (credentials.read("captcha.altcha-secret").isEmpty()) {
                byte[] key = new byte[32];
                new SecureRandom().nextBytes(key);
                credentials.update(Map.of("captcha.altcha-secret", HexFormat.of().formatHex(key)));
                diagnostics.keyInitialized();
            }
        }
        @Bean(destroyMethod = "close") OutboundHttpClient captchaHttp(OutboundHttpClientFactory factory) {
            return factory.open(new OutboundHttpClientProfile(Duration.ofSeconds(3), Duration.ofSeconds(5),
                    OutboundHttpRoute.inherit(), OutboundHttpRedirectPolicy.NEVER, OutboundHttpCookiePolicy.DISABLED, 4, 4));
        }
        @Bean OperationVerification altcha(PluginCredentials credentials) { return new AltchaVerification(credentials, false, diagnostics); }
        @Bean OperationVerification altchaPbkdf2(PluginCredentials credentials) { return new AltchaVerification(credentials, true, diagnostics); }
        @Bean OperationVerification turnstile(PluginCredentials credentials, Environment environment, OutboundHttpClient captchaHttp) {
            return new TurnstileVerification(credentials, environment, captchaHttp, diagnostics);
        }
        @Bean OperationVerification recaptcha(PluginCredentials credentials, Environment environment, OutboundHttpClient captchaHttp) {
            return new RecaptchaVerification(credentials, environment, captchaHttp, diagnostics);
        }
        @Bean(destroyMethod = "close") ImageChallengeStore imageChallenges() { return new ImageChallengeStore(); }
        @Bean CaptchaImageController captchaImages(ImageChallengeStore store) { return new CaptchaImageController(store); }
        @Bean OperationVerification textCaptcha(ImageChallengeStore store) { return new ImageVerification(store, false, diagnostics); }
        @Bean OperationVerification sliderCaptcha(ImageChallengeStore store) { return new ImageVerification(store, true, diagnostics); }
        @Bean PageContribution captchaPages() {
            return new PageContribution() {
                public void validate(String type, String configuration) {
                    if (components().stream().noneMatch(component -> component.type().equals(type)))
                        throw new IllegalArgumentException("PAGE_COMPONENT_UNAVAILABLE");
                }
                public List<PageComponent> components() {
                    return List.of(component("altcha"), component("turnstile"), component("altcha-pbkdf2"),
                            component("text"), component("slider"), component("recaptcha"));
                }
                public List<PageComponentCategory> componentCategories() {
                    return List.of(new PageComponentCategory("captcha:verification", "category", "captcha", "category.help",
                            components().stream().map(PageComponent::type).toList()));
                }
            };
        }
    }
    private static List<GuiConfigContribution> createGuiConfigContributions() {
        var fields = List.of(
                field("altcha-secret", GuiConfigFieldType.PASSWORD, 0),
                field("turnstile-sitekey", GuiConfigFieldType.STRING, 1),
                field("turnstile-secret", GuiConfigFieldType.PASSWORD, 2),
                field("turnstile-hostname", GuiConfigFieldType.STRING, 3),
                field("turnstile-test-mode", GuiConfigFieldType.BOOL, 4),
                field("recaptcha-sitekey", GuiConfigFieldType.STRING, 5),
                field("recaptcha-secret", GuiConfigFieldType.PASSWORD, 6),
                field("recaptcha-hostname", GuiConfigFieldType.STRING, 7),
                field("recaptcha-test-mode", GuiConfigFieldType.BOOL, 8)
        );
        var layouts = fields.stream().map(field -> {
            String provider = field.key().substring("captcha.".length(), field.key().indexOf('-'));
            return new GuiConfigFieldLayoutContribution(
                    field.key(),
                    provider,
                    "settings." + provider,
                    "captcha",
                    field.order()
            );
        }).toList();
        var section = new GuiConfigSectionContribution(
                "captcha.providers",
                "captcha",
                "",
                "",
                "captcha",
                "settings.component",
                "settings.component.help",
                "",
                "",
                List.of(),
                GuiConfigSectionLayout.CARD_SWITCHER,
                0,
                layouts,
                List.of(),
                List.of()
        );
        return List.of(new GuiConfigContribution(
                List.of(new GuiConfigGroupContribution("captcha", "plugin.name", "captcha", 500, true)),
                fields,
                List.of(section)
        ));
    }
    private static PageComponent component(String method) {
        String module = switch (method) {
            case "altcha", "altcha-pbkdf2" -> "altcha";
            case "text", "slider" -> "image";
            default -> method;
        };
        return new PageComponent("captcha:" + method, 1, method, "captcha",
                "/captcha/" + module + ".mjs", "", "{\"type\":\"object\",\"additionalProperties\":false}", "{}",
                true, List.of(), List.of(), new PageInteraction.Participant("captcha-" + method, OperationVerification.INTERACTION));
    }
    private static GuiConfigFieldContribution field(String name, GuiConfigFieldType type, int order) {
        return new GuiConfigFieldContribution(
                "captcha." + name,
                "captcha",
                name,
                name + ".help",
                "captcha",
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
