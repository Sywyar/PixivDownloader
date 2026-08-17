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
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** Returns a survey-scoped anonymous hash and deterministic submission UUID, never the raw installation identity. */
@PluginManagedBean
@RestController
@RequestMapping("/api/multi-mode-decision-survey/identity")
public class MultiModeDecisionSurveyIdentityController {

    private static final String NAMESPACE = "pixivdownload:multi-mode-decision-survey:v1";
    static final String CAMPAIGN_VERSION = "multi-mode-decision-v1";
    private static final Pattern SURVEY_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final InstallIdentityProvider installIdentityProvider;

    public MultiModeDecisionSurveyIdentityController(InstallIdentityProvider installIdentityProvider) {
        this.installIdentityProvider = installIdentityProvider;
    }

    @GetMapping
    public ResponseEntity<?> identity(
            @RequestParam(value = "surveyId", required = false) String surveyId) {
        if (surveyId == null || !SURVEY_ID_PATTERN.matcher(surveyId).matches()) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(ApiErrorResponse.of("survey.identity.invalid-request", "Invalid survey id"));
        }
        try {
            String scopedIdentity = deriveScopedIdentity(surveyId, installIdentityProvider.get());
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new IdentityResponse(scopedIdentity,
                            deriveSubmissionId(surveyId, CAMPAIGN_VERSION, scopedIdentity)));
        } catch (RuntimeException ignored) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(ApiErrorResponse.of("survey.identity.unavailable", "Survey identity is unavailable"));
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

    static String deriveSubmissionId(String surveyId, String campaignVersion, String scopedIdentity) {
        if (surveyId == null || !SURVEY_ID_PATTERN.matcher(surveyId).matches()
                || campaignVersion == null || !campaignVersion.matches("[a-z0-9][a-z0-9._-]{0,63}")
                || scopedIdentity == null || !scopedIdentity.matches("pmds_[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid survey submission scope");
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(
                    (NAMESPACE + ":submission\0" + surveyId + '\0' + campaignVersion + '\0'
                            + scopedIdentity).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        digest[6] = (byte) ((digest[6] & 0x0f) | 0x80);
        digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
        ByteBuffer bytes = ByteBuffer.wrap(digest);
        return new UUID(bytes.getLong(), bytes.getLong()).toString();
    }

    public record IdentityResponse(String distinctId, String submissionId) {
    }
}
