package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.Selection;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Looking at a construct and saving must not change it.
 *
 * <p>Every Save flushes the editor showing the selected node, and an editor builds what
 * it writes from its controls. A control that cannot hold the saved value writes
 * whatever it holds instead: History's boundary graph outputs the imported class
 * Position, the output list left imported classes out, the combo kept its first class,
 * and the flush saved Person over Position with nobody having chosen it (#303). Each
 * editor was tested with what it was built for; none with the configuration shipped.
 *
 * <p>This drives the workbench the way a user does — select a node, then flush — for
 * every class, field, selection and domain node of every shipped model, and requires the
 * model to come back unchanged. It goes through {@link ModelSourceWorkbenchPanel} rather
 * than each editor, because which editor receives a node is itself decided there. Each
 * node is checked against a freshly loaded model, so one editor that damages the model
 * cannot hide what the next one does.
 *
 * <p>{@link #KNOWN} is what the guard found when it was written. It may only shrink: a
 * new violation fails, and so does an entry that no longer occurs, so fixing one means
 * deleting its line here.
 */
class EditorRoundTripGuardTest {

    private static final File MODELS = new File("../data/wikidata");

    /** Found 2026-10-01, all in the field editor. Key: domain | node | changed property. */
    private static final Set<String> KNOWN = Set.of(
            // A stated value is lost.
            "history | field OfficeHolding.source | edgeMembership",             // INHERIT -> NONE
            "historicalpositions | field OfficeHolding.source | edgeMembership", // INHERIT -> NONE
            "historicalpositions | field OfficeHolding.position | edgeMembership",
            "nobelprizes | field LaureatesWithMotivation.category | edgeMembership",
            "oscarnominations | field Nomination.category | edgeMembership",
            "constellations | field Star.apparentMagnitude | renderMode",        // AUTO -> INLINE
            "oscarnominations | field Nomination.ceremony | renderMode",         // AUTO -> REFERENCE
            // A default is written in where nothing was stated.
            "constellations | field Constellation.hemisphere | entityClassName",
            "constellations | field Constellation.namedAfter | entityClassName",
            "mythology | field Character.type | entityClassName",
            "periodictable | field Element.discoverer | entityClassName",
            "periodictable | field Element.namedAfter | entityClassName",
            "periodictable | field Element.partOf | entityClassName",
            "history | field OfficeHolding.startDate | matchValueField",
            "history | field OfficeHolding.endDate | matchValueField",
            "historicalpositions | field OfficeHolding.startDate | matchValueField",
            "historicalpositions | field OfficeHolding.endDate | matchValueField",
            "periodictable | field Element.discoveryDate | matchValueField");

    @Test void selectingAndFlushingEveryShippedNodeChangesNothing() throws Exception {
        List<File> models = shippedModels();
        assertTrue(models.size() >= 5,
                "the guard stopped finding the shipped models: " + models);

        Map<String, String> found = new LinkedHashMap<>();
        for (File file : models) found.putAll(violations(file));

        Set<String> unexpected = new LinkedHashSet<>(found.keySet());
        unexpected.removeAll(KNOWN);
        assertTrue(unexpected.isEmpty(), "Selecting a node and saving changed what was "
                + "saved — an editor wrote a value it did not show:\n\n"
                + String.join("\n\n", unexpected.stream()
                        .map(key -> key + "\n" + found.get(key)).toList()));

        Set<String> fixed = new LinkedHashSet<>(KNOWN);
        fixed.removeAll(found.keySet());
        assertEquals(Set.of(), fixed,
                "these no longer change the model — delete them from KNOWN");
    }

    private static Map<String, String> violations(File file) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        String domain = file.getParentFile().getName();
        GeneratedProjectModelStore store = new GeneratedProjectModelStore();
        int count = nodesOf(store.load(file)).size();
        Session session = new Session(store, file);
        try {
            for (int index = 0; index < count; index++) {
                Node node = nodesOf(session.model).get(index);
                String before = store.toJson(session.model);
                String key = null;
                String detail = null;
                try {
                    session.panel.edit(node.value());
                    session.panel.applyEdits();
                    String after = store.toJson(session.model);
                    if (!before.equals(after)) {
                        key = property(before, after);
                        detail = firstDifference(before, after);
                    }
                } catch (IOException invalid) {
                    key = "invalid";
                    detail = "    " + invalid.getMessage().strip().replace("\n", "\n    ");
                } catch (RuntimeException failure) {
                    key = "threw";
                    detail = "    " + failure;
                }
                if (key != null) {
                    out.put(domain + " | " + node.label() + " | " + key, detail);
                    // Start the next node from the saved model, not from this damage.
                    session.close();
                    session = new Session(store, file);
                }
            }
        } finally {
            session.close();
        }
        return out;
    }

    /** A freshly loaded model and the workbench editing it. */
    private static final class Session {
        final GeneratedProjectModel model;
        final ModelSourceWorkbenchPanel panel;

        Session(GeneratedProjectModelStore store, File file) throws IOException {
            model = store.load(file);
            panel = new ModelSourceWorkbenchPanel(model);
        }

        void close() { panel.close(); }
    }

    private record Node(String label, Object value) { }

    private static List<File> shippedModels() {
        List<File> out = new ArrayList<>();
        File[] domains = MODELS.listFiles(File::isDirectory);
        if (domains == null) return out;
        java.util.Arrays.sort(domains);
        for (File domain : domains) {
            File model = new File(domain, domain.getName() + ".model.json");
            if (model.isFile()) out.add(model);
        }
        return out;
    }

    /** Every node a user can select in the model tree. */
    private static List<Node> nodesOf(GeneratedProjectModel model) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(new Node("domain node", model));
        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz == null) continue;
            nodes.add(new Node("class " + clazz.className(), clazz));
            addFields(clazz.className(), clazz.fields(), nodes);
        }
        for (Selection selection : model.selections()) {
            if (selection != null) nodes.add(new Node("selection " + selection.name(), selection));
        }
        return nodes;
    }

    private static void addFields(String path, List<GeneratedFieldModel> fields,
            List<Node> nodes) {
        if (fields == null) return;
        for (GeneratedFieldModel field : fields) {
            if (field == null) continue;
            String fieldPath = path + "." + field.name();
            nodes.add(new Node("field " + fieldPath, field));
            addFields(fieldPath, field.fields(), nodes);
        }
    }

    /** The JSON property on the first line that differs. */
    private static String property(String before, String after) {
        String line = differingLines(before, after)[0];
        java.util.regex.Matcher name =
                java.util.regex.Pattern.compile("\"([^\"]+)\"\\s*:").matcher(line);
        return name.find() ? name.group(1) : line.strip();
    }

    private static String firstDifference(String before, String after) {
        String[] lines = differingLines(before, after);
        return "    - " + lines[0].strip() + "\n    + " + lines[1].strip();
    }

    private static String[] differingLines(String before, String after) {
        String[] a = before.split("\n", -1);
        String[] b = after.split("\n", -1);
        int i = 0;
        while (i < a.length && i < b.length && a[i].equals(b[i])) i++;
        return new String[] {i < a.length ? a[i] : "<end>", i < b.length ? b[i] : "<end>"};
    }
}
