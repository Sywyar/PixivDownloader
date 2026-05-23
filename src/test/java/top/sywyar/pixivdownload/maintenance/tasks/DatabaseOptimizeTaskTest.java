package top.sywyar.pixivdownload.maintenance.tasks;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import top.sywyar.pixivdownload.maintenance.MaintenanceContext;

import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("DatabaseOptimizeTask 单元测试")
class DatabaseOptimizeTaskTest {

    private SingleConnectionDataSource dataSource;
    private DatabaseOptimizeTask task;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        // 建一张带索引的小表 + 写几行数据，让 PRAGMA optimize 有可分析的目标
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE sample (id INTEGER PRIMARY KEY, value TEXT)");
        jdbc.execute("CREATE INDEX idx_sample_value ON sample(value)");
        jdbc.update("INSERT INTO sample(id, value) VALUES (?, ?)", 1, "a");
        jdbc.update("INSERT INTO sample(id, value) VALUES (?, ?)", 2, "b");

        task = new DatabaseOptimizeTask(dataSource);
    }

    @AfterEach
    void tearDown() {
        dataSource.destroy();
    }

    @Test
    @DisplayName("name() 返回 'database-optimize' 用于日志辨识")
    void shouldExposeStableTaskName() {
        assertThatCode(() -> task.name()).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThat(task.name()).isEqualTo("database-optimize");
    }

    @Test
    @DisplayName("execute 调用 PRAGMA optimize 不抛异常")
    void shouldRunPragmaOptimizeWithoutError() {
        MaintenanceContext ctx = new MaintenanceContext("manual", System.currentTimeMillis());
        assertThatCode(() -> task.execute(ctx)).doesNotThrowAnyException();
    }
}
