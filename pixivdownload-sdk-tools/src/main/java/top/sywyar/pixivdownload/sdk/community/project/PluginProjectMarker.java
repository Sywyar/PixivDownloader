package top.sywyar.pixivdownload.sdk.community.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

/** 工程协议标识的有界读取；标识不证明发布者身份、包完整性或代码安全。 */
public final class PluginProjectMarker {
    public static final String FILE_NAME = ".pixivdownloader-plugin-project";
    private static final byte[] VERSION = "pixivdownloader-plugin-project-v1".getBytes(StandardCharsets.US_ASCII);

    private PluginProjectMarker() { }

    /**
     * 验证选中工程的普通标识文件；Git 跟踪状态由工程定位器核对。
     *
     * @param projectDirectory 已选择的工程目录
     * @throws IOException 文件缺失、链接或不是受支持的单行版本
     */
    public static void validate(Path projectDirectory) throws IOException {
        Path marker = projectDirectory.resolve(FILE_NAME);
        BasicFileAttributes attributes = Files.readAttributes(marker, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException("PROJECT_MARKER_INVALID");
        }
        // 最长合法输入只有版本、三个 BOM 字节和 CRLF；多读一个字节即可判定额外内容。
        try (var input = Files.newInputStream(marker, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(VERSION.length + 6);
            int start = bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb
                    && bytes[2] == (byte) 0xbf ? 3 : 0;
            int end = bytes.length;
            if (end > start && bytes[end - 1] == '\n') {
                end--;
                if (end > start && bytes[end - 1] == '\r') end--;
            }
            if (!Arrays.equals(VERSION, Arrays.copyOfRange(bytes, start, end))) {
                throw new IOException("PROJECT_MARKER_INVALID");
            }
        }
    }
}
