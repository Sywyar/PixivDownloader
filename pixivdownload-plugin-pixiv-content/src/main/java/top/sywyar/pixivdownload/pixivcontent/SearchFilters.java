package top.sywyar.pixivdownload.pixivcontent;

import org.springframework.web.util.UriComponentsBuilder;
import top.sywyar.pixivdownload.core.web.HttpParameterException;

public record SearchFilters(Integer minBookmarks, Integer maxBookmarks, Integer minWidth, Integer maxWidth,
                     Integer minHeight, Integer maxHeight, AspectRatio aspectRatio, Integer minCharacters,
                     Integer maxCharacters, String language) {
    public enum AspectRatio {
        portrait("-0.5"), landscape("0.5"), square("0");
        final String value;
        AspectRatio(String value) { this.value = value; }
    }

    static final SearchFilters NONE = new SearchFilters(null, null, null, null, null, null, null, null, null, null);

    public SearchFilters {
        validateRange("minBookmarks", minBookmarks, "maxBookmarks", maxBookmarks);
        validateRange("minWidth", minWidth, "maxWidth", maxWidth);
        validateRange("minHeight", minHeight, "maxHeight", maxHeight);
        validateRange("minCharacters", minCharacters, "maxCharacters", maxCharacters);
        if (language != null && !language.matches("[a-zA-Z]{2,3}(?:-[a-zA-Z0-9]{2,8})*"))
            throw new HttpParameterException("query", "language", "invalid_value");
    }

    void apply(UriComponentsBuilder uri) {
        range(uri, minBookmarks, maxBookmarks, "blt", "bgt");
        range(uri, minWidth, maxWidth, "wlt", "wgt");
        range(uri, minHeight, maxHeight, "hlt", "hgt");
        range(uri, minCharacters, maxCharacters, "tlt", "tgt");
        if (aspectRatio != null) uri.queryParam("ratio", aspectRatio.value);
        if (language != null) uri.queryParam("work_lang", language.toLowerCase(java.util.Locale.ROOT));
    }

    private static void validateRange(String minField, Integer min, String maxField, Integer max) {
        if (min != null && min < 0) throw new HttpParameterException("query", minField, "out_of_range");
        if (max != null && (max < 0 || min != null && max < min))
            throw new HttpParameterException("query", maxField, "out_of_range");
    }

    private static void range(UriComponentsBuilder uri, Integer min, Integer max, String lower, String upper) {
        if (min != null) uri.queryParam(lower, min);
        if (max != null) uri.queryParam(upper, max);
    }
}
