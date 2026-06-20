package top.sywyar.pixivdownload.download.schedule.source;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import java.util.List;

/**
 * 搜索来源（{@code SEARCH}）：唯一一个多模式来源，按 params 二分发现模式——
 * <ul>
 *   <li>{@code date_d + maxPages==-1}：时间倒序增量，走 ID 水位线（逐页翻到追平历史）；</li>
 *   <li>非 {@code date_d} 但 {@code maxPages==-1}：排序非 ID 单调，走「命中第一个已下载即停」的边界扫描；</li>
 *   <li>固定页数：全量发现该若干页、跳过已下载，不封顶。</li>
 * </ul>
 * 插画 / 小说按任务 kind 选对应发现接口。
 */
public final class SearchSource extends AbstractScheduledSource {

    public SearchSource() {
        super(ScheduledTaskType.SEARCH);
    }

    @Override
    public DiscoveryMode mode(JsonNode source) {
        int maxPages = source.path("maxPages").asInt(3);
        boolean dateDesc = "date_d".equals(source.path("order").asText("date_d"));
        if (maxPages == -1 && dateDesc) {
            return DiscoveryMode.WATERMARK;
        }
        if (maxPages == -1) {
            return DiscoveryMode.DOWNLOADED_BOUNDARY;
        }
        return DiscoveryMode.FULL;
    }

    @Override
    public boolean accountScoped() {
        return false;
    }

    @Override
    public String notificationLabelKey() {
        return "mail.template.common.task-type.search";
    }

    @Override
    public void discoverAndDispatch(ScheduledSourceContext ctx) throws Exception {
        JsonNode s = ctx.source();
        switch (mode(s)) {
            case WATERMARK -> ctx.watermarkScan(searchPages(ctx), ctx.watermarkPageDelay());
            case DOWNLOADED_BOUNDARY -> ctx.boundaryScan(searchPages(ctx));
            default -> {
                // 固定页 SEARCH 不封顶（前端也隐藏 fetchLimit 字段，此处即便误带也不生效）。
                String word = s.path("word").asText("");
                String order = s.path("order").asText("date_d");
                String mode = s.path("mode").asText("all");
                String sMode = s.path("sMode").asText("s_tag");
                int maxPages = s.path("maxPages").asInt(3);
                List<String> ids = ctx.novel()
                        ? ctx.fetch().discoverSearchNovelIds(word, order, mode, sMode, maxPages, ctx.cookie())
                        : ctx.fetch().discoverSearchArtworkIds(word, order, mode, sMode, maxPages, ctx.cookie());
                ctx.fullScan(ids, 0);
            }
        }
    }

    /** 逐页 supplier：插画 / 小说各自的「搜索结果第 N 页」。 */
    private PageSupplier searchPages(ScheduledSourceContext ctx) {
        JsonNode s = ctx.source();
        String word = s.path("word").asText("");
        String order = s.path("order").asText("date_d");
        String mode = s.path("mode").asText("all");
        String sMode = s.path("sMode").asText("s_tag");
        return ctx.novel()
                ? p -> ctx.fetch().discoverSearchNovelIdsPage(word, order, mode, sMode, p, ctx.cookie())
                : p -> ctx.fetch().discoverSearchArtworkIdsPage(word, order, mode, sMode, p, ctx.cookie());
    }
}
