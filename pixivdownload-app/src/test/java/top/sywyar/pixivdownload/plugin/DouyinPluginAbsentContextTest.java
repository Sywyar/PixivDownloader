package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.gallery.model.GalleryKind;
import top.sywyar.pixivdownload.core.gallery.model.projection.GalleryDataAccess;
import top.sywyar.pixivdownload.core.gallery.query.GalleryProjectionQuery;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortDirection;
import top.sywyar.pixivdownload.core.gallery.query.GallerySortField;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryCapabilityRegistry;
import top.sywyar.pixivdownload.core.gallery.runtime.GalleryProjectionBroker;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "pixivdownload.config-dir=target/test-runtime/config",
        "pixivdownload.state-dir=target/test-runtime/state",
        "pixivdownload.data-dir=target/test-runtime/data",
        "pixivdownload.plugins-dir=target/test-runtime/plugins-absent-douyin",
        "setup.browser.auto-open=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("douyin 外置插件缺席时中性画廊查询语义")
class DouyinPluginAbsentContextTest {

    static {
        System.setProperty(RuntimeFiles.CONFIG_DIR_PROPERTY, "target/test-runtime/config");
        System.setProperty(RuntimeFiles.STATE_DIR_PROPERTY, "target/test-runtime/state");
        System.setProperty(RuntimeFiles.DATA_DIR_PROPERTY, "target/test-runtime/data");
        System.setProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY, "target/test-runtime/plugins-absent-douyin");
    }

    @Autowired
    private GalleryCapabilityRegistry galleryCapabilityRegistry;
    @Autowired
    private GalleryProjectionBroker galleryProjectionBroker;

    @AfterAll
    static void tearDownRuntimeDirs() {
        System.clearProperty(RuntimeFiles.CONFIG_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.STATE_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.DATA_DIR_PROPERTY);
        System.clearProperty(RuntimeFiles.PLUGINS_DIR_PROPERTY);
    }

    @Test
    @DisplayName("douyin 缺席时管理视频查询、计数和 facet 返回空且无异常")
    void missingDouyinProviderReturnsEmptyVideoQuery() {
        GalleryProjectionQuery query = new GalleryProjectionQuery(
                GalleryKind.VIDEO, "douyin", List.of(), GallerySortField.DOWNLOADED_AT,
                GallerySortDirection.DESC, null, 10);
        Set<GalleryDataAccess> adminAccess = Set.of(GalleryDataAccess.SHARED, GalleryDataAccess.ADMIN_ONLY);

        var page = galleryProjectionBroker.page(query, adminAccess);
        var count = galleryProjectionBroker.count(query, adminAccess);
        var facets = galleryProjectionBroker.facets(query, adminAccess);

        assertThat(galleryCapabilityRegistry.resolveProjections(GalleryKind.VIDEO, "douyin")).isEmpty();
        assertThat(page.projections()).isEmpty();
        assertThat(count.count()).isZero();
        assertThat(page.diagnostics()).isEmpty();
        assertThat(facets.facets()).isEmpty();
        assertThat(facets.diagnostics()).isEmpty();
    }
}
