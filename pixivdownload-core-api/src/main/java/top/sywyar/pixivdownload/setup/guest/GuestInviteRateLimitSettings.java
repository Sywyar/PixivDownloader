package top.sywyar.pixivdownload.setup.guest;

/**
 * Read-only invite guest rate-limit settings used by optional TTS endpoints.
 */
public interface GuestInviteRateLimitSettings {

    int getTtsRequestLimitMinute();
}
