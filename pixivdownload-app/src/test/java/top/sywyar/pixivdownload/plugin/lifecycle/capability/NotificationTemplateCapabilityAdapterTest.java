package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.core.notification.NotificationTemplateRegistry;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContribution;
import top.sywyar.pixivdownload.plugin.api.notification.NotificationTemplateContributor;
import top.sywyar.pixivdownload.plugin.lifecycle.PluginCapabilityContributionRegistrar;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityDrain;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityInvocationRegistry;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityPublication;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("通知模板 capability 适配器")
class NotificationTemplateCapabilityAdapterTest {

    @Test
    @DisplayName("模板纯值随精确 publication 发布撤回且重复键原子失败")
    void templatesFollowExactPublicationLifecycle() {
        NotificationTemplateContribution template = new NotificationTemplateContribution(
                "run-summary", "mail", Locale.US, "Subject", "<p>Body</p>");
        NotificationTemplateRegistry registry = new NotificationTemplateRegistry();
        ExternalCapabilityInvocationRegistry invocations = new ExternalCapabilityInvocationRegistry();
        NotificationTemplateCapabilityAdapter adapter =
                new NotificationTemplateCapabilityAdapter(registry, invocations);
        PluginCapabilityContributionRegistrar registrar = new PluginCapabilityContributionRegistrar(
                List.of(), List.of(), List.of(adapter), invocations);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(NotificationTemplateContributor.class, () -> () -> List.of(template));
        context.refresh();

        PluginCapabilityContributionRegistrar.PreparedOwner prepared =
                registrar.allocateOwner("schedule-owner", "schedule-package", 1L);
        registrar.prepareInto(prepared, context);
        ExternalCapabilityPublication publication = registrar.publish(prepared);

        assertThat(registry.find("run-summary", "mail", Locale.ENGLISH)).contains(template);
        assertThatThrownBy(() -> registry.registerPrepared(
                "competing-owner", publication.owner().publicationId() + 1L, List.of(template)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate notification template");
        assertThat(registry.find("run-summary", "mail", Locale.US)).contains(template);

        ExternalCapabilityDrain drain = registrar.withdraw(publication).orElseThrow();
        assertThat(drain.isDrained()).isTrue();
        registrar.retireDrained(drain);
        assertThat(registry.find("run-summary", "mail", Locale.US)).isEmpty();
        context.close();
        registrar.acknowledgeRetired(drain);
        assertThat(registrar.releaseRetirementProof(drain)).isTrue();
    }
}
