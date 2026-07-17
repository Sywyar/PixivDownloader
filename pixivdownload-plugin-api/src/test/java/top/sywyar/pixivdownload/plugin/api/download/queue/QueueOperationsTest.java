package top.sywyar.pixivdownload.plugin.api.download.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("下载队列纯 JDK 操作契约")
class QueueOperationsTest {

    @Test
    @DisplayName("同步哨兵使用 generation 0 并立即归零")
    void completedDrainIsImmediatelyDrained() {
        QueueDrain drain = QueueDrain.completed("sample");

        assertThat(drain.queueType()).isEqualTo("sample");
        assertThat(drain.generation()).isEqualTo(QueueDrain.COMPLETED_GENERATION);
        assertThat(drain.activeCount()).isZero();
        assertThat(drain.isDrained()).isTrue();
        assertThat(drain.awaitDrained(System.nanoTime() - 1L)).isTrue();
        assertThat(drain.awaitDrained()).isTrue();
    }

    @Test
    @DisplayName("同步哨兵拒绝空白队列键")
    void completedDrainRejectsBlankQueueType() {
        assertThatThrownBy(() -> QueueDrain.completed(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueDrain.completed("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("默认清退使用宿主捕获键且不重读插件 getter")
    void defaultQuiesceUsesCapturedQueueType() throws Exception {
        QueueOperations operations = new QueueOperations() {
            @Override
            public String queueType() {
                throw new AssertionError("queueType getter must not be read during teardown");
            }

            @Override
            public int clearAll() {
                return 2;
            }

            @Override
            public int clearForOwner(String ownerUuid) {
                return ownerUuid == null ? 0 : 1;
            }
        };

        QueueDrain first = operations.prepareQuiesce("captured");
        QueueDrain second = operations.prepareQuiesce("captured");

        assertThat(first.queueType()).isEqualTo("captured");
        assertThat(first.generation()).isZero();
        assertThat(second.queueType()).isEqualTo(first.queueType());
        assertThat(second.generation()).isEqualTo(first.generation());
        assertThat(operations.clearAll()).isEqualTo(2);
        assertThat(operations.clearForOwner("owner-a")).isEqualTo(1);
        assertThatCode(() -> operations.cancelQuiescedTasks()).doesNotThrowAnyException();
        assertThatCode(() -> operations.cancel("opaque:work-a", "owner-a", false))
                .doesNotThrowAnyException();
        assertThat(QueueOperations.class.getMethod("prepareQuiesce", String.class).isDefault()).isTrue();
        assertThat(QueueOperations.class.getMethod("cancelQuiescedTasks").isDefault()).isTrue();
        assertThat(QueueOperations.class
                .getMethod("cancel", String.class, String.class, boolean.class).isDefault()).isTrue();
    }
}
