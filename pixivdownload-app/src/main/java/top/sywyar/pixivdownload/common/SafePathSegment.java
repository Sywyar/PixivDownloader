package top.sywyar.pixivdownload.common;

import top.sywyar.pixivdownload.i18n.LocalizedException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class SafePathSegment {

    private SafePathSegment() {
    }

    public static String requireSafeDirectoryName(String value) {
        if (value == null) {
            return null;
        }
        String segment = value.trim();
        if (isUnsafe(segment)) {
            throw LocalizedException.badRequest(
                    "download.path.segment.invalid",
                    "Unsafe download subdirectory: {0}",
                    value
            );
        }
        return segment;
    }

    private static boolean isUnsafe(String segment) {
        if (segment.isBlank()
                || ".".equals(segment)
                || "..".equals(segment)
                || segment.contains("..")
                || segment.contains("/")
                || segment.contains("\\")
                || segment.contains(":")
                || segment.indexOf('\0') >= 0) {
            return true;
        }
        try {
            Path path = Paths.get(segment);
            return path.isAbsolute() || path.getNameCount() != 1;
        } catch (InvalidPathException e) {
            return true;
        }
    }
}
