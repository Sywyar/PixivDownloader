package top.sywyar.pixivdownload.setup.guest;

import java.util.Set;

/**
 * Snapshot of an invite guest session attached to the current request.
 */
public record GuestInviteSession(
        long id,
        String code,
        boolean allowSfw,
        boolean allowR18,
        boolean allowR18g,
        boolean tagUnrestricted,
        Set<Long> tagIds,
        boolean authorUnrestricted,
        Set<Long> authorIds,
        boolean novelTagUnrestricted,
        Set<Long> novelTagIds,
        boolean novelAuthorUnrestricted,
        Set<Long> novelAuthorIds) {

    public static final String REQUEST_ATTR = "guestInvite";

    public boolean hasAnyAgeRating() {
        return allowSfw || allowR18 || allowR18g;
    }
}
