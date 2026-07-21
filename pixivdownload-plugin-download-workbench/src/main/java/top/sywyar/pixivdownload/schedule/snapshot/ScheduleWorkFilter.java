package top.sywyar.pixivdownload.schedule.snapshot;

import top.sywyar.pixivdownload.core.db.TagDto;
import top.sywyar.pixivdownload.download.PixivFetchService;
import top.sywyar.pixivdownload.schedule.snapshot.ScheduleTaskSnapshot.Filters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pixiv 插画来源对单个作品应用计划任务筛选条件的纯函数。 */
public final class ScheduleWorkFilter {

    private ScheduleWorkFilter() {
    }

    public static boolean artworkMatches(PixivFetchService.ArtworkMeta artwork, Filters filters) {
        if (!contentMatches(filters.content(), artwork.xRestrict())) return false;
        if ("exclude".equals(filters.aiFilter()) && artwork.ai()) return false;
        if ("only".equals(filters.aiFilter()) && !artwork.ai()) return false;
        if (!typeMatches(filters.typeFilter(), artwork.illustType())) return false;
        if (artwork.pageCount() > 0) {
            if (filters.pagesMin() != null && artwork.pageCount() < filters.pagesMin()) return false;
            if (filters.pagesMax() != null && artwork.pageCount() > filters.pagesMax()) return false;
        }
        if (artwork.bookmarkCount() >= 0) {
            if (filters.bookmarksMin() != null && artwork.bookmarkCount() < filters.bookmarksMin()) return false;
            if (filters.bookmarksMax() != null && artwork.bookmarkCount() > filters.bookmarksMax()) return false;
        }
        List<String> tokens = tagTokens(artwork.tags());
        return tagsAllMatch(tokens, filters.tagsExact(), false)
                && tagsAllMatch(tokens, filters.tagsFuzzy(), true);
    }

    private static boolean contentMatches(String content, int xRestrict) {
        if (content == null) return true;
        return switch (content) {
            case "safe" -> xRestrict == 0;
            case "r18plus" -> xRestrict >= 1;
            case "r18" -> xRestrict == 1;
            case "r18g" -> xRestrict == 2;
            default -> true;
        };
    }

    private static boolean typeMatches(String typeFilter, int illustType) {
        if (typeFilter == null || "all".equals(typeFilter)) return true;
        return switch (typeFilter) {
            case "illust" -> illustType == 0;
            case "manga" -> illustType == 1;
            case "ugoira" -> illustType == 2;
            default -> true;
        };
    }

    private static boolean tagsAllMatch(List<String> tokens, List<String> required, boolean fuzzy) {
        if (required == null || required.isEmpty()) return true;
        for (String needle : required) {
            boolean hit = false;
            for (String token : tokens) {
                if (fuzzy ? token.contains(needle) : token.equals(needle)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) return false;
        }
        return true;
    }

    private static List<String> tagTokens(List<TagDto> tags) {
        List<String> tokens = new ArrayList<>();
        if (tags == null) return tokens;
        for (TagDto tag : tags) {
            if (tag.getName() != null && !tag.getName().isBlank()) {
                tokens.add(tag.getName().toLowerCase(Locale.ROOT));
            }
            if (tag.getTranslatedName() != null && !tag.getTranslatedName().isBlank()) {
                tokens.add(tag.getTranslatedName().toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }
}
