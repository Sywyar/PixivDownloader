package top.sywyar.pixivdownload.stats;

import lombok.RequiredArgsConstructor;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.util.ArrayList;
import java.util.List;

@PluginManagedBean
@RequiredArgsConstructor
public class StatsService {

    private final StatsRepository statsRepository;

    private static final int DEFAULT_TOP_AUTHORS = 15;
    private static final int DEFAULT_TOP_TAGS = 50;
    private static final int MAX_TOP = 200;

    public StatsDto.Dashboard dashboard(int topAuthors, int topTags) {
        int authorLimit = clamp(topAuthors, DEFAULT_TOP_AUTHORS);
        int tagLimit = clamp(topTags, DEFAULT_TOP_TAGS);
        return new StatsDto.Dashboard(
                statsRepository.overview(),
                resolveAuthorNames(statsRepository.topAuthors(authorLimit)),
                statsRepository.topTags(tagLimit),
                statsRepository.monthlyArtworkCounts());
    }

    /** 作者表缺名时回退到 author_id 字符串，保证前端始终有可显示的标签。 */
    private List<StatsDto.AuthorStat> resolveAuthorNames(List<StatsDto.AuthorStat> authors) {
        List<StatsDto.AuthorStat> out = new ArrayList<>(authors.size());
        for (StatsDto.AuthorStat a : authors) {
            String name = (a.name() == null || a.name().isBlank())
                    ? String.valueOf(a.authorId()) : a.name();
            out.add(new StatsDto.AuthorStat(a.authorId(), name, a.count()));
        }
        return out;
    }

    private int clamp(int requested, int fallback) {
        if (requested <= 0) return fallback;
        return Math.min(requested, MAX_TOP);
    }
}
