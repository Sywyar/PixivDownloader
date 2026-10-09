package top.sywyar.pixivdownload.download.media;

/** 后端动图编码资源配置，不属于作品请求或计划快照。 */
public final class UgoiraEncoderSettings {
    public static final String PREFIX = "download-workbench.ugoira";
    public static final int DEFAULT_PARALLELISM = 2;
    public static final int MAX_PARALLELISM = 8;
    public static final int DEFAULT_LOSSLESS_EFFORT = 75;
    public static final int MAX_OUTPUT_MIB = 4096;
    public static final int DEFAULT_MAX_OUTPUT_MIB = MAX_OUTPUT_MIB;
    public static final int DEFAULT_TEMPORARY_BUDGET_GIB = 18;
    public static final int MAX_TEMPORARY_BUDGET_GIB = 1024;
    public static final int DEFAULT_TIMEOUT_MINUTES = 10;
    public static final int MAX_TIMEOUT_MINUTES = 1440;

    private int parallelism = DEFAULT_PARALLELISM;
    private int losslessEffort = DEFAULT_LOSSLESS_EFFORT;
    private int maxOutputMib = DEFAULT_MAX_OUTPUT_MIB;
    private int temporaryBudgetGib = DEFAULT_TEMPORARY_BUDGET_GIB;
    private int timeoutMinutes = DEFAULT_TIMEOUT_MINUTES;

    public int getMaxOutputMib() { return maxOutputMib; }
    public void setMaxOutputMib(int value) {
        if (value < 1 || value > MAX_OUTPUT_MIB) throw new IllegalArgumentException("Invalid Ugoira output budget");
        maxOutputMib = value;
    }

    public int getTemporaryBudgetGib() { return temporaryBudgetGib; }
    public void setTemporaryBudgetGib(int value) {
        if (value < 1 || value > MAX_TEMPORARY_BUDGET_GIB) throw new IllegalArgumentException("Invalid Ugoira temporary budget");
        temporaryBudgetGib = value;
    }

    public int getTimeoutMinutes() { return timeoutMinutes; }
    public void setTimeoutMinutes(int value) {
        if (value < 1 || value > MAX_TIMEOUT_MINUTES) throw new IllegalArgumentException("Invalid Ugoira timeout");
        timeoutMinutes = value;
    }

    public int getParallelism() { return parallelism; }
    public void setParallelism(int value) {
        if (value < 1 || value > MAX_PARALLELISM) throw new IllegalArgumentException("Invalid Ugoira parallelism");
        parallelism = value;
    }

    public int getLosslessEffort() { return losslessEffort; }
    public void setLosslessEffort(int value) {
        if (value < 0 || value > 100) throw new IllegalArgumentException("Invalid WebP lossless effort");
        losslessEffort = value;
    }
}
