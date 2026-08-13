package top.sywyar.pixivdownload.setup.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank(message = "{validation.login.username.required}")
    @Size(max = 128, message = "{validation.login.username.size}")
    private String username;
    @NotBlank(message = "{validation.login.password.required}")
    @Size(max = 1024, message = "{validation.login.password.size}")
    private String password;
    private boolean rememberMe;
}
