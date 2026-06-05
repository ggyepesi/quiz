package language;

import aux.Constants;

import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Reads languages.txt extracted from country wikipedia pages.
public class CountryLanguagesReader {
    public static void main(String[] args) throws Exception {
        Map<String, List<String>> result =
                readCountryLanguages(Constants.languageDirectory + "languages.txt");

        for (Map.Entry<String, List<String>> e : result.entrySet()) {
            System.out.println(e.getKey() + " -> " + e.getValue());
        }
    }

    public static Map<String, List<String>> readCountryLanguages(
            String fileName) throws Exception {
        Map<String, List<String>> result =
                new LinkedHashMap<>();

        try (BufferedReader r = Constants.getBufferedReaderForResource(fileName)) {
            String line;

            while ((line = r.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty()) {
                    continue;
                }

                int arrow = line.indexOf("->");

                if (arrow < 0) {
                    continue;
                }

                String country =
                        line.substring(0, arrow).trim();

                String languagesPart =
                        line.substring(arrow + 2).trim();

                List<String> languages =
                        parseLanguages(languagesPart);

                result.put(country, languages);
            }
        }

        return result;
    }

    private static List<String> parseLanguages(String s) {
        List<String> result = new ArrayList<>();

        String[] parts = s.split(",");

        for (String part : parts) {
            String language = cleanupLanguage(part);

            if (language == null) {
                continue;
            }

            if (!result.contains(language)) {
                result.add(language);
            }
        }

        return result;
    }

    private static String cleanupLanguage(String s) {
        if (s == null) {
            return null;
        }

        s = s.trim();

        if (s.isEmpty()) {
            return null;
        }

        // remove (...) comments
        s = s.replaceAll("\\([^)]*\\)", " ");

        // remove [123] references
        s = s.replaceAll("\\[[^]]*]", " ");

        // normalize spaces
        s = s.replaceAll("\\s+", " ").trim();

        // remove prefixes frequently appearing
        s = s.replaceAll("^Languages of ", "");
        s = s.replaceAll("^Other ", "");
        s = s.replaceAll("^Several ", "");

        // remove generic non-language entries
        String lower = s.toLowerCase();

        if (lower.contains("indigenous languages")) {
            return null;
        }

        if (lower.contains("national languages")) {
            return null;
        }

        if (lower.contains("other languages")) {
            return null;
        }

        if (lower.contains("sign language")
                && !Character.isUpperCase(s.charAt(0))) {
            return null;
        }

        if (s.equals("-")) {
            return null;
        }

        return s;
    }
}