package language;

import objectview.annotations.ViewableReference;
import quiz.QuizableAdapter;

import java.util.ArrayList;
import java.util.List;

public class LanguageFamily extends QuizableAdapter {
    private final String name;
    @ViewableReference
    private LanguageFamily parent;

    private final List<LanguageFamily> children = new ArrayList<>();

    @ViewableReference
    private final List<Language> languages = new ArrayList<>();

    public LanguageFamily(String name) {
        this.name = name;
    }

    public LanguageFamily getParent() {
        return parent;
    }

    public List<LanguageFamily> getChildren() {
        return children;
    }

    public List<Language> getLanguages() {
        return languages;
    }

    public void addChild(LanguageFamily child) {
        if (child == null || children.contains(child)) {
            return;
        }

        children.add(child);
        child.parent = this;
    }

    public void addLanguage(Language language) {
        if (language != null && !languages.contains(language)) {
            languages.add(language);
        }
    }

    @Override
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }

    @Override
    public QuizableAdapter createNew() {
        return new LanguageFamily("");
    }
}