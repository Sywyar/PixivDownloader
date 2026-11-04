package top.sywyar.pixivdownload.captcha;

import cloud.tianai.captcha.validator.impl.SimpleImageCaptchaValidator;
import java.util.List;
import org.json.JSONObject;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerification;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerificationRequest;
import top.sywyar.pixivdownload.plugin.api.security.OperationVerificationResult;

final class ImageVerification implements OperationVerification {
    private final ImageChallengeStore store;
    private final boolean slider;
    private final CaptchaDiagnostics diagnostics;
    private final SimpleImageCaptchaValidator validator = new SimpleImageCaptchaValidator();

    ImageVerification(ImageChallengeStore store, boolean slider, CaptchaDiagnostics diagnostics) {
        this.store = store; this.slider = slider; this.diagnostics = diagnostics;
    }
    public String policyId() { return slider ? "captcha-slider" : "captcha-text"; }
    public Policy policy() { return new Policy(slider ? "slider-position6px-expiry120-v1" : "text5-expiry120-v1", "", INTERACTION, List.of()); }
    public String generation() { return store.generation(); }

    public OperationVerificationResult verify(OperationVerificationRequest request) {
        if (!request.response()) {
            try {
                var created = store.create(policyId(), request.attemptId(), slider ? CaptchaImages::slider : CaptchaImages::text);
                var images = created.images();
                var challenge = new JSONObject().put("id", created.id()).put("expiresAt", created.expiresAt())
                        .put("image", "/api/captcha/images/" + created.id() + "/0")
                        .put("width", images.width()).put("height", images.height());
                if (slider) challenge.put("piece", "/api/captcha/images/" + created.id() + "/1").put("pieceWidth", images.pieceWidth());
                else challenge.put("length", CaptchaImages.TEXT_LENGTH);
                return new OperationVerificationResult(OperationVerificationResult.Status.CHALLENGE, challenge.toString());
            } catch (RuntimeException failure) {
                String reason = switch (String.valueOf(failure.getMessage())) {
                    case "CAPTCHA_IMAGE_BUSY", "CAPTCHA_IMAGE_CAPACITY", "CAPTCHA_IMAGE_CLOSED" -> failure.getMessage();
                    default -> "CAPTCHA_IMAGE_UNAVAILABLE";
                };
                throw diagnostics.unavailable(policyId(), reason, "image-challenge");
            }
        }
        boolean approved = false;
        try {
            if (request.proof().length() > 256) return AltchaVerification.rejected();
            var proof = new JSONObject(request.proof());
            String id = proof.getString("id");
            if (id.length() != 36) return AltchaVerification.rejected();
            var images = store.consume(id, policyId(), request.attemptId());
            if (images != null) {
                if (slider) {
                    double position = proof.getDouble("position");
                    approved = Double.isFinite(position) && position >= 0 && position <= images.width() - images.pieceWidth()
                            && validator.checkPercentage((float) position / images.width(),
                            Float.parseFloat(images.answer()) / images.width(), 6f / images.width());
                } else {
                    String answer = proof.getString("answer");
                    approved = answer.length() == CaptchaImages.TEXT_LENGTH && images.answer().equalsIgnoreCase(answer);
                }
            }
        } catch (IllegalArgumentException invalid) {
            approved = false;
        } catch (org.json.JSONException invalid) {
            approved = false;
        }
        if (!approved) diagnostics.rejected(policyId(), "IMAGE_ANSWER_REJECTED");
        return new OperationVerificationResult(approved ? OperationVerificationResult.Status.APPROVED
                : OperationVerificationResult.Status.REJECTED, "");
    }
}
