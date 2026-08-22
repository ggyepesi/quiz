package language;

import aux.Constants;
import objectview.viewconfig.DomainViews;
import quiz.group.ViewableGroup;


import java.io.File;
import java.util.Map;
import java.util.TreeMap;

public class Languages implements DomainViews {
    private static final String DEFAULT_FILE =
            Constants.dataDirectory + "language/languages.tsv";

    private final Map<String, Language> languages = new TreeMap<>();
    private final Map<String, LanguageFamily> families = new TreeMap<>();

    private ViewableGroup rootGroup;
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
        File file = resolveDataFile(fileName);
        if (!file.exists()) {
            throw new RuntimeException(
                    "Language data file not found: "
                            + file.getAbsolutePath()
                            + "\nRun language.BuildLanguageData first."
            );
        }

        LanguagesData data =
                LanguageStore.read(file.getPath());

        languages.clear();
        families.clear();

        languages.putAll(data.getLanguages());
        families.putAll(data.getFamilies());

        rootGroup =
                data.getRootGroup();

        built = true;
    }

    /**
     * Maven runs the app module with {@code app/} as its working directory,
     * while IntelliJ normally runs from the reactor root. Accept both layouts
     * for the repository-owned data file.
     */
    private static File resolveDataFile(String fileName) {
        File direct = new File(fileName);
        if (direct.exists() || direct.isAbsolute()) {
            return direct;
        }
        File fromModule = new File("..", fileName);
        return fromModule.exists() ? fromModule : direct;
    }

    @Override
    public Map<String, ? extends Language> getViewables() {
        return languages;
    }

    @Override
    public java.util.List<objectview.viewconfig.DomainGroupRoot> getGroupRootBindings() {
        return rootGroup == null ? java.util.List.of()
                : java.util.List.of(new objectview.viewconfig.DomainGroupRoot(
                        Language.class.getSimpleName(), rootGroup));
    }

    public Map<String, Language> getLanguages() {
        return languages;
    }

    public Map<String, LanguageFamily> getFamilies() {
        return families;
    }

    public ViewableGroup getRootGroup() {
        return rootGroup;
    }

    public Language getLanguage(String name) {
        return languages.get(name);
    }

    public LanguageFamily getFamily(String name) {
        return families.get(name);
    }
}
