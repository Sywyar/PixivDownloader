package top.sywyar.pixivdownload.plugin.runtime.admission;

/** 准入结果；warning 不阻断，rejected 必须发生在 PF4J load 前。 */
public record PluginArtifactAdmissionResult(boolean allowed, boolean warning, String code, String detail) {
    public static PluginArtifactAdmissionResult allow() {
        return new PluginArtifactAdmissionResult(true, false, "ALLOWED", null);
    }
    public static PluginArtifactAdmissionResult warn(String code, String detail) {
        return new PluginArtifactAdmissionResult(true, true, code, detail);
    }
    public static PluginArtifactAdmissionResult reject(String code, String detail) {
        return new PluginArtifactAdmissionResult(false, false, code, detail);
    }
}
