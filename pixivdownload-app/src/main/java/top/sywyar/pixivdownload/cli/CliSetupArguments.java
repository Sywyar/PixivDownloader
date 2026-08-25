package top.sywyar.pixivdownload.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CLI 管理命令的纯参数投影与启动参数分类。
 */
record CliSetupArguments(
        String username,
        String password,
        String oldPassword,
        String newPassword,
        String mode,
        String proxyEnabled,
        String proxyHost,
        String proxyPort) {

    private static final Set<String> COMMANDS = Set.of(
            CliSetupCommand.CMD_SETUP,
            CliSetupCommand.CMD_CHANGE_PASSWORD,
            CliSetupCommand.CMD_RESET_PASSWORD);
    private static final Set<String> KNOWN_BOOL_FLAGS = Set.of(
            "--no-gui",
            "--intro",
            "--pixivdownload-startup",
            "--startup",
            CliSetupCommand.CMD_SETUP,
            CliSetupCommand.CMD_CHANGE_PASSWORD,
            CliSetupCommand.CMD_RESET_PASSWORD,
            CliSetupCommand.FLAG_HELP_LONG,
            CliSetupCommand.FLAG_HELP_SHORT,
            "--debug",
            "--trace");
    private static final Set<String> KNOWN_VALUE_FLAGS = Set.of(
            CliSetupCommand.FLAG_USERNAME,
            CliSetupCommand.FLAG_PASSWORD,
            CliSetupCommand.FLAG_OLD_PASSWORD,
            CliSetupCommand.FLAG_NEW_PASSWORD,
            CliSetupCommand.FLAG_MODE,
            CliSetupCommand.FLAG_PROXY_ENABLED,
            CliSetupCommand.FLAG_PROXY_HOST,
            CliSetupCommand.FLAG_PROXY_PORT);

    static Inspection inspect(String[] args) {
        boolean helpRequested = false;
        List<String> unknown = new ArrayList<>();
        List<String> missingValue = new ArrayList<>();
        if (args == null) {
            return new Inspection(false, unknown, missingValue);
        }

        for (String arg : args) {
            if (arg == null || arg.isEmpty()) {
                continue;
            }
            if (CliSetupCommand.FLAG_HELP_LONG.equals(arg) || CliSetupCommand.FLAG_HELP_SHORT.equals(arg)) {
                helpRequested = true;
            } else if (KNOWN_BOOL_FLAGS.contains(arg)) {
                continue;
            } else {
                int equalsIndex = arg.indexOf('=');
                if (equalsIndex > 0 && arg.startsWith("--")) {
                    continue;
                }
                if (equalsIndex < 0 && KNOWN_VALUE_FLAGS.contains(arg)) {
                    missingValue.add(arg);
                } else {
                    unknown.add(arg);
                }
            }
        }
        return new Inspection(helpRequested, unknown, missingValue);
    }

    static boolean containsCommand(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if (isCommand(arg)) {
                return true;
            }
        }
        return false;
    }

    static boolean isCommand(String arg) {
        return arg != null && COMMANDS.contains(arg);
    }

    static CliSetupArguments parse(String[] args) {
        String username = null;
        String password = null;
        String oldPassword = null;
        String newPassword = null;
        String mode = null;
        String proxyEnabled = null;
        String proxyHost = null;
        String proxyPort = null;
        if (args == null) {
            return new CliSetupArguments(null, null, null, null, null, null, null, null);
        }
        for (String arg : args) {
            if (arg == null) {
                continue;
            }
            username = takeValue(arg, CliSetupCommand.FLAG_USERNAME, username);
            password = takeValue(arg, CliSetupCommand.FLAG_PASSWORD, password);
            oldPassword = takeValue(arg, CliSetupCommand.FLAG_OLD_PASSWORD, oldPassword);
            newPassword = takeValue(arg, CliSetupCommand.FLAG_NEW_PASSWORD, newPassword);
            mode = takeValue(arg, CliSetupCommand.FLAG_MODE, mode);
            proxyEnabled = takeValue(arg, CliSetupCommand.FLAG_PROXY_ENABLED, proxyEnabled);
            proxyHost = takeValue(arg, CliSetupCommand.FLAG_PROXY_HOST, proxyHost);
            proxyPort = takeValue(arg, CliSetupCommand.FLAG_PROXY_PORT, proxyPort);
        }
        return new CliSetupArguments(
                username,
                password,
                oldPassword,
                newPassword,
                mode,
                proxyEnabled,
                proxyHost,
                proxyPort);
    }

    private static String takeValue(String arg, String name, String current) {
        String prefix = name + "=";
        return arg.startsWith(prefix) ? arg.substring(prefix.length()) : current;
    }

    record Inspection(boolean helpRequested, List<String> unknown, List<String> missingValue) {
        Inspection {
            unknown = List.copyOf(unknown);
            missingValue = List.copyOf(missingValue);
        }
    }
}
