package top.sywyar.pixivdownload.ai;

import top.sywyar.pixivdownload.ai.model.AiChatMessage;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;

import java.util.List;

/**
 * OpenAI-compatible chat capability contributed by the optional AI plugin.
 */
public interface AiChatClient {

    boolean isConfigured();

    AiChatResult chat(String callType, List<AiChatMessage> messages,
                      AiChatOptions options) throws AiClientException;

    AiChatResult chatTest(String callType, AiClientSettings settings,
                          List<AiChatMessage> messages,
                          AiChatOptions options) throws AiClientException;
}
