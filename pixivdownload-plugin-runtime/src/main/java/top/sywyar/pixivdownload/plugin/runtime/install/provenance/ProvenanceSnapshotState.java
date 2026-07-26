package top.sywyar.pixivdownload.plugin.runtime.install.provenance;

/** 管理清点中单个已安装 artifact 的 provenance 读取终态。 */
public enum ProvenanceSnapshotState {
    PRESENT,
    ABSENT,
    INVALID,
    BUDGET_EXHAUSTED
}
