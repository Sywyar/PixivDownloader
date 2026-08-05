import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Minimal oracle for differential testing: reproduces exactly how this
 * repository's runtime loads a UTF-8 properties resource
 * (InputStreamReader(UTF_8) + java.util.Properties.load(Reader))
 * and prints the resulting key/value pairs as stable JSON.
 *
 * Usage: java PropertiesOracle <file>
 *
 * Output (stdout, UTF-8):
 *   {"entries": [["key","value"], ...], "error": null}
 * On a load failure (e.g. malformed \\uXXXX escape) it prints
 *   {"entries": [], "error": "<message>"}
 * and exits 0, so callers can assert on the error too.
 */
public final class PropertiesOracle {

    public static void main(String[] args) throws IOException {
        System.setOut(new java.io.PrintStream(
                new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true,
                StandardCharsets.UTF_8));
        Path file = Path.of(args[0]);
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            properties.load(reader);
            StringBuilder out = new StringBuilder("{\"entries\": [");
            boolean first = true;
            for (String key : properties.stringPropertyNames()) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                out.append('[').append(json(key)).append(", ").append(json(properties.getProperty(key))).append(']');
            }
            out.append("], \"error\": null}");
            System.out.println(out);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getName() : e.getMessage();
            System.out.println("{\"entries\": [], \"error\": " + json(message) + "}");
        }
    }

    private static String json(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
