package top.sywyar.pixivdownload.ai;

import top.sywyar.pixivdownload.ai.model.AiChatMessage;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;

import java.util.List;

/**
 * 与 OpenAI 兼容的聊天客户端能力。传输、配置和生命周期均属于实现细节。
 */
public interface AiChatClient {
    /**
     * 固定本次执行使用的活动能力；返回的客户端在提供者撤回后拒绝调用，不转接替代者。
     * 不支持绑定或能力缺席时返回空值，持久任务应暂停并保留检查点。
     * @return 仅供本次执行使用的客户端，不得持久化或跨重启保存
     */
    default java.util.Optional<AiChatClient> bind() { return java.util.Optional.empty(); }

    /**
     * 当前结果相关配置的稳定身份，不包含凭据；不支持配置绑定的实现返回空值。
     * 调用方不能把该身份用作权限或凭据。
     * @return 可用于中断恢复校验的配置身份
     */
    default java.util.Optional<String> configurationFingerprint() {
        return java.util.Optional.empty();
    }

    /**
     * 校验配置身份并使用同一份配置快照发送；不支持时在网络调用前明确拒绝。
     * @param expectedFingerprint 创建任务时保存的配置身份
     * @param callType 调用类型
     * @param messages 消息列表
     * @param options 请求选项
     * @return 完整调用结果
     * @throws AiClientException 配置已改变、不可用或实现不支持
     */
    default AiChatResult chatBound(String expectedFingerprint, String callType,
                                  List<AiChatMessage> messages, AiChatOptions options) throws AiClientException {
        throw new AiClientException("AI_CONFIG_BINDING_UNSUPPORTED");
    }

    /**
     * 判断配置状态是否满足条件。
     *
     * @return 满足条件时返回 {@code true}，否则返回 {@code false}
     */
    boolean isConfigured();

    /**
     * 执行对应操作并返回结果。
     *
     * @param callType 调用类型
     * @param messages 消息列表
     * @param options 选项
     * @return 方法返回的 {@code AiChatResult} 实例
     * @throws AiClientException 执行失败时抛出
     */
    AiChatResult chat(String callType, List<AiChatMessage> messages,
                      AiChatOptions options) throws AiClientException;

    /**
     * 执行对应操作并返回结果。
     *
     * @param callType 调用类型
     * @param settings 设置
     * @param messages 消息列表
     * @param options 选项
     * @return 方法返回的 {@code AiChatResult} 实例
     * @throws AiClientException 执行失败时抛出
     */
    AiChatResult chatTest(String callType, AiClientSettings settings,
                          List<AiChatMessage> messages,
                          AiChatOptions options) throws AiClientException;
}
