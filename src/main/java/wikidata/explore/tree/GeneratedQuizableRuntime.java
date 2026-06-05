package wikidata.explore.tree;

import wikidata.explore.model.GeneratedClassModel;

public class GeneratedQuizableRuntime {
    private final GeneratedClassModel model;
    private final String qualifiedClassName;
    private final String source;
    private final Class<?> generatedClass;

    public GeneratedQuizableRuntime(
            GeneratedClassModel model,
            String qualifiedClassName,
            String source,
            Class<?> generatedClass) {
        this.model = model;
        this.qualifiedClassName = qualifiedClassName;
        this.source = source;
        this.generatedClass = generatedClass;
    }

    public GeneratedClassModel model() { return model; }
    public String qualifiedClassName() { return qualifiedClassName; }
    public String source() { return source; }
    public Class<?> generatedClass() { return generatedClass; }
}
