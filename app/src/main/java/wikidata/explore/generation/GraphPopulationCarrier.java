package wikidata.explore.generation;

import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.TransformationClassSource;

/**
 * The ordinary generated class whose entity can carry a graph output membership.
 *
 * <p>A graph may add a transformation-produced subclass such as
 * {@code PositionWithHolders}. That output has no population query of its own: its input
 * {@code Position} is what generation can acquire, and the later graph application adds
 * the output membership. Keeping this resolution here gives generation and application
 * one answer instead of teaching each graph consumer about transformations and bases.
 */
public final class GraphPopulationCarrier {
    private GraphPopulationCarrier() { }

    public static GeneratedClassModel resolve(
            GeneratedProjectModel model, String outputClassName) {
        if (model == null) return null;
        GeneratedClassModel output = model.findClass(outputClassName);
        if (output == null) return null;
        GeneratedClassModel producer = transformationInput(model, output);
        if (producer != null) return producer;
        GeneratedClassModel base = output.hasBase()
                ? model.findClass(output.baseClassName()) : null;
        return base == null ? output : base;
    }

    public static String className(GeneratedProjectModel model, String outputClassName) {
        GeneratedClassModel carrier = resolve(model, outputClassName);
        return carrier == null ? clean(outputClassName) : carrier.className();
    }

    private static GeneratedClassModel transformationInput(
            GeneratedProjectModel model, GeneratedClassModel output) {
        if (!output.materializedByTransformation()) return null;
        TransformationClassSource source = output.transformationSource();
        return source == null ? null
                : model.resolveClass(source.inputClassId(), source.inputClassName());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
