package wikidata.explore.tree;

import wikidata.explore.model.GeneratedClassModel;

public class GeneratedQuizableRuntimeBuilder {
    private final GeneratedQuizableSourceGenerator sourceGenerator =
            new GeneratedQuizableSourceGenerator();
    private final RuntimeJavaCompiler compiler =
            new RuntimeJavaCompiler();

    public GeneratedQuizableRuntime build(GeneratedClassModel model) throws Exception {
        System.out.println(getClass().getName() + ".build...");
        String qcn = sourceGenerator.qualifiedClassName(model);
        String source = sourceGenerator.sourceFor(model);
        Class<?> cls = compiler.compile(qcn, source);
        System.out.println(getClass().getName() + ".build");

        return new GeneratedQuizableRuntime(model, qcn, source, cls);
    }
}
