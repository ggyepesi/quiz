package language;

import aux.Constants;
import objectview.QuizableGroup;

import objectview.QuizableGroupView;
import objectview.QuizableViews;

import java.io.File;
import java.util.Map;
import java.util.TreeMap;

public class Languages implements QuizableViews {
    private static final String DEFAULT_FILE =
            Constants.dataDirectory + "language/languages.tsv";

    private final Map<String, Language> languages = new TreeMap<>();
    private final Map<String, LanguageFamily> families = new TreeMap<>();

    private QuizableGroup rootGroup;
    private QuizableGroupView groupView;
    private boolean built = false;

    public Languages() {
    }

    @Override
    public void buildViews() throws Exception {
        buildView(DEFAULT_FILE);
    }

    public void buildView(String fileName) throws Exception {
        if (built) {
            return;
        }
        File file = new File(fileName);
        if (!file.exists()) {
            throw new RuntimeException(
                    "Language data file not found: "
                            + file.getAbsolutePath()
                            + "\nRun language.BuildLanguageData first."
            );
        }

        LanguagesData data =
                LanguageStore.read(fileName);

        languages.clear();
        families.clear();

        languages.putAll(data.getLanguages());
        families.putAll(data.getFamilies());

        rootGroup =
                data.getRootGroup();

        groupView =
                new QuizableGroupView(
                        rootGroup
                );

        built = true;
    }

    @Override
    public Map<String, ? extends Language> getQuizables() {
        return languages;
    }

    @Override
    public QuizableGroupView getGroupView() {
        return groupView;
    }

    public Map<String, Language> getLanguages() {
        return languages;
    }

    public Map<String, LanguageFamily> getFamilies() {
        return families;
    }

    public QuizableGroup getRootGroup() {
        return rootGroup;
    }

    public Language getLanguage(String name) {
        return languages.get(name);
    }

    public LanguageFamily getFamily(String name) {
        return families.get(name);
    }
}