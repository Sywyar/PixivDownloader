package top.sywyar.pixivdownload.sdk.community.submission;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** SPDX 单行表达式语法与固定 ID 目录；通过语法校验不代表许可证义务已审阅。 */
public final class SpdxExpression {
    private static final com.fasterxml.jackson.databind.JsonNode DATA = ControlledCatalogs.resource("spdx.json");
    public static final String CATALOG_VERSION = DATA.get("catalogVersion").textValue();
    private static final Set<String> LICENSES = lower(ControlledCatalogs.values(DATA, "licenses"));
    private static final Set<String> EXCEPTIONS = lower(ControlledCatalogs.values(DATA, "exceptions"));
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9.-]+\\+?|[()]");
    private SpdxExpression() { }

    /** 支持 SPDX ID、相邻加号、AND/OR/WITH、括号及本源码内固定文本的 LicenseRef。 */
    public static void validate(String expression, Set<String> fixedLicenseRefs) {
        if (expression == null || expression.isBlank() || !expression.equals(expression.strip())) throw invalid();
        if (expression.length() > CommunityJson.MAX_STRING_UNITS) {
            throw CommunityJson.limit("/license/expression", CommunityJson.MAX_STRING_UNITS, "UTF-16");
        }
        var refs = lower(fixedLicenseRefs);
        if (refs.size() != fixedLicenseRefs.size()) throw ContractException.invalid("DUPLICATE_KEY", "/license/licenseRefs");
        var usedRefs = new HashSet<String>();
        var matcher = TOKEN.matcher(expression);
        boolean operand = true;
        boolean withAllowed = false;
        boolean exception = false;
        int depth = 0;
        int end = 0;
        while (matcher.find()) {
            String gap = expression.substring(end, matcher.start());
            if (!gap.chars().allMatch(c -> c == ' ')) throw invalid();
            String token = matcher.group();
            if (exception) {
                if (gap.isEmpty() || !EXCEPTIONS.contains(token.toLowerCase(Locale.ROOT))) throw invalid();
                exception = false;
                withAllowed = false;
            } else if (operand) {
                if ("(".equals(token)) { depth++; }
                else {
                    String id = token.endsWith("+") ? token.substring(0, token.length() - 1) : token;
                    if (id.startsWith("LicenseRef-") && !token.endsWith("+") && id.length() > "LicenseRef-".length()) {
                        String key = id.toLowerCase(Locale.ROOT);
                        if (!refs.contains(key)) throw ContractException.invalid("LICENSE_TEXT_MISSING", "/license/licenseRefs");
                        usedRefs.add(key);
                    } else if (!LICENSES.contains(id.toLowerCase(Locale.ROOT))) throw invalid();
                    operand = false;
                    withAllowed = true;
                }
            } else if (")".equals(token)) {
                if (--depth < 0) throw invalid();
                withAllowed = false;
            } else if (Set.of("AND", "and", "OR", "or").contains(token)) {
                operand = true;
                withAllowed = false;
            } else if (("WITH".equals(token) || "with".equals(token)) && withAllowed && !gap.isEmpty()) {
                exception = true;
            } else throw invalid();
            end = matcher.end();
        }
        if (end != expression.length() || operand || exception || depth != 0 || !usedRefs.equals(refs)) throw invalid();
    }

    private static Set<String> lower(Set<String> values) {
        var result = new HashSet<String>();
        values.forEach(value -> result.add(value.toLowerCase(Locale.ROOT)));
        return Set.copyOf(result);
    }

    private static ContractException invalid() { return ContractException.invalid("LICENSE_EXPRESSION_INVALID", "/license/expression"); }
}
