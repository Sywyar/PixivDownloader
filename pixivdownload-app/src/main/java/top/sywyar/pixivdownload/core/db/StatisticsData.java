package top.sywyar.pixivdownload.core.db;

public record StatisticsData(
        int totalArtworks,
        int totalImages,
        int totalMoved,
        String dailyDate,
        int dailyCompleted,
        int dailyFailed
) {}
