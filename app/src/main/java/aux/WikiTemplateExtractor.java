package aux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WikiTemplateExtractor {
    private static final Pattern FIELD_PATTERN =
            Pattern.compile("(?m)^\\s*\\|\\s*([^=]+?)\\s*=\\s*(.*)$");

    private WikiTemplateExtractor() {
    }

    public static String extractTemplate(String text, String templateNameRegex) {
        if (text == null || templateNameRegex == null) {
            return null;
        }

        Pattern startPattern = Pattern.compile(
                "(?i)\\{\\{\\s*" + templateNameRegex + "\\b"
        );

        Matcher m = startPattern.matcher(text);

        if (!m.find()) {
            return null;
        }

        return extractBalancedTemplate(text, m.start());
    }

    public static String extractInfobox(String text) {
        return extractTemplate(text, "Infobox\\s+[^{}\\n]*");
    }

    public static String extractLanguageInfobox(String text) {
        return extractTemplate(text, "Infobox\\s+[^{}\\n]*language");
    }

    public static Map<String, String> parseFields(String template) {
        Map<String, String> fields = new LinkedHashMap<>();

        if (template == null) {
            return fields;
        }

        Matcher m = FIELD_PATTERN.matcher(template);

        String currentKey = null;
        StringBuilder currentValue = new StringBuilder();

        int lastEnd = 0;

        while (m.find()) {
            if (currentKey != null) {
                String between = template.substring(lastEnd, m.start());

                if (!between.isBlank()) {
                    currentValue.append('\n').append(between.trim());
                }

                fields.put(currentKey, currentValue.toString().trim());
            }

            currentKey = m.group(1).trim().toLowerCase();
            currentValue = new StringBuilder(m.group(2).trim());

            lastEnd = m.end();
        }

        if (currentKey != null) {
            if (lastEnd < template.length()) {
                String tail = template.substring(lastEnd);

                if (!tail.isBlank()) {
                    currentValue.append('\n').append(tail.trim());
                }
            }

            fields.put(currentKey, currentValue.toString().trim());
        }

        return fields;
    }

    private static String extractBalancedTemplate(String text, int start) {
        int i = start;
        int depth = 0;

        while (i < text.length() - 1) {
            char c1 = text.charAt(i);
            char c2 = text.charAt(i + 1);

            if (c1 == '{' && c2 == '{') {
                depth++;
                i += 2;
                continue;
            }

            if (c1 == '}' && c2 == '}') {
                depth--;
                i += 2;

                if (depth == 0) {
                    return text.substring(start, i);
                }

                continue;
            }

            i++;
        }

        return null;
    }
}