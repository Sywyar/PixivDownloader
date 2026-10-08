package top.sywyar.pixivdownload.setup.guest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import top.sywyar.pixivdownload.config.http.ServerAddressProvider;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.metadata.GuestRestriction;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryQuery;
import top.sywyar.pixivdownload.core.metadata.artwork.GalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelGalleryRepository;
import top.sywyar.pixivdownload.core.metadata.novel.NovelWorkSearch;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.core.work.query.WorkQuery;
import top.sywyar.pixivdownload.i18n.LocalizedException;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.registry.schema.DatabaseSchemaRegistry;
import top.sywyar.pixivdownload.setup.guest.dto.InviteCreateRequest;
import top.sywyar.pixivdownload.setup.guest.persistence.GuestInviteMapper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("访客收藏夹持久化与真实数据库查询")
class GuestCollectionVisibilityTest {
    private SingleConnectionDataSource source;
    private JdbcTemplate jdbc;
    private DatabaseInitializer initializer;
    private GuestInviteMapper mapper;
    private GuestInviteService invites;
    private GalleryRepository artworks;
    private NovelGalleryRepository novels;

    @BeforeEach
    void setUp() throws Exception {
        source = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        jdbc = new JdbcTemplate(source);
        var registry = DatabaseSchemaRegistry.forBuiltInPlugins();
        initializer = new DatabaseInitializer(jdbc, registry.contributions(), registry.mergedSchema(),
                TestI18nBeans.appMessages(), event -> {});
        initializer.initialize();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        var sessions = factory.getObject();
        sessions.getConfiguration().addMapper(GuestInviteMapper.class);
        mapper = new SqlSessionTemplate(sessions).getMapper(GuestInviteMapper.class);
        ServerAddressProvider address = mock(ServerAddressProvider.class);
        when(address.uri(anyString())).thenReturn(URI.create("http://localhost/invite"));
        var proxy = new ProxyFactory(new GuestInviteService(mapper, address, initializer));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),
                new AnnotationTransactionAttributeSource()));
        invites = (GuestInviteService) proxy.getProxy();
        artworks = new GalleryRepository(source);
        novels = new NovelGalleryRepository(source);

        // 1、2 同属 haha；2 超出年龄范围；3 同属可见与隐藏收藏夹；4 未收藏；5 已删除。
        for (String table : List.of("artworks", "novels")) {
            String id = table.equals("artworks") ? "artwork_id" : "novel_id";
            for (int i = 1; i <= 5; i++) {
                jdbc.update("INSERT INTO " + table + "(" + id
                                + ",title,folder,count,extensions,time,\"R18\",deleted) VALUES(?,?,'{0}/sample',1,'jpg',?,?,?)",
                        i, "work " + i, i, i == 2 ? 1 : 0, i == 5 ? 1 : 0);
            }
            String membership = table.equals("artworks") ? "artwork_collections" : "novel_collections";
            for (int i : List.of(1, 2, 5)) {
                jdbc.update("INSERT INTO " + membership + "(collection_id," + id + ",added_time) VALUES(1,?,1)", i);
            }
            jdbc.update("INSERT INTO " + membership + "(collection_id," + id + ",added_time) VALUES(2,3,1),(3,3,1)");
        }
    }

    @AfterEach
    void close() {
        source.destroy();
    }

    @Test
    @DisplayName("收藏夹计数只含可见且未删除作品，列表与单作品作用域使用同一配置")
    void countsAndQueriesRespectCollectionScope() {
        GuestRestriction filters = restriction(false, List.of(1L, 2L));
        assertThat(artworks.countVisibleWorksByCollection(filters)).isEqualTo(Map.of(1L, 1L, 2L, 1L, 3L, 1L));
        assertThat(novels.countVisibleWorksByCollection(filters)).isEqualTo(Map.of(1L, 1L, 2L, 1L, 3L, 1L));
        assertThat(artworks.findArtworkIds(query(filters, null)).ids()).containsExactly(4L, 3L, 1L);
        assertThat(novels.findVisibleNovelIds(filters)).containsExactly(4L, 3L, 1L);

        GuestRestriction works = restriction(true, List.of(1L, 2L));
        assertThat(artworks.findArtworkIds(query(works, null)).ids()).containsExactly(4L, 1L);
        assertThat(novels.findVisibleNovelIds(works)).containsExactly(4L, 1L);
        assertThat(artworks.countVisibleWorksByCollection(works)).isEqualTo(Map.of(1L, 1L));
        assertThat(novels.countVisibleWorksByCollection(works)).isEqualTo(Map.of(1L, 1L));
        assertThat(artworks.findArtworkIds(query(restriction(true, List.of()), null)).ids()).containsExactly(4L);
        assertThat(novels.findVisibleNovelIds(restriction(true, List.of()))).containsExactly(4L);
    }

    @Test
    @DisplayName("仅隐藏入口时也不能通过手工收藏夹筛选查询隐藏归属")
    void hiddenCollectionCannotBeUsedAsFilter() {
        GuestRestriction filters = restriction(false, List.of(1L, 2L));
        assertThat(artworks.findArtworkIds(query(filters, List.of(3L))).totalElements()).isZero();
        assertThat(artworks.findArtworkIds(query(filters, List.of(1L))).ids()).containsExactly(1L);
        var search = new NovelWorkSearch(null, novels, null);
        assertThat(search.filteredIds(WorkQuery.builder(WorkType.NOVEL).collectionIds(List.of(3L)).build(), filters)).isEmpty();
    }

    @Test
    @DisplayName("邀请创建、编辑、会话恢复与删除完整保存收藏夹规则")
    void inviteRulesRoundTrip() {
        var request = request();
        request.setCollectionUnrestricted(false);
        request.setCollectionIds(List.of(1L, 2L, 1L));
        request.setCollectionRestrictsWorks(true);
        long id = invites.createInvite(request);
        var detail = invites.detail(id);
        assertThat(detail.collectionIds()).containsExactly(1L, 2L);
        assertThat(detail.collectionRestrictsWorks()).isTrue();
        var session = invites.resolveByCode(detail.code()).orElseThrow();
        var scope = new GuestWorkVisibilityScopeFactory().fromSession(session);
        for (WorkType type : WorkType.values()) {
            assertThat(scope.restrictionFor(type).collectionIds()).containsExactlyInAnyOrder(1L, 2L);
            assertThat(scope.restrictionFor(type).collectionUnrestricted()).isFalse();
            assertThat(scope.restrictionFor(type).collectionRestrictsWorks()).isTrue();
        }
        request.setCollectionIds(List.of(2L));
        request.setCollectionRestrictsWorks(false);
        invites.updateInvite(id, request);
        assertThat(invites.detail(id).collectionIds()).containsExactly(2L);
        assertThat(invites.resolveByCode(detail.code()).orElseThrow().collectionRestrictsWorks()).isFalse();
        invites.delete(id);
        assertThat(mapper.findInviteCollectionIds(id)).isEmpty();
        assertThat(invites.resolveByCode(detail.code())).isEmpty();
    }

    @Test
    @DisplayName("旧邀请自动补列后保留原有范围，非法收藏夹输入拒绝且不修改现有规则")
    void migrationAndInvalidInputPreservePermissions() {
        long id = invites.createInvite(request());
        jdbc.execute("ALTER TABLE guest_invites DROP COLUMN collection_unrestricted");
        jdbc.execute("ALTER TABLE guest_invites DROP COLUMN collection_restricts_works");
        initializer.initialize();
        assertThat(invites.detail(id).collectionUnrestricted()).isTrue();
        assertThat(invites.detail(id).collectionRestrictsWorks()).isFalse();
        for (List<Long> ids : List.of(List.of(0L), List.of(-1L),
                java.util.Collections.<Long>singletonList(null), java.util.Collections.nCopies(501, 1L))) {
            var invalid = request();
            invalid.setCollectionIds(ids);
            assertThatThrownBy(() -> invites.updateInvite(id, invalid)).isInstanceOf(LocalizedException.class);
        }
        assertThat(invites.detail(id).collectionUnrestricted()).isTrue();
    }

    @Test
    @DisplayName("收藏夹关联写入失败时邀请属性与旧白名单一并回滚")
    void failedUpdateRollsBackWholeInvite() {
        var original = request();
        original.setCollectionUnrestricted(false);
        original.setCollectionIds(List.of(1L));
        long id = invites.createInvite(original);
        jdbc.execute("CREATE TRIGGER reject_collection BEFORE INSERT ON guest_invite_collections"
                + " WHEN NEW.collection_id = 2 BEGIN SELECT RAISE(ABORT, 'fixture failure'); END");
        var update = request();
        update.setName("changed");
        update.setCollectionUnrestricted(false);
        update.setCollectionRestrictsWorks(true);
        update.setCollectionIds(List.of(2L));
        assertThatThrownBy(() -> invites.updateInvite(id, update)).isInstanceOf(RuntimeException.class);
        var detail = invites.detail(id);
        assertThat(detail.name()).isEqualTo("guest");
        assertThat(detail.collectionIds()).containsExactly(1L);
        assertThat(detail.collectionRestrictsWorks()).isFalse();
    }

    @Test
    @DisplayName("邀请过期与维护清理同时移除收藏夹规则")
    void expiredInvitesCleanCollectionRules() {
        for (int mode = 0; mode < 3; mode++) {
            var request = request();
            request.setCollectionUnrestricted(false);
            request.setCollectionIds(List.of(1L));
            long id = invites.createInvite(request);
            String code = invites.detail(id).code();
            jdbc.update("UPDATE guest_invites SET expire_time = 1 WHERE id = ?", id);
            if (mode == 0) assertThat(invites.resolveByCode(code)).isEmpty();
            else if (mode == 1) invites.deleteExpired(System.currentTimeMillis());
            else invites.purgeExpiredAndRevoked(System.currentTimeMillis());
            assertThat(mapper.findInviteCollectionIds(id)).isEmpty();
        }
    }

    private InviteCreateRequest request() {
        var request = new InviteCreateRequest();
        request.setName("guest");
        return request;
    }

    private GuestRestriction restriction(boolean works, List<Long> ids) {
        return new GuestRestriction(Set.of(0), true, List.of(), true, List.of(), false, ids, works);
    }

    private GalleryQuery query(GuestRestriction restriction, List<Long> collections) {
        return GalleryQuery.builder().page(0).size(20).sort("date").order("desc")
                .guestRestriction(restriction).collectionIds(collections).build();
    }
}
