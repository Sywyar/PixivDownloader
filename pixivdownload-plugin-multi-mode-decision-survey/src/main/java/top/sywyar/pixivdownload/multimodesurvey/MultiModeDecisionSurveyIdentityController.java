package top.sywyar.pixivdownload.multimodesurvey;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** Returns only a survey-scoped anonymous hash, never the raw installation identity. */
@PluginManagedBean
@RestController
@RequestMapping("/api/multi-mode-decision-survey/identity")
public class MultiModeDecisionSurveyIdentityController {

    private static final String NAMESPACE = "pixivdownload:multi-mode-decision-survey:v1";
    private static final Pattern SURVEY_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final InstallIdentityProvider installIdentityProvider;

    public MultiModeDecisionSurveyIdentityController(InstallIdentityProvider installIdentityProvider) {
        this.installIdentityProvider = installIdentityProvider;
    }

    @GetMapping
    public ResponseEntity<IdentityResponse> identity(
            @RequestParam(value = "surveyId", required = false) String surveyId) {
        if (surveyId == null || !SURVEY_ID_PATTERN.matcher(surveyId).matches()) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new IdentityResponse(deriveScopedIdentity(surveyId, installIdentityProvider.get())));
        } catch (RuntimeException ignored) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
    }

    static String deriveScopedIdentity(String surveyId, String installIdentity) {
        if (surveyId == null || !SURVEY_ID_PATTERN.matcher(surveyId).matches()) {
            throw new IllegalArgumentException("invalid survey id");
        }
        UUID uuid = UUID.fromString(installIdentity);
        if (uuid.version() != 4 || uuid.variant() != 2) {
            throw new IllegalArgumentException("install identity is not a UUID v4");
        }
        String input = NAMESPACE + '\0' + surveyId + '\0' + uuid;
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
