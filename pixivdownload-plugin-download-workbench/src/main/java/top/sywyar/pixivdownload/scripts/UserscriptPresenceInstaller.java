package top.sywyar.pixivdownload.scripts;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只为显式预留入口的脚本加入当前安装站点的只读检测响应。 */
final class UserscriptPresenceInstaller {
    private static final String MARKER = "// @pixiv-presence-bootstrap";
    private static final Pattern HEADER_END = Pattern.compile("(?m)^//\\s*==/UserScript==[^\\r\\n]*");
    private static final String BOOTSTRAP = readBootstrap();

    private UserscriptPresenceInstaller() {
    }

    static String apply(String content, String id, String installUrl) {
        int marker = content.indexOf(MARKER);
        Matcher headerEnd = HEADER_END.matcher(content);
        if (marker < 0 || !headerEnd.find() || marker < headerEnd.end()) {
            return content;
        }
        URI uri = URI.create(installUrl);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || ("http".equals(uri.getScheme()) && port == 80)
                || ("https".equals(uri.getScheme()) && port == 443);
        String origin = uri.getScheme() + "://" + uri.getHost().toLowerCase(Locale.ROOT)
                + (defaultPort ? "" : ":" + port);
        String context = uri.getPath().substring(0, uri.getPath().lastIndexOf("/api/scripts/"));
        String page = context + "/pixiv-batch";
        String include = (origin + page).replaceAll("([\\\\/.*+?^${}()|\\[\\]])", "\\\\$1")
                + "(?:-alt)?\\.html(?:[?#].*)?$";
        String config = "{\"origin\":" + quote(origin) + ",\"page\":" + quote(page)
                + ",\"id\":" + quote(id) + "}";
        String body = content.substring(0, marker) + "if ((" + BOOTSTRAP + ")(" + config + ")) return;"
                + content.substring(marker + MARKER.length());
        return body.substring(0, headerEnd.start()) + "// @include /^" + include + "/\n"
                + body.substring(headerEnd.start());
    }

    private static String quote(String value) {
        return '"' + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + '"';
    }

    private static String readBootstrap() {
        try (InputStream input = UserscriptPresenceInstaller.class.getResourceAsStream("/userscript-presence-bootstrap.js")) {
            if (input == null) {
                throw new IllegalStateException("Missing userscript presence bootstrap");
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
