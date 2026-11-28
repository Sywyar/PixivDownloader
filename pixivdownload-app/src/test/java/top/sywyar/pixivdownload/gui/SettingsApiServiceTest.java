package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.config.DatabaseSettingsService;
import top.sywyar.pixivdownload.plugin.api.gui.*;

import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.registry.PluginSource;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import java.nio.file.Files;
import java.nio.file.Path;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
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
    private final List<DesktopUiPluginSnapshot> sources = new ArrayList<>();
    private final Map<String, String> credentials = new HashMap<>();
    private boolean locked;
    private final PluginRegistry registry = new PluginRegistry(List.of());

    private void register(String id, long generation) {
        var feature = mock(PixivFeaturePlugin.class);
        when(feature.id()).thenReturn(id);
        registry.register(new PluginRegistry.RegisteredPlugin(feature, PluginSource.EXTERNAL, getClass().getClassLoader(), id, generation));
    }

    private final Map<String, String> originalDirectories = new HashMap<>();

    @org.junit.jupiter.api.AfterEach void restoreDirectories() {
        originalDirectories.forEach((name, value) -> {
            if (value == null) System.clearProperty(name);
            else System.setProperty(name, value);
        });
    }

    @BeforeEach void setup() throws Exception {
        Map.of(RuntimeFiles.CONFIG_DIR_PROPERTY, directory, RuntimeFiles.STATE_DIR_PROPERTY, directory.resolve("state"),
                RuntimeFiles.DATA_DIR_PROPERTY, directory.resolve("data")).forEach((name, path) ->
                originalDirectories.put(name, System.setProperty(name, path.toString())));
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
        register("independent", 4);
        sources.add(source);
        service = new SettingsApiService(host, () -> sources, registry, database);
    }

    private static GuiConfigFieldContribution field(String key, GuiConfigFieldType type) {
        return new GuiConfigFieldContribution(key, "fixture", key, type, "", 0);
    }
    private static SettingsApiService.Change change(String owner, String key, SettingsApiService.Action action, String value) {
        return new SettingsApiService.Change(owner, owner, owner == null ? 0 : 4, key, action, value);
    }

    @Test void reportsTheSettingAndOffendingMemberWithoutSavingTheDraft() throws Exception {
        var keep = change("independent", "independent.token", SettingsApiService.Action.KEEP, null);
        assertEquals(List.of(), service.save(List.of(keep), false));
        var invalid = assertThrows(SettingsApiService.InvalidField.class, () -> service.save(List.of(keep,
                change(null, "server.port", SettingsApiService.Action.KEEP, null)), false));
        assertEquals("/changes/1/action", invalid.field);
        assertEquals("INVALID_TOOL_REQUEST", invalid.getMessage());
        var supplied = assertThrows(SettingsApiService.InvalidField.class, () -> service.save(List.of(
                change("independent", "independent.token", SettingsApiService.Action.KEEP, "discard")), true));
        assertEquals("/changes/0/value", supplied.field);
        var stale = assertThrows(SettingsApiService.InvalidField.class, () -> service.save(List.of(
                new SettingsApiService.Change("independent", null, 4, "independent.token", SettingsApiService.Action.KEEP, null)), false));
        assertEquals("/changes/0/packageId", stale.field);
        assertEquals("SETTINGS_OWNER_CHANGED", stale.getMessage());
        assertEquals("old-secret", credentials.get("independent.token"));
    }

    @Test void validatingCoreAndPluginChangesPreservesPendingKeyAndAllEnvelopes() throws Exception {
        var store = new top.sywyar.pixivdownload.config.credential.PluginCredentialStore();
        // 预生成信封会创建待迁移的安装密钥。
        var paths = new ArrayList<Path>();
        for (String owner : List.of("independent", "unrelated")) {
            byte[] bytes = store.prepareTransfer(owner, Map.of(owner + ".token", "fixture"));
            var path = top.sywyar.pixivdownload.config.RuntimeFiles.peekPluginCredentialPath(owner);
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.write(path, bytes);
            paths.add(path);
        }
        paths.add(top.sywyar.pixivdownload.config.RuntimeFiles.pluginCredentialKeyPath());
        paths.add(directory.resolve("core.yaml"));
        paths.add(directory.resolve("plugin.yaml"));
        var before = new HashMap<Path, byte[]>();
        for (var path : paths) before.put(path, java.nio.file.Files.readAllBytes(path));
        for (var update : List.of(change(null, "server.port", SettingsApiService.Action.SET, "9090"),
                change("independent", "independent.token", SettingsApiService.Action.SET, "new-value"))) {
            try (var files = mockStatic(top.sywyar.pixivdownload.config.RuntimeFiles.class, CALLS_REAL_METHODS)) {
                assertEquals(1, service.save(List.of(update), true).size());
                files.verify(() -> top.sywyar.pixivdownload.config.RuntimeFiles.peekPluginCredentialPath("unrelated"), never());
                if (update.owner() == null)
                    files.verify(top.sywyar.pixivdownload.config.RuntimeFiles::pluginCredentialKeyPath, never());
            }
            for (var path : paths) assertArrayEquals(before.get(path), java.nio.file.Files.readAllBytes(path));
        }
        verify(host, never()).withCredentialLocks(anyCollection(), any());
        verify(host, never()).readCredentials(anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"normal", "pending", "damaged"})
    void savingPortAndWorkbenchParallelismSeparatelyLeavesUnrelatedCredentialsUntouched(String state) throws Exception {
        var realHost = new AppDesktopUiHost(8080, core);
        doAnswer(org.mockito.AdditionalAnswers.delegatesTo(realHost)).when(host).withCredentialLocks(anyCollection(), any());
        var store = new PluginCredentialStore();
        Path envelope = RuntimeFiles.peekPluginCredentialPath("unrelated");
        byte[] bytes = store.prepareTransfer("unrelated", Map.of("unrelated.token", "fixture"));
        Files.createDirectories(envelope.getParent());
        Files.write(envelope, bytes);
        if (state.equals("normal")) store.update("unrelated", Map.of("unrelated.token", "updated"));
        Path key = RuntimeFiles.pluginCredentialKeyPath();
        if (state.equals("damaged")) Files.writeString(key, "damaged installation key");
        byte[] keyBefore = Files.readAllBytes(key);
        byte[] envelopeBefore = Files.readAllBytes(envelope);
        try (var files = mockStatic(RuntimeFiles.class, CALLS_REAL_METHODS)) {
            service.save(List.of(change(null, "server.port", null, "9090")), false);
            files.verify(RuntimeFiles::pluginCredentialKeyPath, never());
            files.verify(() -> RuntimeFiles.peekPluginCredentialPath("unrelated"), never());
        }
        assertEquals("9090", new ConfigFileEditor(directory.resolve("core.yaml")).asConfigFile().read("server.port"));
        assertArrayEquals(keyBefore, Files.readAllBytes(key));
        assertArrayEquals(envelopeBefore, Files.readAllBytes(envelope));
        Path classes = Path.of(System.getProperty("download-workbench.plugin.classes"));
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            var workbench = (top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin) loader.loadClass(
                    "top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin").getConstructor().newInstance();
            var source = mock(DesktopUiPluginSnapshot.class);
            String owner = workbench.id();
            when(source.id()).thenReturn(owner);
            when(source.packageId()).thenReturn(owner);
            when(source.generation()).thenReturn(4L);
            when(source.configContributions()).thenReturn(workbench.guiConfigContributions());
            register(owner, 4);
            var official = new SettingsApiService(realHost, () -> List.of(source), registry, database);
            String setting = "download-workbench.ugoira.parallelism";
            realHost.pluginConfig(owner).writeAll(Map.of(setting, "1"));
            var changes = List.of(change(owner, setting, null, "2"));
            try (var files = mockStatic(RuntimeFiles.class, CALLS_REAL_METHODS)) {
                official.save(changes, true);
                assertEquals("1", realHost.pluginConfig(owner).read(setting));
                official.save(changes, false);
                files.verify(RuntimeFiles::pluginCredentialKeyPath, never());
                files.verify(() -> RuntimeFiles.peekPluginCredentialPath(owner), never());
            }
            assertEquals("2", realHost.pluginConfig(owner).read(setting));
            assertArrayEquals(keyBefore, Files.readAllBytes(key));
            assertArrayEquals(envelopeBefore, Files.readAllBytes(envelope));
        }
    }

    @Test void checksStoredCredentialsOnlyWhenAnActiveRequiredRuleNeedsThem() throws Exception {
        String owner = "independent";
        String mode = owner + ".mode", token = owner + ".token", label = owner + ".label";
        var realHost = new AppDesktopUiHost(8080, core);
        var source = mock(DesktopUiPluginSnapshot.class);
        when(source.id()).thenReturn(owner);
        when(source.packageId()).thenReturn(owner);
        when(source.generation()).thenReturn(4L);
        var enabled = GuiConfigCondition.equalsTo(mode, "on");
        when(source.configContributions()).thenReturn(List.of(new GuiConfigContribution(List.of(
                field(mode, GuiConfigFieldType.STRING), field(token, GuiConfigFieldType.PASSWORD).requiredWhen(enabled),
                field(label, GuiConfigFieldType.STRING).requiredWhen(enabled, GuiConfigCondition.notBlank(token))))));
        var conditional = new SettingsApiService(realHost, () -> List.of(source), registry, database);
        var enable = List.of(change(owner, mode, null, "on"));
        assertEquals(token, assertThrows(SettingsApiService.InvalidField.class, () -> conditional.save(enable, false)).key);
        var store = new PluginCredentialStore();
        var envelope = RuntimeFiles.peekPluginCredentialPath(owner);
        byte[] bytes = store.prepareTransfer(owner, Map.of(token, "fixture"));
        Files.createDirectories(envelope.getParent());
        Files.write(envelope, bytes);
        var key = RuntimeFiles.pluginCredentialKeyPath();
        byte[] pending = Files.readAllBytes(key);
        assertEquals(label, assertThrows(SettingsApiService.InvalidField.class, () -> conditional.save(enable, false)).key);
        conditional.save(List.of(enable.getFirst(), change(owner, label, null, "present")), false);
        assertEquals("on", realHost.pluginConfig(owner).read(mode));
        assertArrayEquals(pending, Files.readAllBytes(key));
        assertArrayEquals(bytes, Files.readAllBytes(envelope));
        Files.writeString(key, "damaged key");
        conditional.save(List.of(change(owner, mode, null, "off")), false);
        assertEquals("off", realHost.pluginConfig(owner).read(mode));
        assertEquals("damaged key", Files.readString(key));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {-1, 4, 5})
    void withdrawnOrReplacedOwnerCannotWriteAnyFileInTheBatch(int replacementGeneration) throws Exception {
        var coreFile = directory.resolve("core.yaml");
        var pluginFile = directory.resolve("plugin.yaml");
        byte[] coreBefore = Files.readAllBytes(coreFile), pluginBefore = Files.readAllBytes(pluginFile);
        when(host.requireSafeConfigValue("withdraw")).thenAnswer(call -> {
            registry.unregister("independent");
            if (replacementGeneration >= 0) register("independent", replacementGeneration);
            return "withdraw";
        });
        var stale = assertThrows(SettingsApiService.InvalidField.class, () -> service.save(List.of(
                change(null, "server.port", null, "9090"), change("independent", "independent.label", null, "withdraw"),
                change("independent", "independent.token", null, "next-secret")), false));
        assertEquals("SETTINGS_OWNER_CHANGED", stale.getMessage());
        assertEquals("/changes/1/owner", stale.field);
        assertArrayEquals(coreBefore, Files.readAllBytes(coreFile));
        assertArrayEquals(pluginBefore, Files.readAllBytes(pluginFile));
        verify(host, never()).withCredentialLocks(anyCollection(), any());
        assertEquals("old-secret", credentials.get("independent.token"));
    }

    @Test void bothPluginIdentitiesAreReservedUntilTheLastFileIsWritten() throws Exception {
        register("second", 4);
        var source = mock(DesktopUiPluginSnapshot.class);
        when(source.id()).thenReturn("second");
        when(source.packageId()).thenReturn("second");
        when(source.generation()).thenReturn(4L);
        when(source.configContributions()).thenReturn(List.of(new GuiConfigContribution(List.of(),
                List.of(field("second.label", GuiConfigFieldType.STRING)), List.of())));
        sources.add(source);
        var second = spy(new ConfigFileEditor(directory.resolve("second.yaml")).asConfigFile());
        when(host.pluginConfig("second")).thenReturn(second);
        doAnswer(call -> {
            for (String id : List.of("independent", "second"))
                assertTrue(assertThrows(IllegalStateException.class, () -> registry.unregister(id))
                        .getMessage().contains("active identity reservation"));
            return call.callRealMethod();
        }).when(second).writeAll(anyMap());
        assertEquals(3, service.save(List.of(change(null, "server.port", null, "9090"),
                change("independent", "independent.label", null, "one"),
                change("second", "second.label", null, "two")), false).size());
        assertEquals("9090", core.read("server.port"));
        assertEquals("one", plugin.read("independent.label"));
        assertEquals("two", second.read("second.label"));
        registry.unregister("independent");
        registry.unregister("second");
    }

    @Test void fieldsNeverReturnCoreOrPluginSecrets() throws Exception {
        new top.sywyar.pixivdownload.config.credential.PluginCredentialStore().update("independent", credentials);
        var fields = service.fields(owner -> true);
        assertTrue(fields.stream().anyMatch(field -> field.key().equals("server.port")));
        for (var field : fields) if (field.sensitive()) {
            assertNull(field.value());
            assertNull(field.defaultValue());
            assertTrue(field.stored());
        }
        assertFalse(fields.toString().contains("old-secret"));
        assertFalse(fields.toString().contains("core-secret"));
    }

    @Test void coreOnlyReadSkipsPluginFilesAndLocks() throws Exception {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity.REQUEST_ATTRIBUTE,
                new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(UUID.randomUUID(), Set.of("core:settings.read")));
        var controller = new SettingsController(service, null, null);
        var store = new top.sywyar.pixivdownload.config.credential.PluginCredentialStore();
        store.update("independent", credentials);
        Path path = top.sywyar.pixivdownload.config.RuntimeFiles.peekPluginCredentialPath("independent");
        byte[] valid = java.nio.file.Files.readAllBytes(path);
        java.nio.file.Files.writeString(path, "invalid");
        List<SettingsApiService.Field> fields;
        try (var files = mockStatic(top.sywyar.pixivdownload.config.RuntimeFiles.class, CALLS_REAL_METHODS)) {
            fields = controller.fields(request);
            files.verify(() -> top.sywyar.pixivdownload.config.RuntimeFiles.peekPluginCredentialPath("independent"), never());
            files.verify(top.sywyar.pixivdownload.config.RuntimeFiles::pluginCredentialKeyPath, never());
        }
        assertTrue(fields.stream().anyMatch(field -> field.key().equals("server.port") && field.value().equals("8080")));
        assertTrue(fields.stream().allMatch(field -> field.owner() == null));
        assertEquals("invalid", java.nio.file.Files.readString(path));
        verify(host, never()).readCredentials(anyString());
        verify(host, never()).pluginConfig(anyString());
        var admin = new org.springframework.mock.web.MockHttpServletRequest();
        assertThrows(java.io.IOException.class, () -> controller.fields(admin));
        java.nio.file.Files.write(path, valid);
        assertTrue(controller.fields(admin).stream().anyMatch(field -> field.key().equals("independent.token") && field.stored()));
    }

    @Test void presetsUseDeclaredOwnerFieldsAndNeverExposeSensitiveValues() throws Exception {
        var section = mock(GuiConfigSectionContribution.class);
        when(section.sectionId()).thenReturn("server");
        when(section.presets()).thenReturn(List.of(
                new GuiConfigPresetContribution("alternate", "alternate", 0, Map.of("server.port", "9090")),
                new GuiConfigPresetContribution("unsafe", "unsafe", 0, Map.of("server.secret", "must-not-return"))));
        when(host.coreConfigSections()).thenReturn(List.of(section));
        var presets = service.presets(owner -> true);
        assertEquals(1, presets.size());
        assertNull(presets.getFirst().owner());
        assertNull(presets.getFirst().namespace());
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
        var realHost = new AppDesktopUiHost(8080, core);
        var delegate = org.mockito.AdditionalAnswers.delegatesTo(realHost);
        doAnswer(call -> {
            var owners = call.<Collection<String>>getArgument(0);
            assertEquals(Set.of("independent"), Set.copyOf(owners));
            realHost.withCredentialLocks(owners, () -> {
                locked = true;
                try { call.<DesktopUiHost.IoOperation>getArgument(1).run(); }
                finally { locked = false; }
            });
            return null;
        }).when(host).withCredentialLocks(anyCollection(), any());
        doAnswer(delegate).when(host).readCredentials("independent");
        doAnswer(delegate).when(host).snapshotCredentials("independent");
        doAnswer(delegate).when(host).restoreCredentials(eq("independent"), any());
        doAnswer(delegate).when(host).updateCredentials(eq("independent"), any());
        service.save(List.of(change("independent", "independent.token", SettingsApiService.Action.SET, "saved")), false);
        assertEquals("saved", realHost.readCredentials("independent").get("independent.token"));
        var envelope = RuntimeFiles.peekPluginCredentialPath("independent");
        byte[] before = Files.readAllBytes(envelope);
        doAnswer(call -> {
            assertTrue(locked);
            realHost.updateCredentials("independent", call.getArgument(1));
            assertFalse(realHost.readCredentials("independent").containsKey("independent.token"));
            throw new java.io.IOException("fixture failure after credential write");
        }).when(host).updateCredentials(eq("independent"), any());
        assertThrows(java.io.IOException.class, () -> service.save(List.of(
                change(null, "server.port", null, "9090"),
                change("independent", "independent.label", null, "after"),
                change("independent", "independent.token", SettingsApiService.Action.CLEAR, null)), false));
        assertEquals("8080", core.read("server.port"));
        assertEquals("before", plugin.read("independent.label"));
        assertArrayEquals(before, Files.readAllBytes(envelope));
        assertEquals("saved", realHost.readCredentials("independent").get("independent.token"));
        verify(host).restoreCredentials(eq("independent"), any());
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
