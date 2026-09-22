package wikidata.explore.query.swing;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.query.result.ObjectQueryResult;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.JButton;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphAnnotationsShareInstancesViewTest {

    @Test void namedGraphAnnotationsArePeerSectionsWithoutCandidateShells() {
        DynamicViewable position = new DynamicViewable("Q1", "Q1");
        position.type("Position");
        WikidataDynamicObject candidateShell = object("Q2", "Position");
        WikidataDynamicObject annotation = object("Q2", "PositionValidity");
        annotation.put("Annotated instance", candidateShell);

        Map<String, List<Viewable>> sections = QueryObjectResultPanel.sections(
                new ObjectQueryResult(List.of(position), null, ""),
                Map.of("PositionValidity", List.of(annotation)));

        assertEquals(List.of("Position", "PositionValidity"),
                List.copyOf(sections.keySet()));
        assertEquals(List.of(position), sections.get("Position"));
        assertFalse(sections.get("Position").contains(candidateShell),
                "the annotation's private candidate shell is not another Position");
    }

    @Test void oneGraphTabContainsOriginalDecisionSubtabs() throws Exception {
        DynamicViewable position = new DynamicViewable("Q1", "Position");
        position.type("Position");
        WikidataDynamicObject accepted = annotation("Q1", "Accepted");
        WikidataDynamicObject review = annotation("Q2", "Review");
        WikidataDynamicObject rejected = annotation("Q3", "Rejected");
        List<Viewable> all = List.of(accepted, review, rejected);
        Map<String, List<Viewable>> decisions = new LinkedHashMap<>();
        decisions.put("Accepted", List.of(accepted));
        decisions.put("Review", List.of(review));
        decisions.put("Rejected", List.of(rejected));
        QueryObjectResultPanel panel = new QueryObjectResultPanel();

        panel.acceptGrouped(new ObjectQueryResult(List.of(position), null, ""), Map.of(
                "PositionValidity", new QueryObjectResultPanel.GroupedSection(
                        all, decisions)));
        SwingUtilities.invokeAndWait(() -> { });

        List<JTabbedPane> tabs = descendants(panel, JTabbedPane.class);
        assertEquals(2, tabs.size());
        assertEquals(List.of("Position", "PositionValidity"), titles(tabs.get(0)));
        assertEquals(List.of("All (3)", "Accepted (1)", "Review (1)", "Rejected (1)"),
                titles(tabs.get(1)));
    }

    @Test void aLaterResultDoesNotInheritTheLastRunsAnnotationTabs() throws Exception {
        QueryObjectResultPanel panel = new QueryObjectResultPanel();
        panel.acceptGrouped(result(), Map.of("PositionValidity",
                QueryObjectResultPanel.GroupedSection.of(
                        List.of(annotation("Q1", "Accepted")), Map.of())));
        SwingUtilities.invokeAndWait(() -> { });

        panel.accept(result());
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(descendants(panel, JTabbedPane.class).isEmpty(),
                "a result arriving on its own carries no graph annotations, so the "
                        + "ordinary classes keep their side-by-side layout");
    }

    @Test void aGraphThatAnnotatedNothingCostsNoLayout() throws Exception {
        QueryObjectResultPanel panel = new QueryObjectResultPanel();

        panel.acceptGrouped(result(), Map.of("PositionValidity",
                QueryObjectResultPanel.GroupedSection.of(List.of(), Map.of())));
        SwingUtilities.invokeAndWait(() -> { });

        assertTrue(descendants(panel, JTabbedPane.class).isEmpty(),
                "an empty peer draws no tab, so it must not move the ordinary classes "
                        + "out of the side-by-side layout to make room for one");
    }

    /** Two ordinary classes: side by side when nothing else is shown beside them. */
    private static ObjectQueryResult result() {
        DynamicViewable position = new DynamicViewable("Q1", "Q1");
        position.type("Position");
        DynamicViewable holder = new DynamicViewable("Q9", "Q9");
        holder.type("PositionHolder");
        return new ObjectQueryResult(List.of(position, holder), null, "");
    }

    @Test void navigationRevealsAHiddenOwningTabBeforeLookingForItsCard() {
        objectview.render.RenderContext context = new objectview.render.RenderContext();
        DynamicViewable value = new DynamicViewable("Q1", "Position");
        boolean[] revealed = { false };
        context.registerTopLevelRevealer(value, () -> revealed[0] = true);

        context.focusTopLevel(value);

        assertTrue(revealed[0]);
    }

    @Test void aGraphTabCanApplyItsCompletedResult() throws Exception {
        QueryObjectResultPanel panel = new QueryObjectResultPanel();
        int[] applied = { 0 };
        WikidataDynamicObject accepted = annotation("Q1", "Accepted");
        panel.acceptGrouped(new ObjectQueryResult(List.of(), null, ""), Map.of(
                "PositionValidity", QueryObjectResultPanel.GroupedSection.of(
                        List.of(accepted), Map.of("Accepted", List.of(accepted)),
                        List.of(new QueryObjectResultPanel.GroupAction(
                                "Apply accepted instances", () -> applied[0]++)))));
        SwingUtilities.invokeAndWait(() -> { });

        JButton apply = descendants(panel, JButton.class).stream()
                .filter(button -> button.getText().equals("Apply accepted instances"))
                .findFirst().orElseThrow();
        apply.doClick();

        assertEquals(1, applied[0]);
    }

    private static WikidataDynamicObject object(String qid, String type) {
        WikidataDynamicObject value = new WikidataDynamicObject(qid, qid);
        value.type(type);
        return value;
    }

    private static WikidataDynamicObject annotation(String qid, String decision) {
        WikidataDynamicObject value = object(qid, "PositionValidity");
        value.put("Graph decision", decision);
        return value;
    }

    private static List<String> titles(JTabbedPane tabs) {
        return java.util.stream.IntStream.range(0, tabs.getTabCount())
                .mapToObj(tabs::getTitleAt).toList();
    }

    private static <T extends Component> List<T> descendants(
            Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) found.add(type.cast(child));
            if (child instanceof Container nested) found.addAll(descendants(nested, type));
        }
        return found;
    }
}
