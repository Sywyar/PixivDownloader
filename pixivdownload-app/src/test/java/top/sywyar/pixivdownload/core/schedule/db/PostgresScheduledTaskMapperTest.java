package top.sywyar.pixivdownload.core.schedule.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import top.sywyar.pixivdownload.core.db.PostgresTestDatabase;

@EnabledIfEnvironmentVariable(named = "PIXIV_TEST_POSTGRES_URL", matches = ".+")
@DisplayName("真实 PostgreSQL 计划任务持久化")
class PostgresScheduledTaskMapperTest extends ScheduledTaskMapperTest {
    private PostgresTestDatabase database;

    @Override
    javax.sql.DataSource createDataSource() throws Exception {
        database = new PostgresTestDatabase();
        return database.pool();
    }

    @Override
    @AfterEach
    void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            if (database != null) database.close();
        }
    }
}
