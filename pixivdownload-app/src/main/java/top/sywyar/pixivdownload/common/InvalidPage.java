package top.sywyar.pixivdownload.common;

/** Identifies the rejected paging argument while preserving the caller's error code. */
public final class InvalidPage extends IllegalArgumentException {
    private final String field;

    private InvalidPage(String code, String field) {
        super(code);
        this.field = field;
    }

    public static void check(int offset, int limit, int maximum, String code) {
        if (offset < 0) throw new InvalidPage(code, "offset");
        if (limit < 1 || limit > maximum) throw new InvalidPage(code, "limit");
    }

    public String field() { return field; }
}
