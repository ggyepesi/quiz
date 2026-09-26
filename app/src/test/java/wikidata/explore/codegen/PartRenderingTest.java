package wikidata.explore.codegen;

import datasource.schema.FieldType;

import org.junit.jupiter.api.Test;
import objectview.Viewable;
import objectview.render.Card;
import objectview.render.RenderContext;
import objectview.viewconfig.ViewConfig;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.*;
import wikidata.explore.transform.OwnedComponents;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A part takes its owner's display name, and the owner still keeps its own heading.
 *
 * <p>Those two together are the case worth guarding. While a part was named "owner —
 * site" the names could not collide; now they are the same string, so a renderer that
 * suppressed a heading by comparing it with a child's would drop the owner's title —
 * which is the objection the old naming convention existed to avoid. Inside its owner
 * the part shows no heading of its own, and the owner's survives.
 */
class PartRenderingTest {

    @Test void aPartTakesItsOwnersNameAndTheOwnerKeepsItsHeading() throws Exception {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("people");
        GeneratedClassModel person = new GeneratedClassModel("Person");
        GeneratedFieldModel site = person.addField(
                "birthName", FieldType.ENTITY, FieldCardinality.SINGLE);
        site.entityClassName("BirthName");
        site.renderMode(FieldRenderMode.INLINE);
        site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
        GeneratedClassModel birthName = new GeneratedClassModel("BirthName");
        birthName.ownedClass(true);
        birthName.addField("familyName", FieldType.STRING, FieldCardinality.SINGLE)
                .mapping().propertyPid("P734");
        project.rootClass(person);
        project.addClass(birthName);

        WikidataDynamicObject source = new WikidataDynamicObject("Q42", "Douglas Adams");
        source.type("Person");
        source.typeKey("Person");
        OwnedComponents.apply(project, List.of(source), null, null);
        WikidataDynamicObject component =
                (WikidataDynamicObject) source.get("birthName");
        component.put("familyName", "Adams");

        assertEquals("Douglas Adams", component.getDisplayName(),
                "the owner's name is the default; which component it is, its class and "
                        + "production site already say");
        assertTrue(component.isPart());

        try (GeneratedViewableRuntime runtime =
                     new GeneratedViewableRuntimeBuilder().build(project)) {
            Viewable mapped = (Viewable) new GeneratedViewableMapper(runtime)
                    .mapRoots(List.of(source)).getFirst();
            Viewable part = (Viewable) read(mapped, "birthName");
            assertNotNull(part);
            assertTrue(part.isPart(), "the mapped instance carries the declaration too");
            assertTrue(objectview.ViewableAdapter.isInline(
                            mapped.getClass().getDeclaredField("birthName")),
                    "owned value fields retain their configured inline semantics");

            Card[] card = new Card[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> card[0] = new Card(
                    mapped, ViewConfig.all(mapped.getClass()),
                    new RenderContext(List.of(mapped)), false));
            assertEquals("Douglas Adams", card[0].getTitle(),
                    "the owner keeps its own heading even though its part now carries "
                            + "the same name");

            ViewConfig selectedAsWhole = ViewConfig.leaf();
            selectedAsWhole.setCls((Class<? extends Viewable>) mapped.getClass());
            selectedAsWhole.addField("birthName", ViewConfig.leaf());
            Card[] whole = new Card[1];
            javax.swing.SwingUtilities.invokeAndWait(() -> whole[0] = new Card(
                    mapped, selectedAsWhole,
                    new RenderContext(List.of(mapped)), false));
            assertTrue(whole[0].hasRenderedConfiguredContent(),
                    "an undrilled inline selection renders the owned value's fields");
        }
    }

    private static Object read(Object owner, String field) {
        objectview.field.FieldSet set = ((Viewable) owner).fields();
        for (objectview.field.FieldRef ref : set.fields()) {
            if (ref.name().equalsIgnoreCase(field)) return set.read(ref.name());
        }
        return null;
    }
}
