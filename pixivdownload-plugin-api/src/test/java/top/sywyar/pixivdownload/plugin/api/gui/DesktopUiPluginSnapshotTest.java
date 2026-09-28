package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("桌面插件快照标识校验")
class DesktopUiPluginSnapshotTest {
    @Test
    @DisplayName("快照和指纹保留合法标识、命名空间规范化及长度边界")
    void acceptsStableIds() {
        for (String id : new String[]{"a", "0", "a.b:c_d-e", "a".repeat(128)}) {
            DesktopUiPluginSnapshot snapshot = snapshot(id, id, " " + id + " ", 0);
            assertThat(snapshot.id()).isEqualTo(id);
            assertThat(snapshot.displayNamespace()).isEqualTo(id);
            assertThat(snapshot.fingerprint()).isEqualTo(new DesktopUiPluginSnapshot.Fingerprint(id, false, id, 0));
        }
        assertThat(snapshot("a", "b", " ", 1).displayNamespace()).isNull();
        assertThat(snapshot("a", "b", null, 1).displayNamespace()).isNull();
    }

    @Test
    @DisplayName("所有构造入口拒绝非法标识和负代际")
    void rejectsInvalidIds() {
        for (String id : new String[]{null, "", " ", "-a", "a/b", "a b", "中文", "a\n", "a".repeat(129)}) {
            assertThatThrownBy(() -> snapshot(id, "valid", null, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> snapshot("valid", id, null, 1)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DesktopUiPluginSnapshot.Fingerprint(id, false, "valid", 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new DesktopUiPluginSnapshot.Fingerprint("valid", false, id, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String namespace : new String[]{"-a", "a/b", "中文", "a".repeat(129)}) {
            assertThatThrownBy(() -> snapshot("valid", "valid", namespace, 1)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> snapshot("valid", "valid", null, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DesktopUiPluginSnapshot.Fingerprint("valid", false, "valid", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DesktopUiPluginSnapshot snapshot(String id, String packageId, String namespace, long generation) {
        return new DesktopUiPluginSnapshot(
                id,
                false,
                packageId,
                generation,
                false,
                namespace,
                "",
                null,
                null,
                null,
                null,
                null
        );
    }
}
