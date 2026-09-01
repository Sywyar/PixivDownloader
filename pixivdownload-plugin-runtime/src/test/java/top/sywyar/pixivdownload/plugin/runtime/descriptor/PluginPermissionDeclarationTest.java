package top.sywyar.pixivdownload.plugin.runtime.descriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("插件权限声明")
class PluginPermissionDeclarationTest {

    @Test
    @DisplayName("规范化声明并以集合包含关系判断权限是否增加")
    void normalizesAndComparesPrivilege() {
        PluginPermissionDeclaration previous = PluginPermissionDeclaration.declared(
                List.of("NETWORK", "filesystem-write", "network"));
        PluginPermissionDeclaration reduced = PluginPermissionDeclaration.declared(List.of("network"));

        assertThat(previous.permissions()).containsExactly("filesystem-write", "network");
        assertThat(reduced.isNoMorePrivilegedThan(previous)).isTrue();
        assertThat(previous.isNoMorePrivilegedThan(reduced)).isFalse();
        assertThat(PluginPermissionDeclaration.undeclared().isNoMorePrivilegedThan(previous)).isFalse();
        assertThat(reduced.isNoMorePrivilegedThan(PluginPermissionDeclaration.undeclared())).isTrue();
    }

    @Test
    @DisplayName("声明状态参与摘要且非法 token 被拒绝")
    void bindsDeclarationStateAndRejectsInvalidTokens() {
        assertThat(PluginPermissionDeclaration.declared(List.of()).digest())
                .isNotEqualTo(PluginPermissionDeclaration.undeclared().digest());
        assertThatThrownBy(() -> PluginPermissionDeclaration.declared(List.of("file/system")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid token");
        assertThatThrownBy(() -> PluginPermissionDeclaration.declared(List.of("a".repeat(65))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid token");
    }
}
