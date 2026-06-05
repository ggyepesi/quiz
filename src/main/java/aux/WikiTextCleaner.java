package aux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WikiTextCleaner {
    private WikiTextCleaner() {
    }

    public static String clean(String s) {
        if (s == null) {
            return null;
        }

        s = s.trim();

        if (s.isEmpty()) {
            return null;
        }

        s = removeComments(s);
        s = removeRefs(s);
        s = replaceBrWithComma(s);
        s = replaceWikiLinks(s);
        s = simplifyKnownTemplates(s);
        s = removeTemplatesRobustly(s);
        s = removeTablesAndFiles(s);
        s = removeHtmlTags(s);
        s = removeWikiFormatting(s);
        s = decodeBasicEntities(s);
        s = removeRemainingWikiBraces(s);
        s = normalizeWhitespaceAndCommas(s);

        if (s.isEmpty() || s.equals("-") || s.equals("—")) {
            return null;
        }

        return s;
    }

    public static List<String> splitCleanList(String value) {
        value = clean(value);

        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }

        value = value
                .replace(" and ", ",")
                .replace(";", ",");

        String[] parts = value.split("\\s*,\\s*");
        List<String> result = new ArrayList<>();

        for (String part : parts) {
            part = clean(part);

            if (part != null && !result.contains(part)) {
                result.add(part);
            }
        }

        return result;
    }

    public static String replaceWikiLinks(String s) {
        if (s == null) {
            return null;
        }

        Pattern linkPattern =
                Pattern.compile("\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?]]");

        Matcher m = linkPattern.matcher(s);
        StringBuffer sb = new StringBuffer();

        while (m.find()) {
            String target = m.group(1);
            String label = m.group(2);

            String replacement = label != null ? label : target;

            replacement = replacement
                    .replaceAll("\\([^)]*\\)", "")
                    .trim();

            m.appendReplacement(
                    sb,
                    Matcher.quoteReplacement(replacement)
            );
        }

        m.appendTail(sb);

        return sb.toString();
    }

    private static String removeComments(String s) {
        return s.replaceAll("(?is)<!--.*?-->", "");
    }

    private static String removeRefs(String s) {
        s = s.replaceAll("(?is)<ref[^>/]*/>", "");
        s = s.replaceAll("(?is)<ref[^>]*>.*?</ref>", "");
        return s;
    }

    private static String replaceBrWithComma(String s) {
        return s.replaceAll("(?is)<br\\s*/?>", ", ");
    }

    private static String simplifyKnownTemplates(String s) {
        String prev;

        do {
            prev = s;

            // {{lang|gil|Taetae ni Kiribati}} -> Taetae ni Kiribati
            s = s.replaceAll("\\{\\{\\s*lang\\s*\\|[^|{}]+\\|([^{}]+)}}", "$1");

            // {{native name|xx|Name}} -> Name
            s = s.replaceAll("\\{\\{\\s*native name\\s*\\|[^|{}]+\\|([^{}]+)}}", "$1");

            // {{small|text}} -> text
            s = s.replaceAll("\\{\\{\\s*small\\s*\\|([^{}]+)}}", "$1");

            // {{nowrap|text}} -> text
            s = s.replaceAll("\\{\\{\\s*nowrap\\s*\\|([^{}]+)}}", "$1");

            // {{transliteration|...|text}} -> text, roughly last arg
            s = s.replaceAll("\\{\\{\\s*transliteration\\s*\\|[^{}|]+\\|([^{}]+)}}", "$1");

            // {{sigfig|118,618|2}} -> 118,618
            s = s.replaceAll("\\{\\{\\s*sigfig\\s*\\|([^|{}]+)\\|[^{}]+}}", "$1");

            // {{IPA|...}}, {{IPA-all|...}} etc. remove pronunciation templates
            s = s.replaceAll("\\{\\{\\s*IPA[^{}]*}}", "");

            // {{citation needed}}, {{cn}}, etc.
            s = s.replaceAll("\\{\\{\\s*(citation needed|cn|fact)[^{}]*}}", "");

        } while (!s.equals(prev));

        return s;
    }

    private static String removeTemplatesRobustly(String s) {
        StringBuilder out = new StringBuilder();
        int depth = 0;
        int i = 0;

        while (i < s.length()) {
            if (i + 1 < s.length()
                    && s.charAt(i) == '{'
                    && s.charAt(i + 1) == '{') {
                depth++;
                i += 2;
                continue;
            }

            if (i + 1 < s.length()
                    && s.charAt(i) == '}'
                    && s.charAt(i + 1) == '}') {
                if (depth > 0) {
                    depth--;
                    i += 2;
                    continue;
                }
            }

            if (depth == 0) {
                out.append(s.charAt(i));
            }

            i++;
        }

        return out.toString();
    }

    private static String removeTablesAndFiles(String s) {
        // Remove simple file/category fragments that sometimes survive.
        s = s.replaceAll("\\[\\[(File|Image|Category):[^]]*]]", "");
        return s;
    }

    private static String removeHtmlTags(String s) {
        return s.replaceAll("(?is)<[^>]+>", "");
    }

    private static String removeWikiFormatting(String s) {
        s = s.replace("'''", "");
        s = s.replace("''", "");
        s = s.replace("__NOTOC__", "");
        return s;
    }

    private static String decodeBasicEntities(String s) {
        return s
                .replace("&nbsp;", " ")
                .replace("&ndash;", "–")
                .replace("&mdash;", "—")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    private static String removeRemainingWikiBraces(String s) {
        return s
                .replace("{{", "")
                .replace("}}", "")
                .replace("{|", "")
                .replace("|}", "");
    }

    private static String normalizeWhitespaceAndCommas(String s) {
        s = s.replace('\u00A0', ' ');

        s = s.replaceAll("\\s+", " ").trim();

        s = s.replaceAll("\\s*,\\s*", ", ");
        s = s.replaceAll(",\\s*,+", ", ");
        s = s.replaceAll("^,\\s*", "");
        s = s.replaceAll("\\s*,$", "");

        s = s.replaceAll("\\s+([,;:])", "$1");
        s = s.replaceAll("([,;:])([^\\s])", "$1 $2");

        return s.trim();
    }
}