package top.sywyar.pixivdownload.plugin.api.gui;

/**
 * Generic response-array summary declaration for GUI config action results.
 *
 * @param arrayPath dot-separated path to the JSON array
 * @param labelPath path inside each array item used as the leading label
 * @param statusPath optional path inside each item used as a status token
 * @param successStatus status value omitted from the summary
 * @param detailPath optional path inside each item used as detail text
 */
public record GuiConfigActionResultSummary(
        String arrayPath,
        String labelPath,
        String statusPath,
        String successStatus,
        String detailPath
) {

    public GuiConfigActionResultSummary {
        arrayPath = arrayPath == null ? "" : arrayPath.trim();
        labelPath = labelPath == null ? "" : labelPath.trim();
        statusPath = statusPath == null ? "" : statusPath.trim();
        successStatus = successStatus == null ? "" : successStatus;
        detailPath = detailPath == null ? "" : detailPath.trim();
    }

    public static GuiConfigActionResultSummary allItems(String arrayPath, String labelPath, String detailPath) {
        return new GuiConfigActionResultSummary(arrayPath, labelPath, "", "", detailPath);
    }

    public static GuiConfigActionResultSummary nonSuccessItems(String arrayPath, String labelPath,
                                                              String statusPath, String successStatus,
                                                              String detailPath) {
        return new GuiConfigActionResultSummary(arrayPath, labelPath, statusPath, successStatus, detailPath);
    }
}
