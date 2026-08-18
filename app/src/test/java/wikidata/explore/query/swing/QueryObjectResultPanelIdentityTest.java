package wikidata.explore.query.swing;

import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;
import wikidata.explore.query.result.ObjectQueryResult;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryObjectResultPanelIdentityTest {
    @Test void multiTypeViewKeepsTheWikidataIdentityDecorator() throws Exception {
        DynamicViewable person = new DynamicViewable("Q1", "One");
        person.type("Person");
        DynamicViewable work = new DynamicViewable("Q2", "Two");
        work.type("Work");
        QueryObjectResultPanel panel = new QueryObjectResultPanel();

        panel.accept(new ObjectQueryResult(List.of(person, work), null, null));
        SwingUtilities.invokeAndWait(() -> { });

        assertNotNull(panel.activeRenderContext());
        assertInstanceOf(JLabel.class, panel.activeRenderContext().cardDecoration(person));
        assertEquals("Q1", ((JLabel) panel.activeRenderContext()
                .cardDecoration(person)).getText());
    }
}
