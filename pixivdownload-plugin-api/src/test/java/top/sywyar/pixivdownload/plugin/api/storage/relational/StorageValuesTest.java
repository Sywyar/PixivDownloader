package top.sywyar.pixivdownload.plugin.api.storage.relational;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationValues;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("提供者统一文本规范化")
class StorageValuesTest {
    @Test
    @DisplayName("有效 Unicode 文本仅移除 NUL，保留其余内容和空值语义")
    void normalizesOnlyNul() {
        assertThat(StorageValues.normalize("\0你好\0🌸\r\n\t <br> \\u0000 \\0\0"))
                .isEqualTo("你好🌸\r\n\t <br> \\u0000 \\0");
        assertThat(StorageValues.normalize("\0\0")).isEqualTo("");
        assertThat(StorageValues.normalize("")).isEqualTo("");
        assertThat(StorageValues.normalize(null)).isNull();
        var bytes = new byte[]{0, 1, (byte) 255, 0};
        assertThat(StorageValues.normalize(bytes)).isSameAs(bytes);
        assertThat(bytes).containsExactly(0, 1, (byte) 255, 0);
        assertThat(StorageValues.normalize(9007199254740993L)).isEqualTo(9007199254740993L);
    }

    @Test
    @DisplayName("完整性摘要仍区分原文与清洗结果，不能隐藏目标数据变化")
    void preservesRawVerification() {
        String original = "a\0b";
        assertThat(MigrationValues.canonical(original, "TEXT")).isEqualTo(original);
        var raw = MigrationValues.newDigest();
        var normalized = MigrationValues.newDigest();
        MigrationValues.hash(raw, original);
        MigrationValues.hash(normalized, StorageValues.normalize(original));
        assertThat(MigrationValues.finish(raw)).isNotEqualTo(MigrationValues.finish(normalized));
    }
}
