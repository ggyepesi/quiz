package flag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import language.Language;
import objectview.annotations.Reference;
import quiz.ViewableGroup;
import objectview.media.ImagePane;
import objectview.ViewableAdapter;

public class State extends ViewableAdapter {

    private final String name;
    private final List<ImagePane> flagVersions = new ArrayList<>();
    private final List<ImagePane> armsVersions = new ArrayList<>();
    private final List<ImagePane> shapeVersions = new ArrayList<>();

    @Reference
    private final Map<String, ViewableGroup> groups = new TreeMap<>();

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
     *  state that still carries groups/capitals/currencies/languages is KEPT, so it
     *  is curatable in the transform app (its empty {@code flagVersions} is the
     *  filterable "no flag" fact — no separate status field needed). */
    public boolean hasContent() {
        return hasImagePane()
                || !groups.isEmpty()
                || !currencies.isEmpty()
                || !capitals.isEmpty()
                || !languages.isEmpty();
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

    public void addFlag(String key, ImagePane flag) {
        addImagePane("FLAGS", flagVersions, key, flag);
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

    public Map<String, ViewableGroup> getGroups() {
        return groups;
    }

    public void addGroup(ViewableGroup group) {
        // A local label is not an identity: "United States" can occur below
        // A state may belong to several meaningful curated group branches.
        groups.put(group.getIdentifier(), group);
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
