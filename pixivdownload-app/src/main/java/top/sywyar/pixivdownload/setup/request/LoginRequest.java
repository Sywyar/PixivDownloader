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
    @lombok.ToString.Exclude
    private String password;
    private boolean rememberMe;
    @Size(max = 128)
    @lombok.ToString.Exclude
    private String verificationToken;
    @Size(max = 16)
    @lombok.ToString.Exclude
    private java.util.Map<String, String> verificationProofs = java.util.Map.of();
}
