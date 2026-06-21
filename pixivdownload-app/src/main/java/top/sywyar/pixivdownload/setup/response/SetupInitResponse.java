package top.sywyar.pixivdownload.setup.response;

import lombok.Getter;

@Getter
public class SetupInitResponse {
    private final boolean ok;
    private final String mode;
    private final String warning;

    public SetupInitResponse(boolean ok, String mode) {
        this(ok, mode, null);
    }

    public SetupInitResponse(boolean ok, String mode, String warning) {
        this.ok = ok;
        this.mode = mode;
        this.warning = warning;
    }
}
