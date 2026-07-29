package quiz.web;

import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;
import quiz.ValueObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ViewableJsonValueObjectTest {

    static final class Owner extends ViewableAdapter {
        private final ViewableAdapter detail;

        Owner(ViewableAdapter detail) {
            this.detail = detail;
        }

        @Override public String getIdentifier() {
            return "owner";
        }

        @Override public String getDisplayName() {
            return "Owner";
        }
    }

    static final class AnonymousDetail extends ViewableAdapter implements ValueObject {
        private final String text = "detail";

        @Override public String getIdentifier() {
            return null;
        }

        @Override public String getDisplayName() {
            return "";
        }
    }

    static final class NamedValue extends ViewableAdapter implements ValueObject {
        private final String text = "detail";

        @Override public String getIdentifier() {
            return "must-not-be-rendered-as-an-id";
        }

        @Override public String getDisplayName() {
            return "Named value";
        }
    }

    static final class NamedEntity extends ViewableAdapter {
        private final String text = "detail";

        @Override public String getIdentifier() {
            return "entity-id";
        }

        @Override public String getDisplayName() {
            return "Named entity";
        }
    }

    @Test
    void anonymousObjectRendersStructurallyRegardlessOfValueIdentity() {
        ViewableView view = ViewableJson.of(new Owner(new AnonymousDetail()));

        assertEquals(1, view.fields().size());
        ViewableView.Field detail = view.fields().get(0);
        assertEquals("inline", detail.kind());
        assertEquals(1, detail.nodes().size());
        assertEquals("AnonymousDetail", detail.nodes().get(0).type());
    }

    @Test
    void namedValueKeepsTheOrdinaryChipShapeButEmbedsItsExpansion() {
        ViewableView view = ViewableJson.of(new Owner(new NamedValue()));

        ViewableView.Field detail = view.fields().get(0);
        assertEquals("ref", detail.kind());
        assertEquals("Named value", detail.ref().name());
        assertNull(detail.ref().id());
        assertNotNull(detail.ref().inline());
    }

    @Test
    void namedEntityKeepsTheSameChipShapeAndUsesLazyExpansion() {
        ViewableView view = ViewableJson.of(new Owner(new NamedEntity()));

        ViewableView.Field detail = view.fields().get(0);
        assertEquals("ref", detail.kind());
        assertEquals("Named entity", detail.ref().name());
        assertEquals("entity-id", detail.ref().id());
        assertNull(detail.ref().inline());
    }
}
