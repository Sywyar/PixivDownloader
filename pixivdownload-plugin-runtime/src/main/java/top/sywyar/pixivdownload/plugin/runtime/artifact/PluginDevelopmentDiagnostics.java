package top.sywyar.pixivdownload.plugin.runtime.artifact;

import top.sywyar.pixivdownload.plugin.runtime.discovery.PluginLoadFailure;

import java.nio.file.Path;
import java.util.List;

/** 插件开发模式的源目录诊断与醒目控制台提示。 */
public final class PluginDevelopmentDiagnostics {

    private static final String ANSI_RED_BOLD = "\u001B[1;31m";
    private static final String ANSI_RESET = "\u001B[0m";

    private PluginDevelopmentDiagnostics() {
    }

    public static List<PluginLoadFailure> sourceFailures(
            PluginDevelopmentArtifacts.DevelopmentDiscovery discovery) {
        return discovery.sourceOnlyModules().stream()
                .map(module -> new PluginLoadFailure(module.pluginId(),
                        "development plugin module has plugin.properties in source resources but no compiled "
                                + "target/classes/plugin.properties: " + module.moduleRoot()))
                .toList();
    }

    public static void printBanner(
            Path productionDirectory,
            PluginDevelopmentArtifacts.DevelopmentDiscovery discovery) {
        redLine("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
        redLine("PIXIVDOWNLOAD PLUGIN DEVELOPMENT MODE ENABLED");
        redLine("The plugins directory is ignored: " + productionDirectory);
        redLine("Development root: " + discovery.developmentRoot());
        redLine("Development cache: " + discovery.cacheRoot());
        redLine("Compiled plugin modules: " + discovery.artifacts().size()
                + displayModules(discovery.artifacts().stream()
                .map(artifact -> artifact.moduleRoot().getFileName().toString()).toList()));
        if (!discovery.sourceOnlyModules().isEmpty()) {
            redLine("Source plugin modules without target/classes output: "
                    + displayModules(discovery.sourceOnlyModules().stream()
                    .map(module -> module.moduleRoot().getFileName().toString()).toList()));
            redLine("Compile these modules before launching; otherwise required plugins may keep recovery mode active.");
        }
        redLine("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
    }

    private static String displayModules(List<String> modules) {
        if (modules == null || modules.isEmpty()) {
            return " (none)";
        }
        return " [" + String.join(", ", modules) + "]";
    }

    private static void redLine(String message) {
        System.err.println(ANSI_RED_BOLD + message + ANSI_RESET);
    }
}
