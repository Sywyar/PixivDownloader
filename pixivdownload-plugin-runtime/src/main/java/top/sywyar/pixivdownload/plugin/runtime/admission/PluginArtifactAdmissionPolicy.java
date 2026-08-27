package top.sywyar.pixivdownload.plugin.runtime.admission;

/** 宿主提供的纯数据加载准入门；运行时不依赖宿主状态实现。 */
@FunctionalInterface
public interface PluginArtifactAdmissionPolicy {
    PluginArtifactAdmissionResult evaluate(PluginArtifactAdmissionRequest request);

    static PluginArtifactAdmissionPolicy allowAll() {
        return ignored -> PluginArtifactAdmissionResult.allow();
    }
}
