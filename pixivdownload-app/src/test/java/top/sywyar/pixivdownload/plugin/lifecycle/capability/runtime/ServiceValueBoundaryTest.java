package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("同步服务完整值边界")
class ServiceValueBoundaryTest {
    public record Box<T>(T value) { }
    public record Payload(List<Box<String>> items, byte[] bytes) { }
    public record Recursive(List<Recursive> children) { }
    public interface Echo { Payload echo(Payload input); }
    public interface NestedAsync { Box<List<CompletableFuture<String>>> get(); }
    public interface Untyped { Map<String, Object> get(); }
    public interface RecursiveService { Recursive echo(Recursive input); }
    public record Replaced(byte[] bytes) {
        public Replaced { bytes = new byte[ServiceValueBoundary.MAX_BYTES + 1]; }
    }
    public record Mutated(Box<String>[] values) {
        public Mutated { values[0] = new Box<>("changed"); }
    }
    public record Accessor(byte[] bytes) {
        @Override public byte[] bytes() { throw new AssertionError("accessor must not execute"); }
    }
    public enum Stateful { VALUE; final Object callback = new Object(); }
    public interface InvalidConstructor { Replaced get(); }
    public interface MutatingConstructor { Mutated get(); }
    public interface StatefulEnum { Stateful get(); }
    public interface CustomAccessor { Accessor get(); }
    public interface RecordKey { Map<Box<String>, String> get(); }
    public enum Plain { FIRST, SECOND }
    public interface EnumValues { List<Plain> values(); }

    @Test
    @DisplayName("枚举在类型准备时验证，批量运行值仍受累计节点预算约束")
    void copiesEnumCollections() throws Throwable {
        ServiceValueBoundary.validate(EnumValues.class);
        var type = EnumValues.class.getMethod("values").getGenericReturnType();
        var values = java.util.Collections.nCopies(8_000, Plain.FIRST);
        assertThat(new ServiceValueBoundary(EnumValues.class).copy(values, type)).isEqualTo(values);
        assertThatThrownBy(() -> new ServiceValueBoundary(EnumValues.class).copy(
                java.util.Collections.nCopies(ServiceValueBoundary.MAX_NODES, Plain.FIRST), type))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("嵌套泛型和数组双向复制且不保留原可变对象")
    void copiesValues() throws Throwable {
        ServiceValueBoundary.validate(Echo.class);
        Payload input = new Payload(new ArrayList<>(List.of(new Box<>("你好"))), new byte[]{4, 5});
        Payload copy = (Payload) new ServiceValueBoundary(Echo.class).copy(input, Payload.class);
        input.items().clear();
        input.bytes()[0] = 9;
        assertThat(copy.items()).containsExactly(new Box<>("你好"));
        assertThat(copy.bytes()).containsExactly(4, 5);
        assertThatThrownBy(() -> copy.items().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("声明中的深层异步类型与任意对象在激活前拒绝")
    void rejectsUnsafeTypeGraphs() {
        assertThatThrownBy(() -> ServiceValueBoundary.validate(NestedAsync.class)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ServiceValueBoundary.validate(Untyped.class)).isInstanceOf(IllegalArgumentException.class);
        for (Class<?> type : List.of(InvalidConstructor.class, MutatingConstructor.class, StatefulEnum.class, RecordKey.class)) {
            assertThatThrownBy(() -> ServiceValueBoundary.validate(type)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("复制读取 record 实际字段而不执行可覆写访问器")
    void ignoresCustomAccessor() throws Throwable {
        ServiceValueBoundary.validate(CustomAccessor.class);
        var source = new Accessor(new byte[]{3});
        var copied = (Accessor) new ServiceValueBoundary(CustomAccessor.class).copy(source, Accessor.class);
        var field = Accessor.class.getDeclaredField("bytes");
        field.setAccessible(true);
        assertThat((byte[]) field.get(copied)).containsExactly(3).isNotSameAs(field.get(source));
    }

    @Test
    @DisplayName("泛型擦除不能让错误运行值越过边界")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void rejectsWrongRuntimeType() {
        Payload input = new Payload((List) List.of(new Box<>(42)), new byte[0]);
        assertThatThrownBy(() -> new ServiceValueBoundary(Echo.class).copy(input, Payload.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("循环与多个参数累计超限均拒绝")
    void rejectsCyclesAndCumulativeBudget() throws Throwable {
        List<Recursive> children = new ArrayList<>();
        Recursive value = new Recursive(children);
        children.add(value);
        ServiceValueBoundary.validate(RecursiveService.class);
        assertThatThrownBy(() -> new ServiceValueBoundary(RecursiveService.class).copy(value, Recursive.class))
                .isInstanceOf(IllegalArgumentException.class);
        ServiceValueBoundary boundary = new ServiceValueBoundary(Echo.class);
        boundary.copy(new byte[ServiceValueBoundary.MAX_BYTES / 2], byte[].class);
        assertThatThrownBy(() -> boundary.copy(new byte[ServiceValueBoundary.MAX_BYTES / 2 + 1], byte[].class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
