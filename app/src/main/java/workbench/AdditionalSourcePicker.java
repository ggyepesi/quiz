package workbench;

import wikidata.explore.model.FieldSourceType;
import wikidata.explore.query.swing.SwingQueryRunner;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

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
    public record Choice(FieldSourceType sourceType, String property, String label) { }

    private static final String NATIVE = "Native Wikipedia infobox";
    private static final String DBPEDIA = "DBpedia projection";
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

        Objects.requireNonNull(seeds, "Something has to be sampled");
        Object[] options = clearing == null
                ? new Object[]{NATIVE, DBPEDIA}
                : new Object[]{NATIVE, DBPEDIA, CLEAR};
        Object answer = JOptionPane.showInputDialog(parent,
                "Which Wikipedia structure should be sampled?",
                "Choose additional source", JOptionPane.PLAIN_MESSAGE, null, options, NATIVE);

        if (answer == null) return;
        if (CLEAR.equals(answer)) {
            clearing.run();
            return;
        }
        if (NATIVE.equals(answer)) {
            infobox(parent, runner, seeds, key -> accept(chosen,
                    FieldSourceType.WIKIPEDIA_INFOBOX, key, "Native Wikipedia infobox parameter"));
            return;
        }
        dbpedia(parent, runner, seeds, property -> accept(chosen,
                FieldSourceType.DBPEDIA, property, "DBpedia infobox property"));
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
    private static void accept(Consumer<Choice> chosen, FieldSourceType sourceType,
            String property, String label) {
        if (chosen == null || property == null || property.isBlank()) return;
        chosen.accept(new Choice(sourceType, property, label));
    }
}
