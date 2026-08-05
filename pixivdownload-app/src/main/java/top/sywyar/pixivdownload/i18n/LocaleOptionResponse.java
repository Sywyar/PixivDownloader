package top.sywyar.pixivdownload.i18n;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class LocaleOptionResponse {

    private final String tag;
    private final List<String> aliases;
    private final String label;
    private final String nativeName;
    private final String direction;
    private final String status;
}
