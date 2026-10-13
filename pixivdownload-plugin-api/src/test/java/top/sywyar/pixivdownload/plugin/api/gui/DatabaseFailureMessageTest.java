package top.sywyar.pixivdownload.plugin.api.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("数据库失败原因的共享本地化合同")
class DatabaseFailureMessageTest {
    @ParameterizedTest
    @CsvSource({
            "28P01, authentication", "SQLSTATE_28P01, authentication",
            "28000, authorization", "SQLSTATE_08001, connection", "SQLSTATE_3D000, database-missing",
            "SQLSTATE_42501, permission", "DATABASE_USERNAME_REQUIRED, username-required",
            "DATABASE_ENCODING_UNSUPPORTED, encoding", "DATABASE_PRODUCT_UNSUPPORTED, product",
            "DATABASE_SCHEMA_MISSING, schema-missing", "DATABASE_TARGET_MISMATCH, target-mismatch",
            "DATABASE_PROVIDER_UNAVAILABLE, provider-unavailable", "TARGET_NOT_EMPTY, target-not-empty",
            "MIGRATION_PLAN_CHANGED, plan-changed", "MIGRATION_SOURCE_CHANGED, source-changed",
            "DATA_MISMATCH, data-mismatch", "MIGRATION_BUSY, busy", "MIGRATION_INTERRUPTED, interrupted"
    })
    @DisplayName("连接检查和迁移诊断提供相同的可操作原因")
    void mapsControlledFailures(String code, String reason) {
        assertThat(DesktopDatabaseSettings.failureMessageKey(code)).isEqualTo("gui.config.database.error." + reason);
    }

    @Test
    @DisplayName("空值、未知诊断和带凭据正文不会成为文案键")
    void rejectsUncontrolledText() {
        for (String code : new String[]{null, "", "future-code", "SQLSTATE_28P01 password=private",
                "08001 jdbc:fixture://user:secret@host/db", "SQLSTATE_UNKNOWN"}) {
            assertThat(DesktopDatabaseSettings.failureMessageKey(code)).isEqualTo("gui.config.database.error.unknown");
        }
    }
}
