package top.sywyar.pixivdownload.gui;

/** 桌面兜底的当前原因；选择冲突不等同于插件启动失败。 */
public record DesktopUiFailure(Reason reason, String detail) {
    public enum Reason {
        NO_PROVIDER("no-provider"),
        SELECTION_FAILED("gui-selection"),
        ALL_FAILED("no-gui");

        private final String key;

        Reason(String key) { this.key = key; }

        public String adviceKey() { return "recovery.advice." + key; }
        public String summaryKey() { return "recovery.summary." + key; }
    }
}
