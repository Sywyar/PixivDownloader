package top.sywyar.pixivdownload.openapi;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.PluginTestProvenance;
import top.sywyar.pixivdownload.plugin.runtime.PluginRuntimeManager;
import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageReader;
import top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校验随源码发布的 Agent skill 中写明的接口与权限仍然存在。
 * <p>
 * 需要装入的插件由 skill 自身推出：权限 ID 的 owner 即插件 ID，再补齐其必需依赖。owner 不是 app 测试登记的
 * 官方插件时直接失败；插件已登记但 classes 尚未构建时，与其它外置插件启动测试一样跳过。
 */
@SpringBootTest(properties = {
        "database.provider=database-sqlite",
        "database.jdbc-url=jdbc:sqlite:target/test-runtime/data/agent-skill.db",
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-agent-skill",
        "setup.browser.auto-open=false",
        "update.auto-check=false"
})
@ContextConfiguration(initializers = PluginTestProvenance.VerifiedLocalPluginBootstrapInitializer.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("pluginsStaged")
@DisplayName("Agent skill 与完整接口目录")
class AgentSkillCatalogTest {

    private static final Path PLUGINS_DIR = Path.of("target/test-runtime/plugins-agent-skill");
    private static final boolean STAGED = stagePlugins();

    static {
        if (STAGED) {
            System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
            System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
            System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
            System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, PLUGINS_DIR.toString());
        }
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PluginRuntimeManager pluginRuntimeManager;

    @SuppressWarnings("unused")
    static boolean pluginsStaged() {
        return STAGED;
    }

    @AfterAll
    void releasePlugins() throws IOException {
        pluginRuntimeManager.shutdown();
        TestDirectoryCleanup.delete(PLUGINS_DIR);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @Test
    @DisplayName("skill 引用的操作、路径、接口组与权限都能在装入相关插件后的目录中找到")
    void referencesResolveInCatalog() throws Exception {
        AgentSkillDocument skill = AgentSkillDocument.load();
        JsonNode catalog = JsonMapper.shared().readTree(MockMvcBuilders.webAppContextSetup(context).build()
                .perform(get("/api/openapi.json").param("lang", "en-US"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        Map<String, Set<String>> operations = operations(catalog.path("paths"));
        Set<String> permissions = new HashSet<>();
        collectPermissions(catalog, false, permissions);

        List<String> unresolved = new ArrayList<>();
        for (AgentSkillDocument.ApiReference reference : skill.apiReferences()) {
            String path = AgentSkillDocument.normalize(reference.path());
            boolean found;
            if (reference.family()) {
                found = operations.keySet().stream().anyMatch(candidate -> candidate.startsWith(path));
            } else if (reference.method() != null) {
                found = operations.getOrDefault(path, Set.of()).contains(reference.method().toLowerCase(Locale.ROOT));
            } else {
                found = operations.containsKey(path);
            }
            if (!found) unresolved.add(reference.toString());
        }
        for (String permission : skill.permissions()) {
            if (!permissions.contains(permission)) unresolved.add(permission);
        }

        assertThat(skill.apiReferences()).isNotEmpty();
        assertThat(skill.permissions()).isNotEmpty();
        assertThat(unresolved).as("Agent skill references missing from /api/openapi.json").isEmpty();
    }

    /** 规范化路径 → 该路径声明的 HTTP 方法（小写）。 */
    private static Map<String, Set<String>> operations(JsonNode paths) {
        Map<String, Set<String>> operations = new HashMap<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            Set<String> methods = operations.computeIfAbsent(AgentSkillDocument.normalize(path.getKey()),
                    ignored -> new TreeSet<>());
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                if (operation.getValue().has("responses")) methods.add(operation.getKey());
            }
        }
        return operations;
    }

    /** 收集 {@code x-program-permission*} 扩展下出现的全部权限 ID。 */
    private static void collectPermissions(JsonNode node, boolean declared, Set<String> permissions) {
        if (node.isString()) {
            if (declared) permissions.add(node.asString());
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> child : node.properties()) {
                collectPermissions(child.getValue(), declared || child.getKey().startsWith("x-program-permission"),
                        permissions);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) collectPermissions(child, declared, permissions);
        }
    }

    private static boolean stagePlugins() {
        try {
            Set<String> owners = new TreeSet<>();
            for (String permission : AgentSkillDocument.load().permissions()) {
                owners.add(permission.substring(0, permission.indexOf(':')));
            }
            owners.remove("core");
            for (String owner : owners) {
                if (System.getProperty(owner + ".plugin.classes") == null) {
                    throw new IllegalStateException("Agent skill references permissions of plugin '" + owner
                            + "', which is not registered as an official plugin for app tests");
                }
            }
            TestDirectoryCleanup.delete(PLUGINS_DIR);
            Deque<String> pending = new ArrayDeque<>(owners);
            Set<String> staged = new HashSet<>();
            while (!pending.isEmpty()) {
                String pluginId = pending.pop();
                if (!staged.add(pluginId)) continue;
                Path artifact = PluginTestProvenance.stageOfficialPlugin(PLUGINS_DIR, pluginId);
                PluginPackageReader.inspect(artifact).descriptor().dependencies().stream()
                        .filter(dependency -> !dependency.optional())
                        .forEach(dependency -> pending.push(dependency.pluginId()));
            }
            PluginTestProvenance.stageSqliteProvider(PLUGINS_DIR);
            return true;
        } catch (IOException unbuilt) {
            return false;
        }
    }
}
