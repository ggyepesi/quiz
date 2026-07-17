package aux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class WikiTemplateFields {
    private WikiTemplateFields() {
    }

    public static String firstClean(Map<String, String> fields, String... keys) {
        if (fields == null) {
            return null;
        }

        for (String key : keys) {
            String value = WikiTextCleaner.clean(fields.get(key));

            if (value != null) {
                return value;
            }
        }

        return null;
    }

    public static List<String> numberedCleanFields(Map<String, String> fields, String prefix) {
        if (fields == null || prefix == null) {
            return Collections.emptyList();
        }

        List<Integer> numbers = new ArrayList<>();

        for (String key : fields.keySet()) {
            if (key.matches(Patterns.quotedPrefix(prefix) + "\\d+")) {
                numbers.add(Integer.parseInt(key.substring(prefix.length())));
            }
        }

        Collections.sort(numbers);

        List<String> result = new ArrayList<>();

        for (Integer n : numbers) {
            String value = WikiTextCleaner.clean(fields.get(prefix + n));

            if (value != null) {
                result.add(value);
            }
        }

        return result;
    }

    private static class Patterns {
        private static String quotedPrefix(String prefix) {
            return java.util.regex.Pattern.quote(prefix);
        }
    }
}