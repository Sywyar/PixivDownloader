package top.sywyar.pixivdownload.translation;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TranslationConfig {
    private String engine = "ai";
    private String youdaoAppKey = "";
    private String youdaoAppSecret = "";
    private String libretranslateUrl = "";
    private String libretranslateApiKey = "";
    private int maxConcurrent = 10;
    private int aiSegmentSize;
    private MachineSettings youdao = new MachineSettings(5000, 50, 1000);
    private MachineSettings libretranslate = new MachineSettings(5000, 10, 0);

    public MachineSettings machine(String engine) {
        return switch (engine) {
            case "youdao" -> youdao;
            case "libretranslate" -> libretranslate;
            default -> throw new IllegalArgumentException("translation.unknown-engine");
        };
    }

    public void validate() {
        if (maxConcurrent < 1 || aiSegmentSize < 0 || aiSegmentSize > 1_000_000)
            throw new IllegalArgumentException("translation.invalid-settings");
        youdao.validate(5000);
        libretranslate.validate(1_000_000);
    }

    /** 每个引擎分别绑定；字符数按 Unicode 码点计，批次总量也受此预算约束。 */
    @Getter
    @Setter
    public static class MachineSettings {
        private int requestChars;
        private int batchSize;
        private int requestIntervalMs;
        private int requestConcurrency = 1;
        private int maxRetries = 3;
        private int readTimeoutSeconds = 120;
        private boolean useProxy;

        public MachineSettings() { this(5000, 10, 0); }
        public MachineSettings(int requestChars, int batchSize, int requestIntervalMs) {
            this.requestChars = requestChars;
            this.batchSize = batchSize;
            this.requestIntervalMs = requestIntervalMs;
        }
        private void validate(int maxChars) {
            if (requestChars < 1 || requestChars > maxChars || batchSize < 1 || batchSize > 100
                    || requestIntervalMs < 0 || requestIntervalMs > 60_000
                    || requestConcurrency < 1 || requestConcurrency > 32
                    || maxRetries < 0 || maxRetries > 10
                    || readTimeoutSeconds < 1 || readTimeoutSeconds > 3600)
                throw new IllegalArgumentException("translation.invalid-settings");
        }
    }
}
