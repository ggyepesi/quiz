package quiz.web;

import java.util.List;

import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;
import objectview.annotations.Inline;
import quiz.ValueObject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ViewableJsonValueObjectTest {

    static final class DatedHolding extends ViewableAdapter {
        @Inline private final aux.FlexibleDate startDate =
                aux.FlexibleDate.parse("1765");
        @Inline private final aux.FlexibleDate endDate =
                aux.FlexibleDate.parse("1790");

        @Override public String getIdentifier() { return "holding"; }
        @Override public String getDisplayName() {
            return "Office (1765–1790)";
        }
    }

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

    /** Rule 3: a ticked object with nothing ticked under it shows its field name
     *  alone, whether it is a value or an entity. */
    @Test
    void anObjectWithNothingTickedUnderItIsItsFieldNameAlone() {
        ViewableView view = ViewableJson.of(new Owner(new AnonymousDetail()));

        assertEquals(1, view.fields().size());
        assertEquals("empty", view.fields().get(0).kind());
        assertEquals("detail", view.fields().get(0).name());
    }

    /** Rule 6: an object opens in place, showing its ticked fields. */
    @Test
    void aCaptionlessObjectWithTickedFieldsOpensInPlace() {
        ViewableView view = ViewableJson.of(new Owner(new AnonymousDetail()), detail("text"));

        ViewableView.Field detail = view.fields().get(0);
        assertEquals("inline", detail.kind());
        assertEquals("AnonymousDetail", detail.nodes().get(0).type());
        assertEquals("", detail.nodes().get(0).name());
        assertEquals("detail", detail.nodes().get(0).fields().get(0).value());
    }

    /** The default ticks a nested object's DISPLAY and nothing deeper, so a value is
     *  its caption; with fields ticked it opens under that caption. */
    @Test
    void aNamedValueIsItsCaptionUntilAFieldUnderItIsTicked() {
        ViewableView byDefault = ViewableJson.of(new Owner(new NamedValue()));
        assertEquals("text", byDefault.fields().get(0).kind());
        assertEquals("Named value", byDefault.fields().get(0).value());

        ViewableView ticked = ViewableJson.of(new Owner(new NamedValue()),
                detail("@view:display", "text"));
        ViewableView.Field detail = ticked.fields().get(0);
        assertEquals("inline", detail.kind());
        assertEquals("Named value", detail.nodes().get(0).name());
        assertEquals(List.of("text"),
                detail.nodes().get(0).fields().stream().map(ViewableView.Field::name).toList());
    }

    /** An entity without a card of its own on the web is not a navigation chip; it
     *  is projected like any other object. */
    @Test
    void anEntityWithoutItsOwnCardIsProjectedNotLinked() {
        ViewableView view = ViewableJson.of(new Owner(new NamedEntity()));

        assertEquals("text", view.fields().get(0).kind());
        assertEquals("Named entity", view.fields().get(0).value());
    }

    private static objectview.viewconfig.ViewConfig detail(String... fields) {
        objectview.viewconfig.ViewConfig detail = objectview.viewconfig.ViewConfig.leaf();
        for (String field : fields) detail.addField(field, objectview.viewconfig.ViewConfig.leaf());
        objectview.viewconfig.ViewConfig owner = objectview.viewconfig.ViewConfig.leaf();
        owner.addField("detail", detail);
        return owner;
    }

    @Test
    void inlineScalarDatesRemainOrdinaryVisibleValues() {
        ViewableView view = ViewableJson.of(new DatedHolding());

        assertEquals(2, view.fields().size());
        assertEquals("startDate", view.fields().get(0).name());
        assertEquals("text", view.fields().get(0).kind());
        assertEquals("1765", view.fields().get(0).value());
        assertEquals("endDate", view.fields().get(1).name());
        assertEquals("1790", view.fields().get(1).value());
    }
}
