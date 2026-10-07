package quiz.web;

import objectview.field.FieldKind;
import objectview.field.FieldRef;
import objectview.field.FieldRole;
import objectview.field.FieldSet;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A loaded snapshot object keeps its display name as its label, not among its property
 * values, while its schema names a DISPLAY field ("name", as the saved State class
 * declared it). Reading that field returned null, so every card of such an object —
 * the served countries' US states, on the web and in TransformApp — had no caption
 * although DISPLAY was ticked. Found by smoke-testing the web app.
 */
class SnapshotDisplayIsItsLabelTest {

    @Test void theDisplayFieldOfALoadedObjectReadsItsLabel() {
        WikidataDynamicObject alabama = alabama();

        assertEquals("Alabama", FieldSet.of(alabama).read("name"));
        assertEquals("Alabama", ViewableJson.of(alabama).name(),
                "the default ticks DISPLAY, so the card has its caption");
    }

    @Test void anUntickedDisplayStillShowsNoCaption() {
        objectview.viewconfig.ViewConfig population = objectview.viewconfig.ViewConfig.leaf();
        population.addField("population", objectview.viewconfig.ViewConfig.leaf());

        assertEquals("", ViewableJson.of(alabama(), population).name(),
                "the label is the DISPLAY field's value, never a fallback for it");
    }

    private static WikidataDynamicObject alabama() {
        WikidataDynamicObject alabama = new WikidataDynamicObject("Alabama", "Alabama");
        alabama.type("USState");
        alabama.put("population", "5024279");
        List<FieldRef> schema = List.of(
                FieldRef.described("name", "name", FieldRole.DISPLAY, FieldKind.TEXT,
                        FieldKind.TEXT, "String", false, false, null, false, false,
                        false, false, false, "", false),
                FieldRef.described("population", "population", FieldRole.NONE,
                        FieldKind.TEXT, FieldKind.TEXT, "String", false, false, null,
                        false, false, false, false, false, "", false));
        alabama.dynamicFieldSchema(() -> schema);
        return alabama;
    }
}
