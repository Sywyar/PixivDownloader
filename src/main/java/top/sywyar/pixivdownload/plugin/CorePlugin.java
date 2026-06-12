package top.sywyar.pixivdownload.plugin;

import top.sywyar.pixivdownload.author.AuthorSchemaContribution;
import top.sywyar.pixivdownload.collection.CollectionSchemaContribution;
import top.sywyar.pixivdownload.core.db.ArtworkSchemaContribution;
import top.sywyar.pixivdownload.core.db.CoreSchemaContribution;
import top.sywyar.pixivdownload.core.db.FileNameSchemaContribution;
import top.sywyar.pixivdownload.core.db.ImageHashSchemaContribution;
import top.sywyar.pixivdownload.core.db.StatisticsSchemaContribution;
import top.sywyar.pixivdownload.core.db.TagSchemaContribution;
import top.sywyar.pixivdownload.novel.db.NovelSchemaContribution;
import top.sywyar.pixivdownload.plugin.api.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.PluginKind;
import top.sywyar.pixivdownload.plugin.api.SchemaContribution;
import top.sywyar.pixivdownload.schedule.db.ScheduleSchemaContribution;
import top.sywyar.pixivdownload.series.MangaSeriesSchemaContribution;
import top.sywyar.pixivdownload.setup.guest.GuestInviteSchemaContribution;

import java.util.List;

/**
 * 核心插件：承载核心层（schema、公共静态资源、基础路由等）的 contribution 声明。
 * <p>
 * 受管 schema 按领域拆成独立 contribution 类、但全部由核心声明——按「卸载投影测试」
 * （主人插件未安装时其他部件仍需要的表归核心），现存全部长期事实表的 ownerPluginId
 * 一律为 core；功能插件的 {@code schema()} 现阶段为空，插件私有表只在未来出现
 * 交互状态 / 临时队列 / 可重建缓存时才产生。
 */
public class CorePlugin implements PixivFeaturePlugin {

    @Override
    public String id() {
        return "core";
    }

    @Override
    public String displayName() {
        return "核心";
    }

    @Override
    public PluginKind kind() {
        return PluginKind.CORE;
    }

    @Override
    public List<SchemaContribution> schema() {
        return List.of(
                CoreSchemaContribution.CONTRIBUTION,
                ArtworkSchemaContribution.CONTRIBUTION,
                FileNameSchemaContribution.CONTRIBUTION,
                StatisticsSchemaContribution.CONTRIBUTION,
                TagSchemaContribution.CONTRIBUTION,
                ImageHashSchemaContribution.CONTRIBUTION,
                AuthorSchemaContribution.CONTRIBUTION,
                MangaSeriesSchemaContribution.CONTRIBUTION,
                CollectionSchemaContribution.CONTRIBUTION,
                GuestInviteSchemaContribution.CONTRIBUTION,
                NovelSchemaContribution.CONTRIBUTION,
                ScheduleSchemaContribution.CONTRIBUTION);
    }
}
