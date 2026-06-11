package wikidata.explore.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GeneratedProjectModel {

    private String name = "Generated Wikidata Project";
    private GeneratedClassModel rootClass;

    private final List<GeneratedClassModel> classes = new ArrayList<>();

    public GeneratedProjectModel() {
        rootClass = new GeneratedClassModel("Constellation");
        rootClass.ensureNameField();
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

    public String name() {
        return name;
    }

    public void name(String name) {
        this.name =
                name == null || name.isBlank()
                        ? "Generated Wikidata Project"
                        : name.trim();
    }

    public GeneratedClassModel rootClass() {
        rootClass.ensureNameField();
        return rootClass;
    }

    public void rootClass(GeneratedClassModel rootClass) {
        GeneratedClassModel oldRoot = this.rootClass;

        this.rootClass =
                rootClass == null
                        ? new GeneratedClassModel("GeneratedClass")
                        : rootClass;

        this.rootClass.ensureNameField();

        if (oldRoot != null && oldRoot != this.rootClass) {
            classes.remove(oldRoot);
        }

        classes.remove(this.rootClass);
        classes.addFirst(this.rootClass);
    }

    public List<GeneratedClassModel> classes() {
        for (GeneratedClassModel c : classes) {
            c.ensureNameField();
        }

        return Collections.unmodifiableList(classes);
    }

    public void addClass(GeneratedClassModel c) {
        if (c == null) {
            return;
        }

        c.ensureNameField();

        if (!classes.contains(c)) {
            classes.add(c);
        }
    }

    public GeneratedClassModel findClass(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }

        for (GeneratedClassModel c : classes) {
            if (name.equals(c.className())) {
                return c;
            }
        }

        return null;
    }

    public GeneratedClassModel getOrCreateClass(String name) {
        String clean =
                name == null || name.isBlank()
                        ? "GeneratedClass"
                        : name.trim();

        GeneratedClassModel existing = findClass(clean);

        if (existing != null) {
            return existing;
        }

        GeneratedClassModel created = new GeneratedClassModel(clean);
        addClass(created);
        return created;
    }

    public void removeClass(GeneratedClassModel c) {
        if (c == null || c == rootClass) {
            return;
        }

        classes.remove(c);
    }

    @Override
    public String toString() {
        return name;
    }
}