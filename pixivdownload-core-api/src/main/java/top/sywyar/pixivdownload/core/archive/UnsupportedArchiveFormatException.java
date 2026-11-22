package top.sywyar.pixivdownload.core.archive;

public final class UnsupportedArchiveFormatException extends IllegalArgumentException {
    public static final String CODE = "validation.archive.export.format.unsupported";
    private final String format;

    public UnsupportedArchiveFormatException(String format) {
        super("Unsupported archive format: " + format);
        this.format = format;
    }

    public String format() {
        return format;
    }
}
