package top.sywyar.pixivdownload.plugin.api.userscript;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.web.UserscriptContribution;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("油猴脚本目录契约")
class UserscriptCatalogContractTest {

    @Test
    @DisplayName("插件声明显式拥有稳定脚本 id 与精确 classpath 资源")
    void contributionHasStableIdAndExactResourceShape() {
        assertThat(UserscriptContribution.class.isRecord()).isTrue();
        assertThat(Arrays.stream(UserscriptContribution.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .containsExactly("id", "classpathResource");
        assertThat(Arrays.stream(UserscriptContribution.class.getRecordComponents())
                .map(component -> component.getType().getName()).toList())
                .containsOnly(String.class.getName());
    }

    @Test
    @DisplayName("目录只暴露当前不可变脚本列表")
    void catalogHasExactSurface() {
        assertThat(UserscriptCatalog.class.isInterface()).isTrue();
        assertThat(Arrays.stream(UserscriptCatalog.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList())
                .singleElement()
                .satisfies(method -> {
                    assertThat(method.getName()).isEqualTo("scripts");
                    assertThat(method.getParameterTypes()).isEmpty();
                    assertThat(method.getGenericReturnType().getTypeName())
                            .isEqualTo(List.class.getName() + "<" + UserscriptArtifact.class.getName() + ">");
                });
    }

    @Test
    @DisplayName("脚本快照只携稳定元数据与已物化文本")
    void artifactHasExactShape() {
        assertThat(UserscriptArtifact.class.isRecord()).isTrue();
        assertThat(Arrays.stream(UserscriptArtifact.class.getRecordComponents())
                .map(component -> component.getName()).toList())
                .containsExactly("id", "displayName", "description", "version", "content");
        assertThat(Arrays.stream(UserscriptArtifact.class.getRecordComponents())
                .map(component -> component.getType().getName()).toList())
                .containsOnly(String.class.getName());
    }

    @Test
    @DisplayName("可选元数据规范为空串且关键字段拒绝缺失")
    void artifactNormalizesOptionalMetadataAndRejectsMissingRequiredFields() {
        UserscriptArtifact artifact = new UserscriptArtifact(
                "sample", "Sample", null, null, "");

        assertThat(artifact.description()).isEmpty();
        assertThat(artifact.version()).isEmpty();
        assertThatThrownBy(() -> new UserscriptArtifact(" ", "Sample", "", "", "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserscriptArtifact("sample", null, "", "", "content"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new UserscriptArtifact("sample", "Sample", "", "", null))
                .isInstanceOf(NullPointerException.class);
    }
}
