package top.sywyar.pixivdownload.plugin.api.database.migration;

import org.junit.jupiter.api.*;
import top.sywyar.pixivdownload.plugin.api.database.DatabaseProvider;
import top.sywyar.pixivdownload.plugin.api.schema.*;
import java.lang.reflect.Modifier;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@DisplayName("中性迁移交换格式和必需能力")
class MigrationContractTest {
    @Test @DisplayName("必需迁移能力没有默认实现，可选替换能力必须明确拒绝")
    void migrationMethodsAreMandatory() {
        assertThat(DatabaseMigrationProvider.class.isAssignableFrom(DatabaseProvider.class)).isTrue();
        var optional = java.util.Set.of("inspectReplacement", "replaceDatabase");
        assertThat(DatabaseMigrationProvider.class.getDeclaredMethods()).allSatisfy(method -> {
            if (optional.contains(method.getName())) assertThat(method.isDefault()).isTrue();
            else assertThat(Modifier.isAbstract(method.getModifiers()) && !method.isDefault()).isTrue();
        });
        var provider = (DatabaseMigrationProvider) java.lang.reflect.Proxy.newProxyInstance(
                DatabaseMigrationProvider.class.getClassLoader(), new Class<?>[]{DatabaseMigrationProvider.class},
                (proxy, method, args) -> {
                    if (!method.isDefault()) throw new AssertionError("Unexpected required migration call");
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                });
        assertThatThrownBy(() -> provider.inspectReplacement(null, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("TARGET_REPLACEMENT_UNSUPPORTED");
        assertThatThrownBy(() -> provider.replaceDatabase(null, null, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessage("TARGET_REPLACEMENT_UNSUPPORTED");
    }
    @Test @DisplayName("保留长整数、二进制、NULL 和 Unicode，拒绝精度损失或非法值")
    void valuesRemainLossless() {
        assertThat(MigrationValues.canonical(9007199254740993L, "INTEGER")).isEqualTo(9007199254740993L);
        assertThat(MigrationValues.canonical(null, "TEXT")).isNull();
        assertThat(Double.doubleToRawLongBits((Double) MigrationValues.canonical(-0d, "REAL")))
                .isEqualTo(Double.doubleToRawLongBits(-0d));
        assertThat(MigrationValues.canonical("你好 🌸", "TEXT")).isEqualTo("你好 🌸");
        assertThat((byte[]) MigrationValues.canonical(new byte[]{0, -1, 1, 0}, "BLOB")).containsExactly(0, (byte)-1, 1, 0);
        assertThatThrownBy(() -> MigrationValues.canonical(9007199254740993L, "REAL")).hasMessage("REAL_PRECISION");
        assertThatThrownBy(() -> MigrationValues.canonical(Double.NaN, "REAL")).hasMessage("REAL_VALUE");
        assertThatThrownBy(() -> MigrationValues.canonical("\uD800", "TEXT")).hasMessage("TEXT_VALUE");
        assertThatThrownBy(() -> MigrationValues.canonical("123", "INTEGER")).hasMessage("INTEGER_VALUE");
    }
    @Test @DisplayName("格式和结构输入失败关闭，不能把任意 SQL 放入交换模型")
    void rejectsUnknownVersionsAndExecutableStructure() {
        var column = new ColumnSpec("id", "INTEGER", true, null, 1);
        assertThatThrownBy(() -> new MigrationSchema(99, List.of(), List.of(), List.of())).hasMessage("MIGRATION_FORMAT_UNSUPPORTED");
        assertThatThrownBy(() -> top.sywyar.pixivdownload.plugin.api.storage.relational.Expression.column("facts", "id); DROP TABLE facts"))
                .hasMessage("MIGRATION_SCHEMA_INVALID");
        assertThatThrownBy(() -> new MigrationSchema(1, List.of(new TableSpec("facts", List.of(
                new ColumnSpec("id", "INTEGER", true, "(SELECT 1)", 1)), List.of())), List.of(), List.of()))
                .hasMessage("MIGRATION_SCHEMA_INVALID");
    }
}
