package wikidata.explore.advisor;

import java.util.List;

/**
 * UI-neutral explanation of one selected model element.
 *
 * <p>The Guide renders this now. The graph and runtime resolution views can use
 * the same source-route vocabulary without depending on Swing or HTML.</p>
 */
public record ModelElementExplanation(
        Scope scope,
        String breadcrumb,
        String title,
        String intent,
        String resultShape,
        List<SourceRouteExplanation> sourceRoutes,
        String example,
        List<String> advice) {

    public enum Scope { MODEL, CLASS, FIELD }

    public ModelElementExplanation {
        scope = scope == null ? Scope.MODEL : scope;
        breadcrumb = clean(breadcrumb);
        title = clean(title);
        intent = clean(intent);
        resultShape = clean(resultShape);
        sourceRoutes = sourceRoutes == null ? List.of() : List.copyOf(sourceRoutes);
        example = clean(example);
        advice = advice == null ? List.of() : List.copyOf(advice);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
