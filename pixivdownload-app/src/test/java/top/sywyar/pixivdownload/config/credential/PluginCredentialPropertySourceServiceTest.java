package top.sywyar.pixivdownload.config.credential;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import top.sywyar.pixivdownload.config.credential.migration.PluginCredentialDefinitionResolver;
import top.sywyar.pixivdownload.config.credential.migration.PluginCredentialEnvironmentMask;
import top.sywyar.pixivdownload.config.credential.migration.PluginCredentialMigrationService;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginApplicationContextFactory;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextModule;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextPropertySnapshot;
import top.sywyar.pixivdownload.plugin.runtime.context.PluginContextPropertySourceProvider;
import top.sywyar.pixivdownload.plugin.runtime.stream.PluginStreamRegistry;
import top.sywyar.pixivdownload.plugin.runtime.task.PluginRuntimeTaskRegistry;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("插件凭证子环境快照")
class PluginCredentialPropertySourceServiceTest {

    @Test
    @DisplayName("宿主凭证回调只由工厂持有且不作为父子容器 provider 契约 Bean 暴露")
    void doesNotExposeHostCredentialServiceAsPluginProviderContract() {
        PluginCredentialPropertySourceService service =
                mock(PluginCredentialPropertySourceService.class);
        when(service.snapshotFor("fixture"))
                .thenReturn(PluginContextPropertySnapshot.empty());
        PluginApplicationContextFactory factory =
                new PluginApplicationContextFactory(
                        service::snapshotFor,
                        new PluginStreamRegistry(),
                        new PluginRuntimeTaskRegistry());

        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext()) {
            parent.registerBean(
                    PluginCredentialPropertySourceService.class,
                    () -> service);
            parent.refresh();
            ConfigurableApplicationContext child = factory.create(
                    parent,
                    new PluginContextModule(
                            "fixture",
                            getClass().getClassLoader(),
                            List.of()));
            try {
                assertThatThrownBy(() ->
                        child.getBean(PluginContextPropertySourceProvider.class))
                        .isInstanceOf(NoSuchBeanDefinitionException.class);
            } finally {
                child.close();
            }
        }
    }

    @Test
    @DisplayName("当前 owner 只得到自己声明的值，全局敏感键仅用于遮罩")
    void exposesOnlyDeclaredOwnerValuesAndMasksAllOwners() throws Exception {
        PluginCredentialStore store = mock(PluginCredentialStore.class);
        PluginCredentialDefinitionResolver definitions =
                mock(PluginCredentialDefinitionResolver.class);
        PluginCredentialMigrationService migration =
                mock(PluginCredentialMigrationService.class);
        PluginCredentialEnvironmentMask environmentMask =
                mock(PluginCredentialEnvironmentMask.class);
        when(definitions.resolveAll()).thenReturn(Map.of(
                "first", Set.of("first.api-key"),
                "second", Set.of("second.token")));
        when(environmentMask.maskKeys()).thenReturn(Set.of("legacy.cookie"));
        when(store.readAll("first")).thenReturn(Map.of(
                "first.api-key", "first-secret",
                "second.token", "must-not-leak",
                "stale.secret", "must-not-leak"));
        PluginCredentialPropertySourceService service =
                new PluginCredentialPropertySourceService(
                        store, definitions, migration, environmentMask);

        PluginContextPropertySnapshot snapshot = service.snapshotFor("first");

        assertThat(snapshot.ownerProperties())
                .containsExactly(Map.entry("first.api-key", "first-secret"));
        assertThat(snapshot.sensitivePropertyKeys())
                .containsExactlyInAnyOrder(
                        "first.api-key", "second.token", "legacy.cookie");
        var order = inOrder(migration, definitions, store);
        order.verify(migration).migrateOwner("first");
        order.verify(definitions).resolveAll();
        order.verify(store).readAll("first");
    }

    @Test
    @DisplayName("没有敏感声明的 owner 不读取残留凭证文件")
    void doesNotReadUndeclaredCredentialFile() throws Exception {
        PluginCredentialStore store = mock(PluginCredentialStore.class);
        PluginCredentialDefinitionResolver definitions =
                mock(PluginCredentialDefinitionResolver.class);
        PluginCredentialMigrationService migration =
                mock(PluginCredentialMigrationService.class);
        PluginCredentialEnvironmentMask environmentMask =
                mock(PluginCredentialEnvironmentMask.class);
        when(definitions.resolveAll()).thenReturn(
                Map.of("other", Set.of("other.api-key")));
        when(environmentMask.maskKeys()).thenReturn(Set.of());
        PluginCredentialPropertySourceService service =
                new PluginCredentialPropertySourceService(
                        store, definitions, migration, environmentMask);

        PluginContextPropertySnapshot snapshot = service.snapshotFor("first");

        assertThat(snapshot.ownerProperties()).isEmpty();
        assertThat(snapshot.sensitivePropertyKeys()).containsExactly("other.api-key");
        verify(store, never()).readAll("first");
    }

    @Test
    @DisplayName("迁移或认证失败时当前 owner fail-closed")
    void failsClosedWhenMigrationCannotPrepareOwner() throws Exception {
        PluginCredentialStore store = mock(PluginCredentialStore.class);
        PluginCredentialDefinitionResolver definitions =
                mock(PluginCredentialDefinitionResolver.class);
        PluginCredentialMigrationService migration =
                mock(PluginCredentialMigrationService.class);
        PluginCredentialEnvironmentMask environmentMask =
                mock(PluginCredentialEnvironmentMask.class);
        when(environmentMask.maskKeys()).thenReturn(Set.of());
        org.mockito.Mockito.doThrow(new IOException("authentication failed"))
                .when(migration).migrateOwner("first");
        PluginCredentialPropertySourceService service =
                new PluginCredentialPropertySourceService(
                        store, definitions, migration, environmentMask);

        assertThatThrownBy(() -> service.snapshotFor("first"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first")
                .hasCauseInstanceOf(IOException.class);
        verify(definitions, never()).resolveAll();
        verify(store, never()).readAll("first");
    }
}
