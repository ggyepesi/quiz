package language;

import java.util.ArrayList;
import java.util.List;

public class WikipediaLanguageResult {
    private final Language language;
    private final List<List<String>> familyPaths = new ArrayList<>();

    public WikipediaLanguageResult(Language language) {
        this.language = language;
    }

    public Language getLanguage() {
        return language;
    }

    public List<List<String>> getFamilyPaths() {
        return familyPaths;
    }

    public void addFamilyPath(List<String> familyPath) {
        if (familyPath != null && !familyPath.isEmpty()) {
            familyPaths.add(new ArrayList<>(familyPath));
        }
    }
}