package top.sywyar.pixivdownload.multimodesurvey;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Returns only a survey-scoped anonymous hash, never the raw installation identity. */
@PluginManagedBean
@RestController
@RequestMapping("/api/multi-mode-decision-survey/identity")
public class MultiModeDecisionSurveyIdentityController {

    static final String SURVEY_ID = "019ff791-9fcf-0000-2a64-0be9f0b64dbf";
    private static final String NAMESPACE = "pixivdownload:multi-mode-decision-survey:v1";

    private final InstallIdentityProvider installIdentityProvider;

    public MultiModeDecisionSurveyIdentityController(InstallIdentityProvider installIdentityProvider) {
        this.installIdentityProvider = installIdentityProvider;
    }

    @GetMapping
    public ResponseEntity<IdentityResponse> identity() {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new IdentityResponse(deriveScopedIdentity(installIdentityProvider.get())));
        } catch (RuntimeException ignored) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
    }

    static String deriveScopedIdentity(String installIdentity) {
        UUID uuid = UUID.fromString(installIdentity);
        if (uuid.version() != 4 || uuid.variant() != 2) {
            throw new IllegalArgumentException("install identity is not a UUID v4");
        }
        String input = NAMESPACE + '\0' + SURVEY_ID + '\0' + uuid;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return "pmds_" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public record IdentityResponse(String distinctId) {
    }
}
