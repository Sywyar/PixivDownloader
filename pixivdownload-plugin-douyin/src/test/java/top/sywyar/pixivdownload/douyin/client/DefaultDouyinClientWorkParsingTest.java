package top.sywyar.pixivdownload.douyin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.client.signature.DouyinSignedUriBuilder;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpTransportException;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static top.sywyar.pixivdownload.douyin.client.DefaultDouyinClientParserTestSupport.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DefaultDouyinClient 作品媒体解析")
class DefaultDouyinClientWorkParsingTest {

    @Test
    @DisplayName("解析公开视频候选")
    void parsesVideoWork() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7351","desc":"Video title","create_time":1710000000,
                "author":{"uid":"u1","nickname":"Author"},
                "video":{"bit_rate":[{"bit_rate":2000,"play_addr":{"url_list":["https://v3.douyinvod.com/video.mp4"],"data_size":10}}],
                "cover":{"url_list":["https://p3.douyinpic.com/cover.jpg"]}}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/video/7351", null);

        assertThat(work.id()).isEqualTo("7351");
        assertThat(work.title()).isEqualTo("Video title");
        assertThat(work.description()).isEqualTo("Video title");
        assertThat(work.itemTitle()).isNull();
        assertThat(work.caption()).isNull();
        assertThat(work.authorName()).isEqualTo("Author");
        assertThat(work.kind()).isEqualTo(DouyinWorkKind.VIDEO);
        assertThat(work.media()).singleElement()
                .satisfies(media -> {
                    assertThat(media.type()).isEqualTo(DouyinMediaType.VIDEO);
                    assertThat(media.url()).isEqualTo(URI.create("https://v3.douyinvod.com/video.mp4"));
                    assertThat(media.sizeBytes()).isEqualTo(10L);
                });
    }

    @Test
    @DisplayName("分别解析 desc、item_title 和 caption 字段")
    void parsesAwemeTextFieldsSeparately() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7360","desc":"Desc text","item_title":"Item text","caption":"Caption text",
                "share_info":{"share_title":"Share text"},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/text.mp4"]}}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/video/7360", null);

        assertThat(work.description()).isEqualTo("Desc text");
        assertThat(work.itemTitle()).isEqualTo("Item text");
        assertThat(work.caption()).isEqualTo("Caption text");
        assertThat(work.title()).isEqualTo("Item text");
    }

    @Test
    @DisplayName("展示标题按 item_title、分享标题、desc、caption 和 ID 兜底")
    void titleFallbackUsesDisplayPriority() throws Exception {
        assertThat(resolveTitle("""
                {"aweme_id":"8101","desc":"Desc title","item_title":"Item title","caption":"Caption title",
                "share_info":{"share_title":"Share title"},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8101.mp4"]}}}
                """)).isEqualTo("Item title");
        assertThat(resolveTitle("""
                {"aweme_id":"8102","desc":"Desc title","caption":"Caption title",
                "share_info":{"share_title":"Share title"},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8102.mp4"]}}}
                """)).isEqualTo("Share title");
        assertThat(resolveTitle("""
                {"aweme_id":"8103","desc":"Desc title","caption":"Caption title",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8103.mp4"]}}}
                """)).isEqualTo("Desc title");
        assertThat(resolveTitle("""
                {"aweme_id":"8104","caption":"Caption title",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8104.mp4"]}}}
                """)).isEqualTo("Caption title");
        assertThat(resolveTitle("""
                {"aweme_id":"8105",
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/8105.mp4"]}}}
                """)).isEqualTo("8105");
    }

    @Test
    @DisplayName("解析图文图集图片候选")
    void parsesImageNote() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7352","desc":"Images","author":{"sec_uid":"sec","nickname":"Author"},
                "image_post_info":{"images":[
                {"watermark_free_download_url_list":["https://p3.douyinpic.com/a.jpg","https://p4.douyinpic.com/a.jpg"]},
                {"display_image":{"url_list":["https://p3.douyinpic.com/b.webp","https://p4.douyinpic.com/b.webp"]}}
                ]}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/note/7352", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.IMAGE_NOTE);
        assertThat(work.media()).hasSize(2);
        assertThat(work.media()).allMatch(media -> media.type() == DouyinMediaType.IMAGE);
        assertThat(work.media().get(1).extension()).isEqualTo("webp");
        assertThat(work.media().get(0).fallbackUrls())
                .containsExactly(URI.create("https://p4.douyinpic.com/a.jpg"));
        assertThat(work.media().get(1).fallbackUrls())
                .containsExactly(URI.create("https://p4.douyinpic.com/b.webp"));
    }

    @Test
    @DisplayName("实况照片严格在同一原始页索引配对并保留静态图位置")
    void pairsLivePhotoByOriginalNodeIndex() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7353","desc":"Live",
                "image_post_info":{"images":[{"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                "video":{}},
                {"display_image":{"url_list":["https://p3.douyinpic.com/b.jpg"]},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/live-b.mp4","https://v6.douyinvod.com/live-b.mp4"]}}},
                {"display_image":{"url_list":["https://p3.douyinpic.com/d.jpg"]}}]}}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/gallery/7353", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.LIVE_PHOTO);
        assertThat(work.media()).extracting("id")
                .containsExactly("7353-p1", "7353-p2", "7353-live-p2", "7353-p3");
        assertThat(work.media()).extracting("fileNameStem")
                .containsExactly("7353-p01", "7353-p02", "7353-live-p02", "7353-p03");
        assertThat(work.media()).extracting("type")
                .containsExactly(
                        DouyinMediaType.IMAGE, DouyinMediaType.IMAGE,
                        DouyinMediaType.LIVE_PHOTO_VIDEO, DouyinMediaType.IMAGE);
        assertThat(work.media().get(2).url())
                .isEqualTo(URI.create("https://v3.douyinvod.com/live-b.mp4"));
        assertThat(work.media().get(2).fallbackUrls())
                .containsExactly(URI.create("https://v6.douyinvod.com/live-b.mp4"));
    }

    @Test
    @DisplayName("不同图片项的静态图与动态视频不得跨项拼成实况照片")
    void rejectsCrossItemLivePhotoPairing() {
        assertCode(() -> client("""
                        {"aweme_detail":{"aweme_id":"7361","desc":"Broken pair",
                        "image_post_info":{"images":[
                          {"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]}},
                          {"video":{"play_addr":{"url_list":["https://v3.douyinvod.com/b.mp4"]}}}
                        ]}}}
                        """)
                        .resolvePublicWork("https://www.douyin.com/note/7361", null),
                DouyinClientErrorCode.MEDIA_URL_MISSING);
    }

    @Test
    @DisplayName("两个完整实况照片组按图片与动态视频相邻顺序输出")
    void keepsEachLivePhotoPairAdjacent() throws Exception {
        var work = client("""
                {"aweme_detail":{"aweme_id":"7362","desc":"Two pairs",
                "image_post_info":{"images":[
                  {"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/a.mp4"]}}},
                  {"display_image":{"url_list":["https://p3.douyinpic.com/b.jpg"]},
                   "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/b.mp4"]}}}
                ]}}}
                """).resolvePublicWork("https://www.douyin.com/note/7362", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.LIVE_PHOTO);
        assertThat(work.media()).extracting("id").containsExactly(
                "7362-p1", "7362-live-p1", "7362-p2", "7362-live-p2");
    }

    @Test
    @DisplayName("图片项级动态视频地址仍与同项静态图配对")
    void pairsItemLevelLivePhotoVideoAddress() throws Exception {
        var work = client("""
                {"aweme_detail":{"aweme_id":"7363","desc":"Alias pair",
                "image_post_info":{"images":[
                  {"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                   "video_play_addr":{"url_list":["https://v3.douyinvod.com/a.mp4"]}}
                ]}}}
                """).resolvePublicWork("https://www.douyin.com/note/7363", null);

        assertThat(work.kind()).isEqualTo(DouyinWorkKind.LIVE_PHOTO);
        assertThat(work.media()).extracting("id", "type").containsExactly(
                org.assertj.core.groups.Tuple.tuple("7363-p1", DouyinMediaType.IMAGE),
                org.assertj.core.groups.Tuple.tuple("7363-live-p1", DouyinMediaType.LIVE_PHOTO_VIDEO));
    }

    @Test
    @DisplayName("声明了动态视频结构但没有有效地址时不得降级为普通图文")
    void rejectsLivePhotoMotionWithoutUsableUrl() {
        assertCode(() -> client("""
                        {"aweme_detail":{"aweme_id":"7364","desc":"Missing motion",
                        "image_post_info":{"images":[
                          {"display_image":{"url_list":["https://p3.douyinpic.com/a.jpg"]},
                           "video":{"play_addr":{"url_list":[]}}}
                        ]}}}
                        """)
                        .resolvePublicWork("https://www.douyin.com/note/7364", null),
                DouyinClientErrorCode.MEDIA_URL_MISSING);
    }

    @Test
    @DisplayName("多个图片数组别名共存时只解析首个非空数组")
    void usesFirstNonEmptyImageArrayWithoutDuplicates() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7354","desc":"Aliases",
                "image_post_info":{
                  "images":[{"display_image":{"url_list":["https://p3.douyinpic.com/canonical.jpg"]}}],
                  "image_list":[{"display_image":{"url_list":["https://p3.douyinpic.com/nested-alias.jpg"]}}]
                },
                "images":[{"display_image":{"url_list":["https://p3.douyinpic.com/top-images.jpg"]}}],
                "image_list":[{"display_image":{"url_list":["https://p3.douyinpic.com/top-list.jpg"]}}]}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/note/7354", null);

        assertThat(work.media()).singleElement().satisfies(media -> {
            assertThat(media.id()).isEqualTo("7354-p1");
            assertThat(media.url()).isEqualTo(URI.create("https://p3.douyinpic.com/canonical.jpg"));
        });
    }

    @Test
    @DisplayName("规范图片数组为空时解析顶层 image_list 并保留页索引")
    void parsesTopLevelImageListAfterEmptyAliases() throws Exception {
        DefaultDouyinClient client = client("""
                {"aweme_detail":{"aweme_id":"7355","desc":"Top level list",
                "image_post_info":{"images":[],"image_list":[]},
                "images":[],
                "image_list":[{"display_image":{"url_list":["https://p3.douyinpic.com/top.jpg"]},
                "video":{"play_addr":{"url_list":["https://v3.douyinvod.com/top-live.mp4"]}}}]}}
                """);

        var work = client.resolvePublicWork("https://www.douyin.com/note/7355", null);

        assertThat(work.media()).extracting("id")
                .containsExactly("7355-p1", "7355-live-p1");
        assertThat(work.media()).extracting("type")
                .containsExactly(DouyinMediaType.IMAGE, DouyinMediaType.LIVE_PHOTO_VIDEO);
    }
}
