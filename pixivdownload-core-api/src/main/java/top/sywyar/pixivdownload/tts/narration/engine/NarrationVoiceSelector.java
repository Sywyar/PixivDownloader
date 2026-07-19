package top.sywyar.pixivdownload.tts.narration.engine;

import java.util.Optional;

/**
 * 解析宿主当前朗读引擎选择的稳定只读端口。
 *
 * <p>配置绑定、能力 registry、发布代际和代理撤回均由宿主实现；小说等消费者只读取当前选择快照。
 */
public interface NarrationVoiceSelector {

    /** 当前配置中请求的引擎 id；未配置时返回空字符串。 */
    String configuredEngineId();

    /** 当前配置实际命中的活动引擎；能力缺失或已撤回时返回空。 */
    Optional<NarrationVoiceSelection> selected();

    /** 当前活动引擎数量，仅用于缺能力诊断。 */
    int availableEngineCount();
}
