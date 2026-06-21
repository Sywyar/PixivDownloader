package top.sywyar.pixivdownload.setup.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthCheckResponse {
    private final boolean valid;
}
