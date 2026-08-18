package top.sywyar.pixivdownload.common;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public final class Utf8ConsoleStreams {
    private Utf8ConsoleStreams() {}
    public static void install() {
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));
    }
}
