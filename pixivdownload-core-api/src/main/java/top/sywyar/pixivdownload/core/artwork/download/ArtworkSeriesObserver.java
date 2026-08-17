package top.sywyar.pixivdownload.core.artwork.download;

/**
 * 插画系列事实的核心观察端口。
 */
public interface ArtworkSeriesObserver {

    /**
     * @param credential 补齐系列或封面所需的不透明凭证；不得持久化或写入诊断，可为 {@code null}
     * @param observation 观察结果
     */
    void observe(ArtworkSeriesObservation observation, String credential);
}
