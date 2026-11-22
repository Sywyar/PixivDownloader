package top.sywyar.pixivdownload.core.web;

/** A rejected HTTP input whose public location is known to its owner. */
public final class HttpParameterException extends RuntimeException {
    private final String in;
    private final String field;
    private final String reason;

    /**
     * @param in request location (query, path, header, part or body)
     * @param field wire name, or a JSON Pointer for a body field
     * @param reason stable validation reason
     */
    public HttpParameterException(String in, String field, String reason) {
        super("Invalid " + in + " field " + field + " (" + reason + ")");
        this.in = in;
        this.field = field;
        this.reason = reason;
    }

    /** {@return request location} */
    public String in() { return in; }

    /** {@return wire name or body JSON Pointer} */
    public String field() { return field; }

    /** {@return validation reason} */
    public String reason() { return reason; }
}
