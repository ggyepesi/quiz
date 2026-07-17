package language;

import quiz.QuizableGroup;

import java.util.List;
import java.util.Map;

public class LanguagesData {
    private final Map<String, Language> languages;
    private final Map<String, List<String>> familyPaths;
    private final Map<String, LanguageFamily> families;
    private final Map<String, QuizableGroup> familyGroups;
    private final QuizableGroup rootGroup;

    public LanguagesData(
            Map<String, Language> languages,
            Map<String, List<String>> familyPaths,
            Map<String, LanguageFamily> families,
            Map<String, QuizableGroup> familyGroups,
            QuizableGroup rootGroup
    ) {
        this.languages = languages;
        this.familyPaths = familyPaths;
        this.families = families;
        this.familyGroups = familyGroups;
        this.rootGroup = rootGroup;
    }

    public Map<String, Language> getLanguages() {
        return languages;
    }

    public Map<String, List<String>> getFamilyPaths() {
        return familyPaths;
    }

    public Map<String, LanguageFamily> getFamilies() {
        return families;
    }

    public Map<String, QuizableGroup> getFamilyGroups() {
        return familyGroups;
    }

    public QuizableGroup getRootGroup() {
        return rootGroup;
    }
}