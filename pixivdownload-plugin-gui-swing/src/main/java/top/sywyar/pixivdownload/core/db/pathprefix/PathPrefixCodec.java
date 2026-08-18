package top.sywyar.pixivdownload.core.db.pathprefix;

public final class PathPrefixCodec {
    private PathPrefixCodec() {}
    public static String stripTrailingSeparators(String value) {
        if (value == null) return null;
        String stripped = value.replaceAll("[/\\\\]+$", "");
        if (!stripped.isEmpty()) {
            if (stripped.matches("(?i)^[a-z]:$") && value.length() > stripped.length()) {
                return stripped + (value.indexOf('\\') >= 0 ? "\\" : "/");
            }
            return stripped;
        }
        return value.isEmpty() ? value : value.charAt(0) == '\\' ? "\\" : "/";
    }
}
