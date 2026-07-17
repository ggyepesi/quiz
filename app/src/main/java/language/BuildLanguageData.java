package language;

import aux.Constants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;

public class BuildLanguageData {
    public static void main(String[] args) throws Exception {
        String inputFile = Constants.languageDataDirectory + "languages.txt";
        String outputFile = Constants.languageDataDirectory + "languages.tsv";

        List<String> names = readNames(inputFile);

        WikipediaLanguageReader reader = new WikipediaLanguageReader();

        List<Language> languages = new ArrayList<>();

        Map<Language, List<List<String>>> languageFamilyPaths = new IdentityHashMap<>();

        for (String name : names) {
            try {
                System.out.println("Reading " + name);

                WikipediaLanguageResult result = reader.readLanguage(name);

                if (result == null) {
                    System.out.println("Skipped " + name);
                    continue;
                }

                Language language = result.getLanguage();

                languages.add(language);

                languageFamilyPaths.put(language, result.getFamilyPaths());

            } catch (Exception e) {
                System.err.println("FAILED: " + name);
                e.printStackTrace();
            }
        }

        LanguageStore.write(languages, languageFamilyPaths, outputFile);

        System.out.println("Wrote " + outputFile);
    }

    private static List<String> readNames(String fileName) throws Exception {
        Set<String> names = new TreeSet<>();

        try (BufferedReader r = new BufferedReader(new FileReader(fileName))) {
            String line;

            while ((line = r.readLine()) != null) {
                line = line.trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int arrow = line.indexOf("->");

                if (arrow >= 0) {
                    line = line.substring(arrow + 2).trim();
                }

                for (String part : line.split(",")) {
                    String language = part.trim();

                    if (!language.isEmpty()) {
                        names.add(language);
                    }
                }
            }
        }

        return new ArrayList<>(names);
    }
}