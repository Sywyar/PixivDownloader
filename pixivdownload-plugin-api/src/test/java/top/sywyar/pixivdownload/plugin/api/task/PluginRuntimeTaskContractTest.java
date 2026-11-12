package top.sywyar.pixivdownload.plugin.api.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("插件运行期后台任务稳定契约")
class PluginRuntimeTaskContractTest {

    @Test
    @DisplayName("owner-scoped registrar 只登记一次性或周期任务且不接收 owner 身份")
    void registrarHasExactOwnerScopedSurface() throws NoSuchMethodException {
        assertThat(PluginRuntimeTaskRegistrar.class.isInterface()).isTrue();
        assertThat(PluginRuntimeTaskRegistrar.class.getDeclaredFields()).isEmpty();
        assertThat(Arrays.stream(PluginRuntimeTaskRegistrar.class.getDeclaredMethods())
                .map(Method::getName))
                .containsExactlyInAnyOrder("registerOneShot", "registerPeriodic", "acceptsNewTasks");

        assertMethod(PluginRuntimeTaskRegistrar.class, "registerOneShot",
                PluginRuntimeTask.class, Runnable.class);
        assertMethod(PluginRuntimeTaskRegistrar.class, "registerPeriodic",
                PluginRuntimeTask.class, Runnable.class);
        assertMethod(PluginRuntimeTaskRegistrar.class, "acceptsNewTasks", boolean.class);
        assertThat(Arrays.stream(PluginRuntimeTaskRegistrar.class.getDeclaredMethods())
                .map(Method::getName))
                .noneMatch(name -> name.contains("PluginId")
                        || name.contains("PackageId")
                        || name.contains("Generation")
                        || name.contains("Publication"));
    }

    @Test
    @DisplayName("admission 拒绝使用专用 RejectedExecutionException 子类型")
    void rejectedExceptionHasStableJdkParent() {
        assertThat(PluginRuntimeTaskRejectedException.class.getSuperclass())
                .isEqualTo(RejectedExecutionException.class);
        assertThat(PluginRuntimeTaskRejectedException.class.getDeclaredFields()).isEmpty();
    }

    private static void assertMethod(
            Class<?> owner,
            String name,
            Class<?> returnType,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        assertThat(method.getReturnType()).isEqualTo(returnType);
        assertThat(method.isDefault()).isFalse();
        assertThat(method.getExceptionTypes()).isEmpty();
    }
}
