package top.sywyar.pixivdownload.download.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MoveArtworkRequest {
    @NotBlank(message = "{validation.move.path.required}")
    private String movePath;

    /** Move timestamp, in epoch milliseconds. */
    @NotNull(message = "{validation.move.time.required}")
    private Long moveTime;

    /**
     * 分类工具中用户预先配置的目标根目录（即 image_classifier.properties 的
     * {@code target.folder.N} 原值）。可选；为空时服务端按 movePath 本身自动注册前缀。
     * 提供本字段后，{@code path_prefixes} 写入的就是用户配置的根目录，
     * 后续同根的子目录都会被编码为 {@code {N}/<seq>} 而不是各自再注册一行。
     */
    private String classifierTargetFolder;
}
