package top.sywyar.pixivdownload.cli;

import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.plugin.web.page.WebPublicationStore;
import java.io.PrintStream;
import java.util.Arrays;

/** 由启动器在单实例锁内、任何数据库恢复之前执行的本机管理入口。 */
public final class CliWebPublications {
    public static final String COMMAND = "--web-publications";
    private CliWebPublications() { }

    public static void handleIfPresent(String[] args) {
        if (args == null || !Arrays.asList(args).contains(COMMAND)) return;
        System.exit(run(args, WebPublicationStore.openForRecovery(), System.out));
    }

    static int run(String[] args, WebPublicationStore store, PrintStream out) {
        if (Arrays.stream(args).filter(CliSetupArguments::isCommand).count() != 1) {
            out.println(MessageBundles.get("cli.publications.arguments"));
            return 2;
        }
        String selected = null, digest = null;
        for (String arg : args) {
            if (arg.startsWith("--publication-restore=")) {
                if (selected != null) return invalid(out);
                selected = arg.substring("--publication-restore=".length());
            } else if (arg.startsWith("--publication-sha256=")) {
                if (digest != null) return invalid(out);
                digest = arg.substring("--publication-sha256=".length());
            }
        }
        if ((selected == null) != (digest == null)) return invalid(out);
        try {
            if (selected != null) {
                store.recover(selected, digest);
                out.println(MessageBundles.get("cli.publications.restored", store.snapshot().identity()));
            } else {
                try { out.println(MessageBundles.get("cli.publications.current", store.snapshot().identity())); }
                catch (IllegalStateException unavailable) { out.println(MessageBundles.get("cli.publications.unavailable")); }
                out.println(MessageBundles.get("cli.publications.candidates"));
                out.println(JsonMapper.builderWithJackson2Defaults().build().writerWithDefaultPrettyPrinter().writeValueAsString(store.recoveryCandidates()));
            }
            return 0;
        } catch (Exception failure) {
            out.println(MessageBundles.get("cli.publications.failed"));
            org.slf4j.LoggerFactory.getLogger(CliWebPublications.class).warn(MessageBundles.getForLog("web-publication.log.recovery-failed",
                    failure instanceof IllegalArgumentException ? "invalid-selection" : "state-unavailable"));
            return 1;
        }
    }

    private static int invalid(PrintStream out) {
        out.println(MessageBundles.get("cli.publications.arguments"));
        return 2;
    }
}
