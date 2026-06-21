package top.sywyar.pixivdownload.setup.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SetupInitRequest {
    @NotBlank(message = "{validation.setup.username.required}")
    private String username;

    @NotBlank(message = "{validation.setup.password.required}")
    @Size(min = 6, message = "{validation.setup.password.size}")
    private String password;

    @NotBlank(message = "{validation.setup.mode.required}")
    @Pattern(regexp = "solo|multi", message = "{validation.setup.mode.pattern}")
    private String mode;

    /**
     * 代理配置（可选）。{@code proxyEnabled} 为 null 时表示本次安装未提交代理设置，
     * 服务端不改动 config.yaml 中的 proxy.* 默认值。host/port 仅在 enabled 为 true 时校验。
     */
    private Boolean proxyEnabled;
    private String proxyHost;
    private Integer proxyPort;
}
