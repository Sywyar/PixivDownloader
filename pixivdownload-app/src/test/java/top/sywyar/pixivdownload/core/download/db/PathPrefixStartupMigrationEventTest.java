package top.sywyar.pixivdownload.core.download.db;

import jakarta.annotation.PostConstruct;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionOperations;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixCodec;
import top.sywyar.pixivdownload.core.db.pathprefix.PathPrefixMapper;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.core.db.schema.DatabaseReadyEvent;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护 {@code DatabaseReadyEvent} 的发布时机契约：事件在单例实例化早期（{@code @PostConstruct}）
 * 发布，此刻 {@code @EventListener} 尚未注册，但 {@code ApplicationListener} bean 的名字已在
 * refresh 阶段预登记到 multicaster，会被按需实例化并收到事件。
 */
@DisplayName("PathPrefixStartupMigration 经 DatabaseReadyEvent 触发")
class PathPrefixStartupMigrationEventTest {

    private SingleConnectionDataSource dataSource;
    private SqlSession sqlSession;
    private PathPrefixCodec codec;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, tempDir.resolve("state").toString());
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, tempDir.resolve("config").toString());

        dataSource = new SingleConnectionDataSource();
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setUrl("jdbc:sqlite::memory:");
        dataSource.setSuppressClose(true);

        Environment env = new Environment("test", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(PathPrefixMapper.class);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(config);
        sqlSession = factory.openSession(true);

        DatabaseSchemaRegistry registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        new DatabaseInitializer(new JdbcTemplate(dataSource),
                registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {}).initialize();

        // 默认相对 root → 符号根启用，migrate() 运行后必然写出 marker（可观察效果）
        codec = new PathPrefixCodec(sqlSession.getMapper(PathPrefixMapper.class),
                new DownloadConfig(), TestI18nBeans.appMessages());
        codec.init();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        sqlSession.close();
        dataSource.destroy();
    }

    /** 模拟 DatabaseInitializer：在自身 {@code @PostConstruct}（单例实例化早期）发布事件。 */
    static class EarlyDatabaseReadyPublisher {
        private final ApplicationEventPublisher publisher;

        EarlyDatabaseReadyPublisher(ApplicationEventPublisher publisher) {
            this.publisher = publisher;
        }

        @PostConstruct
        void publish() {
            publisher.publishEvent(new DatabaseReadyEvent());
        }
    }

    @Test
    @DisplayName("单例实例化早期发布的 DatabaseReadyEvent 应触达 ApplicationListener 形态的迁移器并执行迁移")
    void shouldRunMigrationWhenEventPublishedDuringEarlySingletonInit() {
        Path marker = RuntimeFiles.resolveDownloadRootMarkerPath();
        assertThat(marker).doesNotExist();

        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            // 发布方先于监听器注册，复现「事件先于监听 bean 实例化」的生产时序
            ctx.registerBean("earlyPublisher", EarlyDatabaseReadyPublisher.class);
            ctx.registerBean(PathPrefixStartupMigration.class,
                    () -> new PathPrefixStartupMigration(dataSource, codec,
                            DatabaseSchemaRegistry.forBuiltInPlugins().pathPrefixColumns(),
                            new DownloadConfig(), TestI18nBeans.appMessages(),
                            TransactionOperations.withoutTransaction()));
            ctx.refresh();
        }

        assertThat(marker).exists();
    }
}
