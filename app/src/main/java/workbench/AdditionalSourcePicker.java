package workbench;

import wikidata.explore.model.FieldSourceType;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import datasource.api.DatasourceOperation;
import datasource.api.DatasourceRegistry;
import datasource.api.SourceRecipe;
import datasource.dbpedia.DbpediaDatasourceProvider;
import datasource.wikipedia.WikipediaDatasourceProvider;

/**
 * Choosing where else a field may be read from: which Wikipedia structure to sample, then the
 * parameter or property itself.
 *
 * <p>Both workbenches ask this, and both had written it out — the same dialog, the same two
 * options, the same labels on the answers, in {@code FieldSourcePanel} and in
 * {@code ValidationPanel}. It survived being pointed at three times, which is what a
 * duplication does when neither copy has a place to live. The two callers differ in what they
 * DO with the answer (one edits the model, one records a curation choice) and in what they can
 * sample, and neither of those is the choosing.
 */
public final class AdditionalSourcePicker {

    /** Which articles the discovery reads: a sample of a class, or these entities. */
    public sealed interface Seeds {
        record OfType(String typeQid, int sampleSize) implements Seeds { }
        record OfEntities(List<String> qids) implements Seeds { }
    }

    public static Seeds ofType(String typeQid, int sampleSize) {
        return new Seeds.OfType(typeQid, sampleSize);
    }

    public static Seeds ofEntities(List<String> qids) {
        return new Seeds.OfEntities(qids == null ? List.of() : List.copyOf(qids));
    }

    /** What was chosen, in the shape a field source is written in. */
    public record Choice(FieldSourceType sourceType, String property, String label,
                         SourceRecipe recipe) { }

    private static final String CLEAR = "Clear curation choice";

    private AdditionalSourcePicker() { }

    /**
     * Asks which structure to sample, runs that structure's picker over {@code seeds}, and
     * hands back the choice. Nothing is written here.
     *
     * @param clearing offered as a third option, and run if taken; null when the caller has
     *                 nothing configured to clear
     */
    public static void choose(Component parent, SwingQueryRunner runner, Seeds seeds,
            Consumer<Choice> chosen, Runnable clearing) {
        choose(parent, runner, datasource.Datasources.standard(), seeds, chosen, clearing);
    }

    public static void choose(Component parent, SwingQueryRunner runner,
            DatasourceRegistry registry, Seeds seeds,
            Consumer<Choice> chosen, Runnable clearing) {

        Objects.requireNonNull(seeds, "Something has to be sampled");
        Objects.requireNonNull(registry, "Datasource choices need a registry");
        OperationChoice nativeInfobox = option(registry, WikipediaDatasourceProvider.ID,
                WikipediaDatasourceProvider.INFOBOX_PARAMETER,
                FieldSourceType.WIKIPEDIA_INFOBOX);
        OperationChoice dbpediaProperty = option(registry, DbpediaDatasourceProvider.ID,
                DbpediaDatasourceProvider.PROPERTY, FieldSourceType.DBPEDIA);
        Object[] options = clearing == null
                ? new Object[]{nativeInfobox, dbpediaProperty}
                : new Object[]{nativeInfobox, dbpediaProperty, CLEAR};
        Object answer = JOptionPane.showInputDialog(parent,
                "Which Wikipedia structure should be sampled?",
                "Choose additional source", JOptionPane.PLAIN_MESSAGE, null, options,
                nativeInfobox);

        if (answer == null) return;
        if (CLEAR.equals(answer)) {
            clearing.run();
            return;
        }
        if (nativeInfobox.equals(answer)) {
            infobox(parent, runner, seeds, key -> accept(chosen,
                    nativeInfobox, key, "Native Wikipedia infobox parameter"));
            return;
        }
        dbpedia(parent, runner, seeds, property -> accept(chosen,
                dbpediaProperty, property, "DBpedia infobox property"));
    }

    private static void infobox(Component parent, SwingQueryRunner runner, Seeds seeds,
            Consumer<String> selected) {
        if (seeds instanceof Seeds.OfType type) {
            WikipediaInfoboxPicker.findByType(parent, runner, type.typeQid(),
                    type.sampleSize(), selected);
        } else if (seeds instanceof Seeds.OfEntities entities) {
            WikipediaInfoboxPicker.findForEntities(parent, runner, entities.qids(), selected);
        }
    }

    private static void dbpedia(Component parent, SwingQueryRunner runner, Seeds seeds,
            Consumer<String> selected) {
        if (seeds instanceof Seeds.OfType type) {
            DbpediaPropertyPicker.findPropertyByType(parent, runner, type.typeQid(),
                    type.sampleSize(), (property, example) -> selected.accept(property));
        } else if (seeds instanceof Seeds.OfEntities entities) {
            DbpediaPropertyPicker.findProperty(parent, runner, entities.qids(),
                    (property, example) -> selected.accept(property));
        }
    }

    /** A dismissed picker hands back nothing, and nothing is what the caller hears. */
    private static void accept(Consumer<Choice> chosen, OperationChoice operation,
            String property, String label) {
        if (chosen == null || property == null || property.isBlank()) return;
        chosen.accept(new Choice(operation.sourceType(), property, label,
                new SourceRecipe(operation.providerId(),
                        operation.operation().id(),
                        java.util.Map.of("property", property, "label", label,
                                "sourceType", operation.sourceType().name()))));
    }

    private static OperationChoice option(DatasourceRegistry registry, String provider,
            String operation, FieldSourceType sourceType) {
        DatasourceOperation resolved = registry.require(provider, operation,
                DatasourceOperation.class);
        return new OperationChoice(provider, resolved, sourceType);
    }

    private record OperationChoice(
            String providerId, DatasourceOperation operation, FieldSourceType sourceType) {
        @Override public String toString() { return operation.displayName(); }
    }
}
