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
