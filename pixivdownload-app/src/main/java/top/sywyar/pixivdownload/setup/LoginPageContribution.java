package top.sywyar.pixivdownload.setup;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.plugin.api.web.page.*;
import java.util.List;

/** 认证页面自己声明不可删除区域，编辑器与正式渲染器不认识认证业务。 */
@Configuration(proxyBeanMethods = false)
public class LoginPageContribution {
    @Bean top.sywyar.pixivdownload.plugin.security.OperationRequirement loginRequirement(
            @org.springframework.beans.factory.annotation.Value("${setup.login-verification-policies:}") String policies) {
        return new top.sywyar.pixivdownload.plugin.security.OperationRequirement("core:login", policies.isBlank() ? List.of()
                : java.util.Arrays.stream(policies.split(",", -1)).map(String::strip).toList());
    }
    @Bean EditablePage loginEditablePage() throws java.io.IOException {
        var regions = new java.util.ArrayList<PageRegion>();
        regions.add(new PageRegion("brand", "header.title", "", false, false));
        regions.add(new PageRegion("preferences", "editor.preferences", "", false, false));
        regions.add(new PageRegion("authentication", "editor.authentication", "", true, false));
        regions.add(new PageRegion("admin-card", "admin.title", "authentication", true, false));
        regions.add(new PageRegion("invite-card", "invite.title", "authentication", true, true));
        for (String[] field : List.of(new String[]{"admin-title", "admin.title"}, new String[]{"username", "field.username"},
                new String[]{"password", "field.password"}, new String[]{"remember", "remember"},
                new String[]{"login-error", "editor.error"}, new String[]{"login-submit", "button.login"}, new String[]{"invite-link", "invite.use-code"}))
            regions.add(new PageRegion(field[0], field[1], "admin-card", false, false));
        for (String[] field : List.of(new String[]{"invite-title", "invite.title"}, new String[]{"invite-code", "invite.code"},
                new String[]{"invite-error", "editor.error"}, new String[]{"invite-submit", "invite.submit"}, new String[]{"back-link", "invite.back"}))
            regions.add(new PageRegion(field[0], field[1], "invite-card", false, false));
        try (var resource = new org.springframework.core.io.ClassPathResource("static/login/login-layout.json").getInputStream()) {
            return new EditablePage("core:login", "page.title", "login", "/login.html", true, regions,
                    new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8),
                    "/login/login-preview.mjs", List.of("/css/pixiv-layout.css", "/login/login.css"),
                    List.of(new PageInteraction.Target("login.verification", "button.login", "core:login", "admin-card",
                            "{\"type\":\"object\",\"properties\":{\"clientAddress\":{\"type\":\"string\",\"maxLength\":128},"
                                    + "\"rememberMe\":{\"type\":\"boolean\"}},\"required\":[\"clientAddress\",\"rememberMe\"],\"additionalProperties\":false}",
                            List.of(top.sywyar.pixivdownload.plugin.api.security.OperationVerification.INTERACTION), 16)));
        }
    }
}
