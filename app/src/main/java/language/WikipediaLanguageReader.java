package language;

import aux.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class WikipediaLanguageReader {
    private static final List<String> TITLE_SUFFIXES = List.of("", " language", " languages");

    private static final Set<String> SKIP = Set.of("Native languages", "National languages", "Indian languages", "Bantu languages", "Khoisan languages", "Other languages", "Indigenous languages", "Sinitic languages", "Sign language", "Several Khoisan languages", "Other Bantu languages", "Creole");

    private static final Map<String, String> TITLE_ALIASES = Map.ofEntries(Map.entry("Assyrian", "Assyrian Neo-Aramaic"), Map.entry("Baniwa", "Baniwa"), Map.entry("Benshangul", "Berta language"), Map.entry("Central Thai", "Thai language"), Map.entry("Chácobo", "Chácobo language"), Map.entry("Fala", "Fala language"), Map.entry("Gilbertese", "Gilbertese language"), Map.entry("Gulmancema", "Gurma language"), Map.entry("Ibanag", "Ibanag language"), Map.entry("Iraqi Turkmen", "Iraqi Turkmen dialect"), Map.entry("Kriol", "Belizean Creole"), Map.entry("Lemko", "Lemko language"), Map.entry("Maninke", "Maninka language"), Map.entry("Moquoit", "Mocoví language"), Map.entry("Ndebele", "Northern Ndebele language"), Map.entry("SA Sign Language", "South African Sign Language"), Map.entry("Tati", "Tati language (Iran)"), Map.entry("Tonga", "Tonga language (Zambia and Zimbabwe)"), Map.entry("Tongan", "Tongan language"), Map.entry("Turkmeni", "Turkmen language"), Map.entry("Ulster-Scots", "Ulster Scots dialect"), Map.entry("Upper Sorbian", "Upper Sorbian language"), Map.entry("Wolof", "Wolof language"), Map.entry("Kom", "Kom language (India)"));

    // A Wikipedia "speakers" infobox value often leads with a language-use label — the
    // resolved wikilink [[first language|L1]] (native speakers) or [[second language|L2]] —
    // e.g. "L1: 74 million" or "L1 and L2: 80% of China". Strip that leading label so the
    // value leads with the count itself (readable, and the leading number is sortable). A
    // value with no such label (or a label with no colon, i.e. free prose) is left as-is.
    private static final java.util.regex.Pattern SPEAKER_USE_LABEL =
            java.util.regex.Pattern.compile(
                    "(?i)^L[12](?:\\s*(?:and|,|&|/)\\s*L[12])*\\s*:\\s*");

    static String cleanSpeakers(String value) {
        if (value == null) {
            return null;
        }
        return SPEAKER_USE_LABEL.matcher(value).replaceFirst("");
    }

    private static List<List<String>> buildFamilyPaths(Map<String, String> fields) {
        List<String> levels = WikiTemplateFields.numberedCleanFields(fields, "fam");

        List<List<String>> paths = new ArrayList<>();
        paths.add(new ArrayList<>());

        for (String level : levels) {
            List<String> alternatives = WikiTextCleaner.splitCleanList(level);

            if (alternatives.isEmpty()) {
                continue;
            }

            List<List<String>> nextPaths = new ArrayList<>();

            for (List<String> path : paths) {
                for (String alternative : alternatives) {
                    List<String> newPath = new ArrayList<>(path);
                    newPath.add(alternative);
                    nextPaths.add(newPath);
                }
            }

            paths = nextPaths;
        }

        paths.removeIf(List::isEmpty);
        return paths;
    }

    private static List<String> candidateTitles(String languageName) {
        List<String> titles = new ArrayList<>();

        String alias = TITLE_ALIASES.get(languageName);

        if (alias != null) {
            titles.add(alias);
        }

        for (String title : WikiTitleResolver.candidateTitles(languageName, TITLE_SUFFIXES)) {
            if (!titles.contains(title)) {
                titles.add(title);
            }
        }

        return titles;
    }

    public WikipediaLanguageResult readLanguage(String languageName) throws Exception {
        languageName = WikiTitleResolver.cleanInputName(languageName);

        if (WikiTitleResolver.shouldSkip(languageName, SKIP)) {
            return null;
        }

        Exception lastException = null;

        for (String candidateTitle : candidateTitles(languageName)) {
            try {
                WikipediaLanguageResult result = readLanguageByWikipediaTitle(languageName, candidateTitle);

                if (result != null) {
                    return result;
                }
            } catch (Exception e) {
                lastException = e;
            }
        }

        if (lastException != null) {
            throw lastException;
        }

        return null;
    }

    private WikipediaLanguageResult readLanguageByWikipediaTitle(
            String originalName, String candidateTitle) throws Exception {

        WikiRawPageReader.ResolvedPage page = WikiRawPageReader.readResolvedRawPage(candidateTitle);

        String infobox = WikiTemplateExtractor.extractInfobox(page.getText());

        if (infobox == null) {
            return null;
        }

        Language language = new Language(originalName);

        language.setWikipediaTitle(page.getTitle().replace("_", " "));
        language.setWikipediaUrl(page.getUrl());

        WikipediaLanguageResult result = new WikipediaLanguageResult(language);

        parseInfobox(result, infobox);

        return result;
    }

    private void parseInfobox(WikipediaLanguageResult result, String infobox) {
        Language language = result.getLanguage();

        Map<String, String> fields = WikiTemplateExtractor.parseFields(infobox);

        List<List<String>> familyPaths = buildFamilyPaths(fields);

        for (List<String> familyPath : familyPaths) {
            result.addFamilyPath(familyPath);
        }

        language.setNativeName(WikiTemplateFields.firstClean(fields, "nativename", "native name"));

        language.setWritingSystem(WikiTemplateFields.firstClean(fields, "script", "scripts", "writing system"));

        language.setRegion(WikiTemplateFields.firstClean(fields, "region"));

        language.setEthnicity(WikiTemplateFields.firstClean(fields, "ethnicity"));

        language.setSpeakers(cleanSpeakers(WikiTemplateFields.firstClean(fields, "speakers")));

        language.setIso6391(WikiTemplateFields.firstClean(fields, "iso1"));

        language.setIso6392(WikiTemplateFields.firstClean(fields, "iso2"));

        language.setIso6393(WikiTemplateFields.firstClean(fields, "iso3"));

        language.setGlottolog(WikiTemplateFields.firstClean(fields, "glotto"));

        for (String country : WikiTextCleaner.splitCleanList(WikiTemplateFields.firstClean(fields, "states", "state"))) {
            language.addCountry(country);
        }

        for (String script : WikiTextCleaner.splitCleanList(language.getWritingSystem())) {
            language.addScript(script);
        }
    }
}