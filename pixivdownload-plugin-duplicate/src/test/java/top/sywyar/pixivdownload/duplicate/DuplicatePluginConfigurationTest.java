package top.sywyar.pixivdownload.duplicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("疑似重复插件执行器装配")
class DuplicatePluginConfigurationTest {

    @Test
    @DisplayName("扫描执行器由插件子上下文以独立单线程池拥有")
    void scanExecutorIsPluginOwnedSingleThreadPool() throws InterruptedException {
        ThreadPoolTaskExecutor executor =
                new DuplicatePluginConfiguration().duplicateScanTaskExecutor();
        executor.initialize();
        try {
            assertThat(executor.getCorePoolSize()).isOne();
            assertThat(executor.getMaxPoolSize()).isOne();
            assertThat(executor.getThreadPoolExecutor().getQueue())
                    .isInstanceOf(LinkedBlockingQueue.class);

            AtomicReference<String> threadName = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            executor.execute(() -> {
                threadName.set(Thread.currentThread().getName());
                completed.countDown();
            });

            assertThat(completed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("duplicate-scan-");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("扫描服务绑定本地执行器并显式声明线程池关闭")
    void scanServiceDependsOnLocalExecutorWithExplicitShutdown() throws NoSuchMethodException {
        Method executorFactory =
                DuplicatePluginConfiguration.class.getDeclaredMethod("duplicateScanTaskExecutor");
        Bean executorBean = executorFactory.getAnnotation(Bean.class);
        assertThat(executorBean.name()).containsExactly("duplicateScanTaskExecutor");
        assertThat(executorBean.destroyMethod()).isEqualTo("shutdown");

        Method serviceFactory = DuplicatePluginConfiguration.class.getDeclaredMethod(
                "duplicateScanService",
                top.sywyar.pixivdownload.core.hash.ArtworkHashIndexMaintenance.class,
                DuplicateService.class,
                top.sywyar.pixivdownload.i18n.MessageResolver.class,
                org.springframework.core.task.TaskExecutor.class);
        Qualifier qualifier = serviceFactory.getParameters()[3].getAnnotation(Qualifier.class);
        assertThat(qualifier).isNotNull();
        assertThat(qualifier.value()).isEqualTo("duplicateScanTaskExecutor");
    }
}
