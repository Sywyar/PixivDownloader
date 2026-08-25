package top.sywyar.pixivdownload.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CLI 管理参数测试")
class CliSetupArgumentsTest {

    @Test
    @DisplayName("应区分帮助、缺值和未知参数并放行 Spring 属性覆盖")
    void classifiesLauncherArguments() {
        CliSetupArguments.Inspection inspection = CliSetupArguments.inspect(new String[]{
                "--help",
                "--username",
                "unexpected",
                "--server.port=8080"
        });

        assertThat(inspection.helpRequested()).isTrue();
        assertThat(inspection.missingValue()).containsExactly("--username");
        assertThat(inspection.unknown()).containsExactly("unexpected");
    }

    @Test
    @DisplayName("应按最后一次声明解析命令参数")
    void parsesLastDeclaredValues() {
        CliSetupArguments arguments = CliSetupArguments.parse(new String[]{
                "--username=first",
                "--username=admin",
                "--mode=solo",
                "--proxy-enabled=false",
                "--proxy-host=proxy.example",
                "--proxy-port=8080"
        });

        assertThat(arguments.username()).isEqualTo("admin");
        assertThat(arguments.mode()).isEqualTo("solo");
        assertThat(arguments.proxyEnabled()).isEqualTo("false");
        assertThat(arguments.proxyHost()).isEqualTo("proxy.example");
        assertThat(arguments.proxyPort()).isEqualTo("8080");
    }

    @Test
    @DisplayName("应只识别 CLI 管理命令")
    void detectsManagementCommands() {
        assertThat(CliSetupArguments.containsCommand(new String[]{"--no-gui", "--setup"})).isTrue();
        assertThat(CliSetupArguments.containsCommand(new String[]{"--no-gui", "--server.port=8080"})).isFalse();
        assertThat(CliSetupArguments.containsCommand(null)).isFalse();
    }
}
