package top.sywyar.pixivdownload.core.web;

/**
 * Resolves the neutral browser acquisition credential header while retaining a source-specific
 * legacy header during migration.
 */
public final class AcquisitionCredentialResolver {

    public static final String HEADER_NAME = "X-Acquisition-Credential";

    private AcquisitionCredentialResolver() {
    }

    public static String resolve(String acquisitionCredential, String legacyCredential) {
        String generic = normalize(acquisitionCredential);
        String legacy = normalize(legacyCredential);
        if (generic != null && legacy != null && !generic.equals(legacy)) {
            throw new IllegalArgumentException("Conflicting acquisition credential headers");
        }
        return generic != null ? generic : legacy;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
