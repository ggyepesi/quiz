package language;

import quiz.group.ViewableGroup;

import java.util.List;
import java.util.Map;

public class LanguagesData {
    private final Map<String, Language> languages;
    private final Map<String, List<String>> familyPaths;
    private final Map<String, LanguageFamily> families;
    private final Map<String, ViewableGroup> familyGroups;
    private final ViewableGroup rootGroup;

    public LanguagesData(
            Map<String, Language> languages,
            Map<String, List<String>> familyPaths,
            Map<String, LanguageFamily> families,
            Map<String, ViewableGroup> familyGroups,
            ViewableGroup rootGroup
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

    public Map<String, ViewableGroup> getFamilyGroups() {
        return familyGroups;
    }

    public ViewableGroup getRootGroup() {
        return rootGroup;
    }
}