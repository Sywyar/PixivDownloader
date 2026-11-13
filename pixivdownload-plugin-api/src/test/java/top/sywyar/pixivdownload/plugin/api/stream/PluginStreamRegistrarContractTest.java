package top.sywyar.pixivdownload.plugin.api.stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件推流登记稳定契约")
class PluginStreamRegistrarContractTest {

    @Test
    @DisplayName("登记入口由宿主限定 owner 且只接收连接 token")
    void registrarHasExactOwnerScopedSurface() throws NoSuchMethodException {
        assertThat(PluginStreamRegistrar.class.isInterface()).isTrue();
        assertThat(PluginStreamRegistrar.class.getDeclaredFields()).isEmpty();
        assertThat(Arrays.stream(PluginStreamRegistrar.class.getDeclaredMethods())
                .map(Method::getName))
                .containsExactlyInAnyOrder("register", "unregister", "acceptsNewStreams");

        assertMethod("register", void.class, String.class, PluginStream.class);
        assertMethod("unregister", void.class, String.class);
        assertMethod("acceptsNewStreams", boolean.class);

        assertThat(Arrays.stream(PluginStreamRegistrar.class.getDeclaredMethods())
                .map(Method::getName))
                .noneMatch(name -> name.contains("PluginId")
                        || name.contains("PackageId")
                        || name.contains("Generation")
                        || name.contains("Publication"));
    }

    private static void assertMethod(
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = PluginStreamRegistrar.class.getDeclaredMethod(name, parameterTypes);
        assertThat(method.getReturnType()).isEqualTo(returnType);
        assertThat(method.isDefault()).isFalse();
        assertThat(method.getExceptionTypes()).isEmpty();
    }
}
