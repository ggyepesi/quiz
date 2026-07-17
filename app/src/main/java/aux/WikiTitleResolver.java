package aux;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class WikiTitleResolver {
    private WikiTitleResolver() {
    }

    public static boolean shouldSkip(String name, Set<String> skip) {
        if (name == null || name.isBlank()) {
            return true;
        }

        name = name.trim();

        if (skip != null && skip.contains(name)) {
            return true;
        }

        String lower = name.toLowerCase();

        return lower.matches("\\d+.*")
                || lower.contains("other ")
                || lower.contains("several ")
                || lower.contains("more languages")
                || lower.contains("languages in ")
                || lower.contains("languages of ");
    }

    public static List<String> candidateTitles(
            String name,
            List<String> suffixes
    ) {
        List<String> result = new ArrayList<>();

        for (String suffix : suffixes) {
            String title = name;

            if (suffix != null
                    && !suffix.isEmpty()
                    && !name.endsWith(suffix)) {
                title = name + suffix;
            }

            if (!result.contains(title)) {
                result.add(title);
            }
        }

        return result;
    }

    public static String cleanInputName(String s) {
        if (s == null) {
            return null;
        }

        s = s.trim();

        s = s.replaceAll("\\[[^]]*]", "");
        s = s.replaceAll("\\([^)]*\\)", "");
        s = s.replaceAll("^[-•]\\s*", "");
        s = s.replaceAll("^\\d+\\.\\s*", "");
        s = s.replaceAll("\\s+", " ").trim();

        return s;
    }
}