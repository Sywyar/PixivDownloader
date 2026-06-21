package top.sywyar.pixivdownload.setup.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SetupStatusResponse {
    private final boolean setupComplete;
    private final String mode;
    private final int multiModeLimitPage;
}
