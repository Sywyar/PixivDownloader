package top.sywyar.pixivdownload.setup;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** 只从显式受信代理读取转发头，并在其它安全过滤器之前规范化请求来源。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class TrustedForwardedRequestFilter extends OncePerRequestFilter {

    private static final List<String> FORWARDED_HEADERS = List.of(
            "Forwarded",
            "X-Forwarded-For",
            "X-Forwarded-Proto",
            "X-Forwarded-Host",
            "X-Forwarded-Port",
            "X-Real-IP");

    private final List<CidrBlock> trustedProxies;

    public TrustedForwardedRequestFilter(
            @Value("${server.trusted-proxy-cidrs:}") String trustedProxyCidrs) {
        this.trustedProxies = parseTrustedProxies(trustedProxyCidrs);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        boolean hasForwardingHeaders = hasForwardingHeaders(request);
        boolean trustedPeer = isTrustedProxy(request.getRemoteAddr());

        if (!trustedPeer) {
            if (hasForwardingHeaders) {
                reject(response);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        if (!hasForwardingHeaders) {
            reject(response);
            return;
        }

        Optional<ForwardedRequest> forwarded = resolveForwardedRequest(request);
        if (forwarded.isEmpty()) {
            reject(response);
            return;
        }
        filterChain.doFilter(new NormalizedRequest(request, forwarded.get()), response);
    }

    private Optional<ForwardedRequest> resolveForwardedRequest(HttpServletRequest request) {
        try {
            String forwarded = combinedHeader(request, "Forwarded");
            boolean hasStandard = hasText(forwarded);
            boolean hasLegacy = FORWARDED_HEADERS.stream()
                    .filter(name -> !"Forwarded".equalsIgnoreCase(name))
                    .map(name -> combinedHeader(request, name))
                    .anyMatch(TrustedForwardedRequestFilter::hasText);
            if (hasStandard == hasLegacy) {
                return Optional.empty();
            }
            return hasStandard
                    ? resolveStandardForwarded(forwarded)
                    : resolveXForwarded(request);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Optional<ForwardedRequest> resolveStandardForwarded(String header) {
        List<String> rawElements = splitOutsideQuotes(header, ',');
        List<ForwardedElement> elements = new ArrayList<>(rawElements.size());
        List<String> forwardedFor = new ArrayList<>(rawElements.size());
        for (String rawElement : rawElements) {
            Map<String, String> parameters = parseParameters(rawElement);
            String address = parameters.get("for");
            if (!hasText(address)) {
                return Optional.empty();
            }
            elements.add(new ForwardedElement(address, parameters.get("proto"), parameters.get("host")));
            forwardedFor.add(address);
        }

        AddressSelection client = selectClient(forwardedFor);
        ForwardedElement boundary = elements.get(client.index());
        Origin origin = parseOrigin(boundary.proto(), boundary.host(), null);
        return Optional.of(new ForwardedRequest(client.address(), origin));
    }

    private Optional<ForwardedRequest> resolveXForwarded(HttpServletRequest request) {
        String forwardedFor = combinedHeader(request, "X-Forwarded-For");
        String realIp = combinedHeader(request, "X-Real-IP");
        List<String> addresses;
        if (hasText(forwardedFor)) {
            addresses = splitOutsideQuotes(forwardedFor, ',');
        } else if (hasText(realIp)) {
            addresses = List.of(realIp);
        } else {
            return Optional.empty();
        }

        AddressSelection client = selectClient(addresses);
        String proto = alignedValue(combinedHeader(request, "X-Forwarded-Proto"), client.index(), addresses.size());
        String host = alignedValue(combinedHeader(request, "X-Forwarded-Host"), client.index(), addresses.size());
        String portHeader = combinedHeader(request, "X-Forwarded-Port");
        String port = hasText(portHeader)
                ? alignedValue(portHeader, client.index(), addresses.size())
                : null;
        Origin origin = parseOrigin(proto, host, port);
        return Optional.of(new ForwardedRequest(client.address(), origin));
    }

    private AddressSelection selectClient(List<String> forwardedFor) {
        if (forwardedFor.isEmpty()) {
            throw new IllegalArgumentException("Missing forwarded client address");
        }
        for (int i = forwardedFor.size() - 1; i >= 0; i--) {
            String address = canonicalAddress(forwardedFor.get(i));
            if (!isTrustedProxy(address)) {
                return new AddressSelection(i, address);
            }
        }
        throw new IllegalArgumentException("Forwarded chain has no client address");
    }

    private boolean isTrustedProxy(String address) {
        byte[] parsed = parseNumericAddress(normalizeForwardedAddress(address));
        if (parsed == null) {
            return false;
        }
        return trustedProxies.stream().anyMatch(cidr -> cidr.contains(parsed));
    }

    private static Origin parseOrigin(String rawScheme, String rawHost, String rawPort) {
        String scheme = unquote(rawScheme).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Unsupported forwarded scheme");
        }
        String authority = unquote(rawHost);
        if (!hasText(authority) || authority.indexOf('/') >= 0 || authority.indexOf('@') >= 0
                || authority.indexOf('?') >= 0 || authority.indexOf('#') >= 0) {
            throw new IllegalArgumentException("Invalid forwarded host");
        }

        URI uri;
        try {
            uri = URI.create(scheme + "://" + authority);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid forwarded host", e);
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || !uri.getPath().isEmpty()
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Invalid forwarded host");
        }

        int authorityPort = uri.getPort();
        int explicitPort = hasText(rawPort) ? parsePort(unquote(rawPort)) : -1;
        if (authorityPort >= 0 && explicitPort >= 0 && authorityPort != explicitPort) {
            throw new IllegalArgumentException("Conflicting forwarded ports");
        }
        int port = authorityPort >= 0
                ? authorityPort
                : explicitPort >= 0 ? explicitPort : defaultPort(scheme);
        String host = normalizeHost(uri.getHost());
        return new Origin(scheme, host, port);
    }

    private static String alignedValue(String header, int index, int addressCount) {
        if (!hasText(header)) {
            throw new IllegalArgumentException("Missing forwarded origin value");
        }
        List<String> values = splitOutsideQuotes(header, ',');
        if (values.size() == 1) {
            return values.get(0);
        }
        if (values.size() != addressCount) {
            throw new IllegalArgumentException("Misaligned forwarded origin values");
        }
        return values.get(index);
    }

    private static Map<String, String> parseParameters(String element) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String rawPair : splitOutsideQuotes(element, ';')) {
            int equals = rawPair.indexOf('=');
            if (equals <= 0 || equals == rawPair.length() - 1) {
                throw new IllegalArgumentException("Invalid Forwarded parameter");
            }
            String name = rawPair.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = unquote(rawPair.substring(equals + 1));
            if (parameters.putIfAbsent(name, value) != null) {
                throw new IllegalArgumentException("Duplicate Forwarded parameter");
            }
        }
        return parameters;
    }

    private static List<String> splitOutsideQuotes(String value, char delimiter) {
        if (!hasText(value)) {
            throw new IllegalArgumentException("Missing forwarded value");
        }
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (quoted && ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && ch == delimiter) {
                parts.add(nonEmpty(value.substring(start, i)));
                start = i + 1;
            }
        }
        if (quoted || escaped) {
            throw new IllegalArgumentException("Invalid quoted forwarded value");
        }
        parts.add(nonEmpty(value.substring(start)));
        return List.copyOf(parts);
    }

    private static String unquote(String value) {
        String trimmed = nonEmpty(value);
        if (!trimmed.startsWith("\"") && !trimmed.endsWith("\"")) {
            if (trimmed.indexOf('"') >= 0 || trimmed.indexOf('\\') >= 0) {
                throw new IllegalArgumentException("Invalid forwarded token");
            }
            return trimmed;
        }
        if (trimmed.length() < 2 || !trimmed.startsWith("\"") || !trimmed.endsWith("\"")) {
            throw new IllegalArgumentException("Invalid quoted forwarded token");
        }
        StringBuilder result = new StringBuilder(trimmed.length() - 2);
        boolean escaped = false;
        for (int i = 1; i < trimmed.length() - 1; i++) {
            char ch = trimmed.charAt(i);
            if (escaped) {
                result.append(ch);
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == '"') {
                throw new IllegalArgumentException("Invalid quoted forwarded token");
            } else {
                result.append(ch);
            }
        }
        if (escaped) {
            throw new IllegalArgumentException("Invalid quoted forwarded token");
        }
        return nonEmpty(result.toString());
    }

    private static String canonicalAddress(String value) {
        byte[] address = parseNumericAddress(normalizeForwardedAddress(value));
        if (address == null) {
            throw new IllegalArgumentException("Invalid forwarded client address");
        }
        try {
            return InetAddress.getByAddress(address).getHostAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid forwarded client address", e);
        }
    }

    private static String normalizeForwardedAddress(String value) {
        String address = unquote(value);
        if (address.startsWith("[") && address.contains("]")) {
            int closing = address.indexOf(']');
            String suffix = address.substring(closing + 1);
            if (!suffix.isEmpty()) {
                if (!suffix.startsWith(":")) {
                    throw new IllegalArgumentException("Invalid forwarded client port");
                }
                parsePort(suffix.substring(1));
            }
            return address.substring(1, closing);
        }
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(':') == colon) {
            String port = address.substring(colon + 1);
            if (!port.isEmpty() && port.chars().allMatch(Character::isDigit)) {
                parsePort(port);
                return address.substring(0, colon);
            }
        }
        return address;
    }

    private static byte[] parseNumericAddress(String value) {
        if (!hasText(value) || value.indexOf('%') >= 0) {
            return null;
        }
        if (value.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}")) {
            String[] parts = value.split("\\.");
            byte[] bytes = new byte[4];
            for (int i = 0; i < parts.length; i++) {
                int part = Integer.parseInt(parts[i]);
                if (part > 255) {
                    return null;
                }
                bytes[i] = (byte) part;
            }
            return bytes;
        }
        if (value.indexOf(':') < 0 || !value.matches("[0-9A-Fa-f:.]+")) {
            return null;
        }
        try {
            return InetAddress.getByName(value).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static List<CidrBlock> parseTrustedProxies(String configured) {
        if (!hasText(configured)) {
            return List.of();
        }
        String[] tokens = configured.split(",", -1);
        List<CidrBlock> result = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            try {
                result.add(CidrBlock.parse(nonEmpty(token)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid trusted proxy CIDR: " + token.trim(), e);
            }
        }
        return List.copyOf(result);
    }

    private static boolean hasForwardingHeaders(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        return names != null
                && Collections.list(names).stream().anyMatch(TrustedForwardedRequestFilter::isForwardedHeader);
    }

    private static boolean isForwardedHeader(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return "forwarded".equals(normalized)
                || "x-real-ip".equals(normalized)
                || normalized.startsWith("x-forwarded-");
    }

    private static String combinedHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        List<String> collected = Collections.list(values);
        return String.join(",", collected);
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(nonEmpty(value));
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Invalid forwarded port");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid forwarded port", e);
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }

    private static String normalizeHost(String host) {
        try {
            return host.indexOf(':') >= 0
                    ? host.toLowerCase(Locale.ROOT)
                    : IDN.toASCII(host).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid forwarded host", e);
        }
    }

    private static String authority(Origin origin) {
        String host = origin.host().indexOf(':') >= 0 ? "[" + origin.host() + "]" : origin.host();
        return origin.port() == defaultPort(origin.scheme()) ? host : host + ":" + origin.port();
    }

    private static String nonEmpty(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty forwarded value");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static void reject(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentLength(0);
    }

    private record AddressSelection(int index, String address) {
    }

    private record ForwardedElement(String address, String proto, String host) {
    }

    private record ForwardedRequest(String clientAddress, Origin origin) {
    }

    private record Origin(String scheme, String host, int port) {
    }

    private record CidrBlock(byte[] network, int prefixLength) {

        static CidrBlock parse(String value) {
            int slash = value.indexOf('/');
            if (slash <= 0 || slash != value.lastIndexOf('/')) {
                throw new IllegalArgumentException("CIDR prefix is required");
            }
            byte[] address = parseNumericAddress(value.substring(0, slash).trim());
            if (address == null) {
                throw new IllegalArgumentException("Invalid CIDR address");
            }
            int prefix;
            try {
                prefix = Integer.parseInt(value.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid CIDR prefix", e);
            }
            int bits = address.length * 8;
            if (prefix < 0 || prefix > bits) {
                throw new IllegalArgumentException("Invalid CIDR prefix");
            }
            byte[] network = address.clone();
            for (int bit = prefix; bit < bits; bit++) {
                network[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
            }
            return new CidrBlock(network, prefix);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            for (int bit = 0; bit < prefixLength; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((address[bit / 8] & mask) != (network[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class NormalizedRequest extends HttpServletRequestWrapper {

        private final ForwardedRequest forwarded;

        private NormalizedRequest(HttpServletRequest request, ForwardedRequest forwarded) {
            super(request);
            this.forwarded = forwarded;
        }

        @Override
        public String getRemoteAddr() {
            return forwarded.clientAddress();
        }

        @Override
        public String getRemoteHost() {
            return forwarded.clientAddress();
        }

        @Override
        public String getScheme() {
            return forwarded.origin().scheme();
        }

        @Override
        public String getServerName() {
            return forwarded.origin().host();
        }

        @Override
        public int getServerPort() {
            return forwarded.origin().port();
        }

        @Override
        public boolean isSecure() {
            return "https".equals(forwarded.origin().scheme());
        }

        @Override
        public String getHeader(String name) {
            if (HttpHeaders.HOST.equalsIgnoreCase(name)) {
                return authority(forwarded.origin());
            }
            if (TrustedForwardedRequestFilter.isForwardedHeader(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if (HttpHeaders.HOST.equalsIgnoreCase(name)) {
                return Collections.enumeration(List.of(authority(forwarded.origin())));
            }
            if (TrustedForwardedRequestFilter.isForwardedHeader(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames()).stream()
                    .filter(name -> !TrustedForwardedRequestFilter.isForwardedHeader(name))
                    .toList();
            return Collections.enumeration(names);
        }

        @Override
        public StringBuffer getRequestURL() {
            return new StringBuffer(forwarded.origin().scheme())
                    .append("://")
                    .append(authority(forwarded.origin()))
                    .append(getRequestURI());
        }

    }
}
