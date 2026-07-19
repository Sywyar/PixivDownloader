package top.sywyar.pixivdownload.core.ai;

import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.ai.AiChatClient;
import top.sywyar.pixivdownload.ai.AiClientException;
import top.sywyar.pixivdownload.ai.AiClientSettings;
import top.sywyar.pixivdownload.ai.model.AiChatMessage;
import top.sywyar.pixivdownload.ai.model.AiChatOptions;
import top.sywyar.pixivdownload.ai.model.AiChatResult;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.ExternalCapabilityUnavailableException;

import java.util.List;

/**
 * Core facade for the optional AI plugin.
 */
@Service
public class AiService implements AiChatClient {

    private final AiChatClientRegistry registry;

    public AiService(AiChatClientRegistry registry) {
        this.registry = registry;
    }

    @Override
    public AiChatResult chat(String callType, List<AiChatMessage> messages,
                             AiChatOptions options) throws AiClientException {
        AiChatClient client = registry.active().orElseThrow(() ->
                new AiClientException("AI plugin unavailable"));
        try {
            return client.chat(callType, messages, options);
        } catch (ExternalCapabilityUnavailableException e) {
            throw new AiClientException("AI plugin unavailable");
        } catch (AiClientException e) {
            throw new AiClientException(e.getMessage(), e);
        }
    }

    @Override
    public AiChatResult chatTest(String callType, AiClientSettings settings,
                                 List<AiChatMessage> messages,
                                 AiChatOptions options) throws AiClientException {
        AiChatClient client = registry.active().orElseThrow(() ->
                new AiClientException("AI plugin unavailable"));
        try {
            return client.chatTest(callType, settings, messages, options);
        } catch (ExternalCapabilityUnavailableException e) {
            throw new AiClientException("AI plugin unavailable");
        } catch (AiClientException e) {
            throw new AiClientException(e.getMessage(), e);
        }
    }

    @Override
    public boolean isConfigured() {
        try {
            return registry.active().map(AiChatClient::isConfigured).orElse(false);
        } catch (ExternalCapabilityUnavailableException unavailable) {
            return false;
        }
    }

}
