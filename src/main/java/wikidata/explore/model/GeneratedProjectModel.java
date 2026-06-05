package wikidata.explore.model;

/**
 * Project wrapper. For now the visible UI exposes a single root class only.
 */
public class GeneratedProjectModel {

    private String name = "Generated Wikidata Project";
    private GeneratedClassModel rootClass = new GeneratedClassModel("Constellation");

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
    }
}
