package wikidata.explore.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Project wrapper. Holds a registry of configured classes.
 * The root class is always at index 0 of the class list.
 */
public class GeneratedProjectModel {

    private String name = "Generated Wikidata Project";
    private GeneratedClassModel rootClass;

    private final List<GeneratedClassModel> classes = new ArrayList<>();

    public GeneratedProjectModel() {
        rootClass = new GeneratedClassModel("Constellation");
        classes.add(rootClass);
    }

    public static GeneratedProjectModel constellationDemo() {
        GeneratedProjectModel p = new GeneratedProjectModel();
        p.name("Constellations");

        GeneratedClassModel c = new GeneratedClassModel("Constellation");
        c.instanceMapping().sourceQid("Q8928");
        c.instanceMapping().sourceLabel("constellation");
        c.instanceMapping().propertyPid("P31");
        c.instanceMapping().propertyLabel("instance of");
        c.instanceMapping().limit(200);

        p.rootClass(c);
        return p;
    }

    public String name() { return name; }
    public void name(String name) {
        this.name =
                name == null || name.isBlank()
                        ? "Generated Wikidata Project"
                        : name.trim();
    }

    public GeneratedClassModel rootClass() { return rootClass; }
    public void rootClass(GeneratedClassModel rootClass) {
        this.rootClass =
                rootClass == null ? new GeneratedClassModel("GeneratedClass") : rootClass;
        if (!classes.contains(this.rootClass)) {
            classes.add(0, this.rootClass);
        } else {
            classes.remove(this.rootClass);
            classes.add(0, this.rootClass);
        }
    }

    public List<GeneratedClassModel> classes() {
        return Collections.unmodifiableList(classes);
    }

    public void addClass(GeneratedClassModel c) {
        if (c != null && !classes.contains(c)) {
            classes.add(c);
        }
    }
}
