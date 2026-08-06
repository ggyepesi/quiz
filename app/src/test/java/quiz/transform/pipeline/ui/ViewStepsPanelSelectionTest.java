package quiz.transform.pipeline.ui;

import flag.State;
import org.junit.jupiter.api.Test;
import quiz.curation.ScopeFilter;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.ReflectionDomain;
import quiz.transform.ui.TransformController;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ViewStepsPanelSelectionTest {

    @Test
    void selectingAFieldIsReportedWhileValueScopeRemainsAll() {
        TransformController controller = new TransformController(
                new ReflectionDomain(List.of(new State("France"))), null);
        AtomicReference<DomainField> selected = new AtomicReference<>();
        AtomicReference<ScopeFilter> scope = new AtomicReference<>();
        ViewStepsPanel panel = new ViewStepsPanel(
                controller, () -> { }, null, List::of,
                (field, filter) -> {
                    selected.set(field);
                    scope.set(filter);
                },
                () -> { });

        selected.set(null);
        scope.set(null);
        panel.selectField("capitals", ScopeFilter.ALL);

        assertNotNull(selected.get());
        assertEquals("capitals", selected.get().field());
        assertEquals(ScopeFilter.ALL, scope.get());
    }
}
