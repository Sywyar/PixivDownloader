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
public class AiService {

    private final AiChatClientRegistry registry;

    public AiService(AiChatClientRegistry registry) {
        this.registry = registry;
    }

    public AiChatResult chat(String callType, List<AiChatMessage> messages,
                             AiChatOptions options) throws AiException {
        AiChatClient client = registry.active().orElseThrow(() ->
                new AiException("AI plugin unavailable"));
        try {
            return client.chat(callType, messages, options);
        } catch (ExternalCapabilityUnavailableException e) {
            throw new AiException("AI plugin unavailable");
        } catch (AiClientException e) {
            throw new AiException(e.getMessage(), e);
        }
    }

    public AiChatResult chatTest(String callType, AiClientSettings settings,
                                 List<AiChatMessage> messages,
                                 AiChatOptions options) throws AiException {
        AiChatClient client = registry.active().orElseThrow(() ->
                new AiException("AI plugin unavailable"));
        try {
            return client.chatTest(callType, settings, messages, options);
        } catch (ExternalCapabilityUnavailableException e) {
            throw new AiException("AI plugin unavailable");
        } catch (AiClientException e) {
            throw new AiException(e.getMessage(), e);
        }
    }

    public boolean isConfigured() {
        try {
            return registry.active().map(AiChatClient::isConfigured).orElse(false);
        } catch (ExternalCapabilityUnavailableException unavailable) {
            return false;
        }
    }

    public static class AiException extends Exception {
        public AiException(String message) {
            super(message);
        }

        public AiException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
