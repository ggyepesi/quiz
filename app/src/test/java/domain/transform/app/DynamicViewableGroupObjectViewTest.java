package domain.transform.app;

import objectview.Viewable;
import objectview.field.FieldRef;
import objectview.field.FieldSet;
import objectview.group.ViewableGroup;
import org.junit.jupiter.api.Test;
import domain.transform.EditableGroup;
import domain.runtime.WikidataDynamicObject;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A loaded group renders through its published group fields, never adapter internals. */
class DynamicViewableGroupObjectViewTest {

    @Test void loadedGroupUsesTheSameObjectViewShapeAndKeepsProducerFields() {
        WikidataDynamicObject zeus = new WikidataDynamicObject("Q34201", "Zeus");
        zeus.type("Deity");
        WikidataDynamicObject olympians = new WikidataDynamicObject(
                "Olympians", "Olympians");
        olympians.type(EditableGroup.GROUP_TYPE);
        olympians.put("role", ViewableGroup.Role.BUCKET.name());
        olympians.put("members", new LinkedHashMap<>(java.util.Map.of(
                zeus.getIdentifier(), zeus)));
        olympians.put("producer", "facet");

        ViewableGroup<?> group = DynamicViewableGroup.adapt(olympians);
        FieldSet fields = FieldSet.of((Viewable) group);
        FieldSet published = group.fields();
        List<String> names = fields.fields().stream().map(FieldRef::name).toList();

        assertEquals(names,
                published.fields().stream().map(FieldRef::name).toList());
        assertTrue(names.containsAll(List.of("members", "keyRef", "children",
                "parent", "role", "producer")), names.toString());
        assertFalse(names.contains("object"), names.toString());
        assertFalse(names.contains("cache"), names.toString());
        assertEquals(List.of(zeus), ((java.util.Map<?, ?>) fields.read("members"))
                .values().stream().toList());
        assertEquals("facet", fields.read("producer"));
    }
}
