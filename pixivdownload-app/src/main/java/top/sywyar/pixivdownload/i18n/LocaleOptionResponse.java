package top.sywyar.pixivdownload.i18n;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LocaleOptionResponse {

    private final String tag;
    private final String label;
    private final String nativeName;
    private final String direction;
    private final String status;
}
