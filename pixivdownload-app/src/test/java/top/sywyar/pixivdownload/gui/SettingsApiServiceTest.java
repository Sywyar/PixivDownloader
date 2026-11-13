package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.config.DatabaseSettingsService;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SettingsApiServiceTest {
    @TempDir Path directory;
    private final DesktopUiHost host = mock(DesktopUiHost.class);
    private final DatabaseSettingsService database = mock(DatabaseSettingsService.class);
    private DesktopUiHost.ConfigFile core;
    private DesktopUiHost.ConfigFile plugin;
    private SettingsApiService service;
    private final Map<String, String> credentials = new HashMap<>();
    private boolean locked;

    @BeforeEach void setup() throws Exception {
        core = new ConfigFileEditor(directory.resolve("core.yaml")).asConfigFile();
        plugin = new ConfigFileEditor(directory.resolve("plugin.yaml")).asConfigFile();
        core.writeAll(Map.of("server.port", "8080", "server.secret", "core-secret"));
        plugin.writeAll(Map.of("independent.label", "before"));
        credentials.put("independent.token", "old-secret");
        when(host.applicationConfig()).thenReturn(core);
        when(host.coreConfigFields()).thenReturn(List.of(field("server.port", GuiConfigFieldType.PORT), field("server.secret", GuiConfigFieldType.PASSWORD)));
        when(host.pluginConfig("independent")).thenReturn(plugin);
        when(host.requireSafeConfigKey(anyString())).thenAnswer(call -> ConfigFileEditor.requireSafeKey(call.getArgument(0)));
        when(host.requireSafeConfigValue(anyString())).thenAnswer(call -> ConfigFileEditor.requireSafeValue(call.getArgument(0)));
        when(database.prepareForApi(any())).thenAnswer(call -> call.getArgument(0));
        doAnswer(call -> {
            assertFalse(locked);
            locked = true;
            try { ((DesktopUiHost.IoOperation) call.getArgument(1)).run(); }
            finally { locked = false; }
            return null;
        }).when(host).withCredentialLocks(anyCollection(), any());
        when(host.readCredentials("independent")).thenAnswer(call -> {
            assertTrue(locked);
            return Map.copyOf(credentials);
        });
        doAnswer(call -> {
            assertTrue(locked);
            credentials.putAll(call.getArgument(1));
            return null;
        }).when(host).updateCredentials(eq("independent"), any());
        var source = mock(DesktopUiPluginSnapshot.class);
        when(source.id()).thenReturn("independent");
        when(source.packageId()).thenReturn("independent");
        when(source.generation()).thenReturn(4L);
        when(source.configContributions()).thenReturn(List.of(new GuiConfigContribution(List.of(),
                List.of(field("independent.label", GuiConfigFieldType.STRING), field("independent.token", GuiConfigFieldType.PASSWORD)), List.of())));
        service = new SettingsApiService(host, () -> List.of(source), database);
    }

    private static GuiConfigFieldContribution field(String key, GuiConfigFieldType type) {
        return new GuiConfigFieldContribution(key, "fixture", key, type, "", 0);
    }
    private static SettingsApiService.Change change(String owner, String key, SettingsApiService.Action action, String value) {
        return new SettingsApiService.Change(owner, owner, owner == null ? 0 : 4, key, action, value);
    }

    @Test void fieldsNeverReturnCoreOrPluginSecrets() throws Exception {
        var fields = service.fields();
        assertTrue(fields.stream().anyMatch(field -> field.key().equals("server.port")));
        for (var field : fields) if (field.sensitive()) {
            assertNull(field.value());
            assertNull(field.defaultValue());
            assertTrue(field.stored());
        }
        assertFalse(fields.toString().contains("old-secret"));
        assertFalse(fields.toString().contains("core-secret"));
    }

    @Test void presetsUseDeclaredOwnerFieldsAndNeverExposeSensitiveValues() throws Exception {
        var section = mock(GuiConfigSectionContribution.class);
        when(section.sectionId()).thenReturn("server");
        when(section.presets()).thenReturn(List.of(
                new GuiConfigPresetContribution("alternate", "alternate", 0, Map.of("server.port", "9090")),
                new GuiConfigPresetContribution("unsafe", "unsafe", 0, Map.of("server.secret", "must-not-return"))));
        when(host.coreConfigSections()).thenReturn(List.of(section));
        var presets = service.presets();
        assertEquals(1, presets.size());
        assertNull(presets.getFirst().owner());
        assertEquals(Map.of("server.port", "9090"), presets.getFirst().values());
        assertEquals("8080", core.read("server.port"));
        service.save(List.of(change(null, "server.port", SettingsApiService.Action.SET, presets.getFirst().values().get("server.port"))), false);
        assertEquals("9090", core.read("server.port"));
    }

    @Test void validatesBeforeWritesAndKeepIsNotClear() throws Exception {
        var changes = List.of(change(null, "server.port", null, "9090"),
                change("independent", "independent.token", SettingsApiService.Action.KEEP, null));
        service.save(changes, true);
        assertEquals("8080", core.read("server.port"));
        service.save(changes, false);
        assertEquals("9090", core.read("server.port"));
        assertEquals("old-secret", credentials.get("independent.token"));
        assertThrows(IllegalArgumentException.class, () -> service.save(List.of(change(null, "server.port", null, "0")), false));
        assertEquals("9090", core.read("server.port"));
        assertThrows(IllegalArgumentException.class, () -> service.save(List.of(
                new SettingsApiService.Change("independent", "independent", 3, "independent.label", null, "late")), false));
        assertEquals("before", plugin.read("independent.label"));
    }

    @Test void credentialFailureRollsBackNormalFilesAndSecretsUnderTheOwnerLock() throws Exception {
        var snapshot = mock(DesktopUiHost.CredentialSnapshot.class);
        when(host.snapshotCredentials("independent")).thenAnswer(call -> {
            assertTrue(locked);
            return snapshot;
        });
        doAnswer(call -> {
            assertTrue(locked);
            credentials.put("independent.token", "");
            throw new java.io.IOException("fixture write failure");
        }).when(host).updateCredentials(eq("independent"), any());
        doAnswer(call -> {
            assertTrue(locked);
            credentials.put("independent.token", "old-secret");
            return null;
        }).when(host).restoreCredentials("independent", snapshot);
        assertThrows(java.io.IOException.class, () -> service.save(List.of(
                change(null, "server.port", null, "9090"),
                change("independent", "independent.label", null, "after"),
                change("independent", "independent.token", SettingsApiService.Action.CLEAR, null)), false));
        assertEquals("8080", core.read("server.port"));
        assertEquals("before", plugin.read("independent.label"));
        assertEquals("old-secret", credentials.get("independent.token"));
        verify(host).restoreCredentials("independent", snapshot);
    }

    @Test void templateValidationKeepsTheFieldAndVariableThroughTheHostIOException() throws Exception {
        doThrow(new java.io.IOException("localized detail", new top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException(
                "unknown-variable", "missing"))).when(host).validateCoreConfigValue("server.port", "9090");
        var failure = assertThrows(SettingsApiService.InvalidField.class,
                () -> service.save(List.of(change(null, "server.port", null, "9090")), false));
        assertEquals("DIRECTORY_TEMPLATE_UNKNOWN_VARIABLE", failure.getMessage());
        assertEquals("server.port", failure.key);
        assertEquals("missing", failure.variable);
        assertEquals("8080", core.read("server.port"));
    }
}
