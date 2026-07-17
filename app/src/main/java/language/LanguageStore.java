package language;

import quiz.QuizableGroup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

public class LanguageStore {
    private static final String SEP = "\t";
    private static final String LIST_SEP = ";;";

    private static final String SECTION_FAMILIES = "#FAMILIES";
    private static final String SECTION_LANGUAGES = "#LANGUAGES";

    public static void write(Collection<Language> languages, Map<Language, List<List<String>>> languageFamilyPaths,
                             String fileName) throws IOException {

        File file = new File(fileName);
        File parent = file.getParentFile();

        if (parent != null) {
            parent.mkdirs();
        }

        Map<String, List<String>> familyPaths = collectFamilyPaths(languageFamilyPaths);
        Map<Language, List<String>> languageToLeafFamilies = collectLanguageLeafFamilies(languages, languageFamilyPaths);

        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file),
                StandardCharsets.UTF_8))) {

            w.write(SECTION_FAMILIES);
            w.newLine();

            w.write(String.join(SEP, "leafFamily", "familyPath"));
            w.newLine();

            for (Map.Entry<String, List<String>> e : familyPaths.entrySet()) {
                w.write(esc(e.getKey()));
                w.write(SEP);
                w.write(escList(e.getValue()));
                w.newLine();
            }

            w.write(SECTION_LANGUAGES);
            w.newLine();

            w.write(String.join(SEP, "name", "nativeName", "writingSystem", "region", "ethnicity",
                    "speakers", "iso6391", "iso6392", "iso6393", "glottolog", "wikipediaTitle", "wikipediaUrl",
                    "countries", "scripts", "leafFamilies"));
            w.newLine();

            for (Language l : languages) {
                w.write(String.join(SEP, esc(l.getName()), esc(l.getNativeName()), esc(l.getWritingSystem()),
                        esc(l.getRegion()), esc(l.getEthnicity()), esc(l.getSpeakers()), esc(l.getIso6391()),
                        esc(l.getIso6392()), esc(l.getIso6393()), esc(l.getGlottolog()), esc(l.getWikipediaTitle()),
                        esc(l.getWikipediaUrl()), escList(l.getCountries()), escList(l.getScripts()),
                        escList(languageToLeafFamilies.get(l))));
                w.newLine();
            }
        }
    }

    public static LanguagesData read(String fileName) throws IOException {
        Map<String, List<String>> familyPaths = new TreeMap<>();
        Map<String, Language> languages = new TreeMap<>();
        Map<String, List<String>> languageLeafFamilyNames = new TreeMap<>();

        String section = null;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(fileName),
                                                    StandardCharsets.UTF_8))) {

            String line;

            while ((line = r.readLine()) != null) {
                line = line.trim();

                switch (line) {
                    case "" -> {
                        continue;
                    }
                    case SECTION_FAMILIES -> {
                        section = SECTION_FAMILIES;
                        r.readLine(); // header

                        continue; // header
                    }
                    case SECTION_LANGUAGES -> {
                        section = SECTION_LANGUAGES;
                        r.readLine(); // header

                        continue;
                    }
                }

                if (SECTION_FAMILIES.equals(section)) {
                    readFamilyLine(line, familyPaths);
                } else if (SECTION_LANGUAGES.equals(section)) {
                    readLanguageLine(line, languages, languageLeafFamilyNames);
                }
            }
        }

        return buildData(languages, familyPaths, languageLeafFamilyNames);
    }

    private static Map<String, List<String>> collectFamilyPaths(Map<Language, List<List<String>>> languageFamilyPaths) {
        Map<String, List<String>> familyPaths = new TreeMap<>();

        if (languageFamilyPaths == null) {
            return familyPaths;
        }

        for (List<List<String>> paths : languageFamilyPaths.values()) {
            if (paths == null) {
                continue;
            }

            for (List<String> path : paths) {
                if (path == null || path.isEmpty()) {
                    continue;
                }

                String leaf = path.get(path.size() - 1);

                if (leaf == null || leaf.isBlank()) {
                    continue;
                }

                familyPaths.putIfAbsent(leaf, new ArrayList<>(path));
            }
        }

        return familyPaths;
    }

    private static Map<Language, List<String>> collectLanguageLeafFamilies(
            Collection<Language> languages, Map<Language, List<List<String>>> languageFamilyPaths) {
        Map<Language, List<String>> result = new IdentityHashMap<>();

        if (languages == null || languageFamilyPaths == null) {
            return result;
        }

        List<Language> sortedLanguages = new ArrayList<>(languages);

        sortedLanguages.sort(Comparator.comparing(Language::getName));

        for (Language language : sortedLanguages) {
            List<List<String>> paths = languageFamilyPaths.get(language);

            if (paths == null) {
                continue;
            }

            for (List<String> path : paths) {
                if (path == null || path.isEmpty()) {
                    continue;
                }

                String leaf = path.get(path.size() - 1);

                if (leaf == null || leaf.isBlank()) {
                    continue;
                }

                List<String> leaves = result.computeIfAbsent(language, x -> new ArrayList<>());

                if (!leaves.contains(leaf)) {
                    leaves.add(leaf);
                }
            }
        }

        return result;
    }

    private static void readFamilyLine(String line, Map<String, List<String>> familyPaths) {
        String[] p = line.split(SEP, -1);

        if (p.length < 2) {
            return;
        }

        String leafFamily = unesc(p[0]);
        List<String> path = unescList(p[1]);

        if (leafFamily != null && !path.isEmpty()) {
            familyPaths.put(leafFamily, path);
        }
    }

    private static void readLanguageLine(String line, Map<String, Language> languages,
                                         Map<String, List<String>> languageLeafFamilyNames) {
        String[] p = line.split(SEP, -1);

        if (p.length < 15) {
            return;
        }

        Language l = new Language(unesc(p[0]));

        l.setNativeName(unesc(p[1]));
        l.setWritingSystem(unesc(p[2]));
        l.setRegion(unesc(p[3]));
        l.setEthnicity(unesc(p[4]));
        l.setSpeakers(unesc(p[5]));
        l.setIso6391(unesc(p[6]));
        l.setIso6392(unesc(p[7]));
        l.setIso6393(unesc(p[8]));
        l.setGlottolog(unesc(p[9]));
        l.setWikipediaTitle(unesc(p[10]));
        l.setWikipediaUrl(unesc(p[11]));

        for (String c : unescList(p[12])) {
            l.addCountry(c);
        }

        for (String s : unescList(p[13])) {
            l.addScript(s);
        }

        List<String> leafFamilies = unescList(p[14]);

        languages.put(l.getName(), l);
        languageLeafFamilyNames.put(l.getName(), leafFamilies);
    }

    private static LanguagesData buildData(Map<String, Language> languages, Map<String, List<String>> familyPaths,
                                           Map<String, List<String>> languageLeafFamilyNames) {
        Map<String, LanguageFamily> families = new TreeMap<>();
        Map<String, QuizableGroup> familyGroups = new TreeMap<>();

        QuizableGroup root = new QuizableGroup("All languages");

        buildFamilyTrees(familyPaths, families, familyGroups, root);
        attachLanguages(languages, languageLeafFamilyNames, families, familyGroups, root);

        return new LanguagesData(languages, familyPaths, families, familyGroups, root);
    }

    private static void buildFamilyTrees(Map<String, List<String>> familyPaths, Map<String, LanguageFamily> families,
                                         Map<String, QuizableGroup> familyGroups, QuizableGroup root) {
        for (List<String> path : familyPaths.values()) {
            LanguageFamily previousFamily = null;
            QuizableGroup group = root;

            for (String familyName : path) {
                if (familyName == null || familyName.isBlank()) {
                    continue;
                }

                LanguageFamily currentFamily = families.computeIfAbsent(familyName, LanguageFamily::new);

                if (previousFamily != null) {
                    previousFamily.addChild(currentFamily);
                }

                group = group.getOrCreateChild(familyName);

                familyGroups.putIfAbsent(familyName, group);

                previousFamily = currentFamily;
            }
        }
    }

    private static void attachLanguages(
            Map<String, Language> languages, Map<String, List<String>> languageLeafFamilyNames,
            Map<String, LanguageFamily> families, Map<String, QuizableGroup> familyGroups, QuizableGroup root) {
        for (Language language : languages.values()) {
            List<String> leafNames = languageLeafFamilyNames.get(language.getName());

            if (leafNames == null || leafNames.isEmpty()) {
                root.addMember(language);
                continue;
            }

            for (String leafName : leafNames) {
                LanguageFamily family = families.get(leafName);

                if (family != null) {
                    language.addLeafFamily(family);
                    family.addLanguage(language);
                }

                QuizableGroup group = familyGroups.get(leafName);

                if (group != null) {
                    group.addMember(language);
                } else {
                    root.addMember(language);
                }
            }
        }
    }

    private static String escList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        List<String> escaped = new ArrayList<>();

        for (String value : values) {
            escaped.add(esc(value));
        }

        return String.join(LIST_SEP, escaped);
    }

    private static List<String> unescList(String s) {
        s = unesc(s);

        if (s == null || s.isEmpty()) {
            return Collections.emptyList();
        }

        String[] parts = s.split(Pattern.quote(LIST_SEP), -1);
        List<String> result = new ArrayList<>();

        for (String part : parts) {
            part = unesc(part);

            if (part != null && !part.isBlank()) {
                result.add(part);
            }
        }

        return result;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }

        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n",
                "\\n").replace("\r", "\\r");
    }

    private static String unesc(String s) {
        if (s == null) {
            return null;
        }

        StringBuilder out = new StringBuilder();
        boolean slash = false;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (slash) {
                switch (c) {
                    case 't':
                        out.append('\t');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    default:
                        out.append(c);
                        break;
                }

                slash = false;
            } else if (c == '\\') {
                slash = true;
            } else {
                out.append(c);
            }
        }

        if (slash) {
            out.append('\\');
        }

        String result = out.toString().trim();

        return result.isEmpty() ? null : result;
    }
}