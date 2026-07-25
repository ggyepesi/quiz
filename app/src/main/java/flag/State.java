package flag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import language.Language;
import objectview.annotations.Reference;
import quiz.QuizableGroup;
import objectview.media.ImagePane;
import quiz.QuizableAdapter;

public class State extends QuizableAdapter {

    /** Whether this state has a flag. Explicit (not just an empty flag list) so a
     *  genuinely flagless territory — e.g. Kingman Reef — is a curatable fact in the
     *  snapshot rather than a silent gap or a broken image. Derived at load: a state
     *  gets a flag → {@link #AVAILABLE}; none resolves → stays {@link #MISSING}.
     *  AVAILABLE serializes blank so it doesn't clutter the ~200 flagged cards (the
     *  flag itself is the signal); only MISSING shows, as a "no flag" curation marker. */
    public enum FlagStatus {
        AVAILABLE(""),
        MISSING("no flag");

        private final String label;

        FlagStatus(String label) { this.label = label; }

        @Override public String toString() { return label; }
    }

    private final String name;
    private final List<ImagePane> flagVersions = new ArrayList<>();
    private final List<ImagePane> armsVersions = new ArrayList<>();
    private final List<ImagePane> shapeVersions = new ArrayList<>();
    private FlagStatus flagStatus = FlagStatus.MISSING;

    @Reference
    private final Map<String, QuizableGroup> groups = new TreeMap<>();

    private final Set<String> currencies = new TreeSet<>();
    private final Set<String> capitals = new TreeSet<>();
    @Reference
    private final List<Language> languages = new ArrayList<>();

    public State(String name) {
        this.name = name;
    }

    public boolean hasImagePane() {
        return !flagVersions.isEmpty() || !armsVersions.isEmpty() || !shapeVersions.isEmpty();
    }

    /** Any curatable content at all — an image, a group membership, or a scalar
     *  fact. A state with none is a stray key and is dropped on load; a flagless
     *  state that still carries groups/capitals/currencies/languages is KEPT and
     *  marked {@link FlagStatus#MISSING} so it can be curated in the transform app. */
    public boolean hasContent() {
        return hasImagePane()
                || !groups.isEmpty()
                || !currencies.isEmpty()
                || !capitals.isEmpty()
                || !languages.isEmpty();
    }

    public FlagStatus getFlagStatus() {
        return flagStatus;
    }

    public List<ImagePane> getFlagVersions() {
        return flagVersions;
    }

    public List<ImagePane> getArmsVersions() {
        return armsVersions;
    }

    public List<ImagePane> getShapeVersions() {
        return shapeVersions;
    }

    @Override
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }

    @Override
    public State createNew() {
        return new State("");
    }

    public void addFlag(String key, ImagePane flag) {
        addImagePane("FLAGS", flagVersions, key, flag);
        if (!flagVersions.isEmpty()) {
            flagStatus = FlagStatus.AVAILABLE;
        }
    }

    public void addArms(String key, ImagePane arms) {
        addImagePane("ARMS", armsVersions, key, arms);
    }

    private void addImagePane(String type, List<ImagePane> imagePanes, String key, ImagePane imagePane) {
        for (ImagePane p : imagePanes) {
            if (key.equals(p.getKey())) {
                System.out.println(type + " DONTADDDUPLICATE " + key);
                return;
            }
        }
        imagePanes.add(imagePane);
        imagePane.setKey(key);
        if (imagePanes.size() > 1) {
            System.out.println(type + " TOOMANY " + name + ", " + imagePanes.size());
            for (ImagePane p : imagePanes) {
                System.out.println("  " + p.getKey());
            }
        }
    }

    public void addShape(String version, ImagePane shape) {
        shapeVersions.add(shape);
    }

    public Map<String, QuizableGroup> getGroups() {
        return groups;
    }

    public void addGroup(QuizableGroup group) {
        groups.put(group.getName(), group);
    }

    public Set<String> getCurrencies() {
        return currencies;
    }

    public Set<String> getCapitals() {
        return capitals;
    }

    public List<Language> getLanguages() {
        return languages;
    }

    @Override
    public String toString() {
        return name + "(" + capitals + "," + currencies + ", " + groups.keySet() + ")";
    }
}
