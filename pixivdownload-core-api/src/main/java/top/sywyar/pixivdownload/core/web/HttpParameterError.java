package top.sywyar.pixivdownload.core.web;

/**
 * A client-correctable error for a named HTTP input.
 * @param code machine-readable error code
 * @param error localized error message
 * @param in request location (query, path, header, part or body)
 * @param field wire name, or a JSON Pointer for a body field
 * @param reason machine-readable validation reason
 */
public record HttpParameterError(String code, String error, String in, String field, String reason) {}
