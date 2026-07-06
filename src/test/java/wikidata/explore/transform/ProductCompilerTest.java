package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import quiz.transform.app.ProductDomain;
import quiz.transform.ui.DomainField;
import quiz.ui.viewconfig.FieldTypeSource;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The compile step reads the declared model as the authority: a reference to a
 * modeled class stays a reference (labelled by class), a reference to an UNMODELED
 * class collapses to a display string, cardinality comes from the model, the reify
 * `source` and auto-seeded `wikidata` are structural, and QID is never a field.
 */
class ProductCompilerTest {

    private static GeneratedProjectModel model() {
        GeneratedProjectModel m = new GeneratedProjectModel();

        GeneratedClassModel osc = new GeneratedClassModel("OscarNominations");
        m.rootClass(osc);

        // A declared class used as a reference target (a member once instances exist).
        m.addClass(new GeneratedClassModel("Category"));
        // A declared-but-bare label class.
        m.addClass(new GeneratedClassModel("Type"));

        GeneratedClassModel nom = new GeneratedClassModel("Nomination");
        nom.statementSourceClass("OscarNominations");   // -> reify `source` back-ref
        ref(nom, "nominee", "OscarNominations", FieldCardinality.AUTO);
        ref(nom, "forWork", "ForWork", FieldCardinality.AUTO);      // ForWork is UNMODELED
        ref(nom, "target", "Category", FieldCardinality.COLLECTION);
        nom.addField("won", FieldType.BOOLEAN, FieldCardinality.SINGLE);
        m.addClass(nom);

        return m;
    }

    private static void ref(GeneratedClassModel c, String name,
                            String target, FieldCardinality card) {
        GeneratedFieldModel f = c.addField(name, FieldType.ENTITY, card);
        f.entityClassName(target);
    }

    private static WikidataDynamicObject substantive(String qid, String name) {
        WikidataDynamicObject o = new WikidataDynamicObject(qid, name);
        o.put("marker", "x");   // a field beyond `wikidata` so it isn't bare
        return o;
    }

    private static List<WikidataDynamicObject> pool() {
        WikidataDynamicObject osc = substantive("Q1", "The Nominee");
        osc.type("OscarNominations");

        WikidataDynamicObject film = substantive("Q2", "Schindler's List");   // ForWork
        WikidataDynamicObject cat = substantive("Q3", "Best Picture");        // Category

        WikidataDynamicObject nom = new WikidataDynamicObject("Q4-abc", "Nomination 1");
        nom.type("Nomination");
        nom.put("nominee", osc);
        nom.put("forWork", film);
        nom.put("target", new java.util.ArrayList<>(List.of(cat)));
        nom.put("won", Boolean.TRUE);
        nom.put("wikidata", "http://www.wikidata.org/entity/Q4");

        return new java.util.ArrayList<>(List.of(osc, nom));
    }

    private static DomainField field(ProductDomain d, String type, String path) {
        for (DomainField f : d.fields(type)) {
            if (f.field().equals(path)) {
                return f;
            }
        }
        return null;
    }

    @Test void membersAreStampedSubstantiveClassesOnly() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        assertEquals(List.of("OscarNominations", "Nomination"), d.types(),
                "Category/Type appear only as reference targets, not members");
    }

    @Test void modeledReferenceStaysAReference() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        DomainField nominee = field(d, "Nomination", "nominee");
        assertNotNull(nominee);
        assertTrue(nominee.reference(), "nominee -> modeled OscarNominations");
        assertEquals("OscarNominations",
                d.fieldTypes("Nomination").field("nominee").typeLabel());
    }

    @Test void unmodeledReferenceCollapsesToString() {
        List<WikidataDynamicObject> pool = pool();
        ProductDomain d = ProductCompiler.compile(model(), pool);

        DomainField forWork = field(d, "Nomination", "forWork");
        assertNotNull(forWork);
        assertFalse(forWork.reference(), "forWork -> unmodeled ForWork, a string");
        assertEquals("String", d.fieldTypes("Nomination").field("forWork").typeLabel());

        // The instance value itself was collapsed to the display name.
        WikidataDynamicObject nom = pool.stream()
                .filter(o -> "Nomination".equals(o.typeName())).findFirst().orElseThrow();
        assertEquals("Schindler's List", nom.dynamicFieldValues().get("forWork"));
    }

    @Test void cardinalityComesFromTheModel() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        DomainField target = field(d, "Nomination", "target");
        assertNotNull(target);
        assertTrue(target.collection(), "target declared COLLECTION");
        assertTrue(target.reference());
        assertEquals("List<Category>",
                d.fieldTypes("Nomination").field("target").typeLabel());
    }

    @Test void reifySourceAndWikidataAreStructural() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        assertEquals(java.util.Set.of("wikidata", "source"),
                d.structuralFields("Nomination"));

        FieldTypeSource ts = d.fieldTypes("Nomination");
        assertTrue(ts.field("source").structural());
        assertTrue(ts.field("wikidata").structural());

        // Structural fields are not offered as operation arguments.
        assertNull(field(d, "Nomination", "source"));
        assertNull(field(d, "Nomination", "wikidata"));
    }

    @Test void qidIsNeverAField() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        for (String type : d.types()) {
            for (DomainField f : d.fields(type)) {
                assertNotEquals("qid", f.field(), "qid must never surface as a field");
            }
        }
    }

    @Test void reifyForwardListIsTypedAndHidesSourceWhenNested() {
        ProductDomain d = ProductCompiler.compile(model(), pool());

        // OscarNominations gets the forward reify list `__Nomination`, typed.
        FieldTypeSource osc = d.fieldTypes("OscarNominations");
        FieldTypeSource.FieldTypeInfo link = osc.field("__Nomination");
        assertNotNull(link, "reify forward list should be a modeled field");
        assertEquals("List<Nomination>", link.typeLabel());
        assertEquals("Nomination", link.nestedClassName());
        assertFalse(link.structural());

        // Nested one level down, the Nomination's `source` back-ref is still hidden
        // (the leak that made per-component handling reappear).
        FieldTypeSource nested = link.nested();
        assertNotNull(nested);
        assertTrue(nested.field("source").structural());
        assertTrue(nested.field("wikidata").structural());
        assertEquals("List<Category>", nested.field("target").typeLabel());
    }

    @Test void nameOnlyReferenceIsAReferenceButNotExpandable() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        FieldTypeSource ts = d.fieldTypes("Nomination");

        // target -> Category (no fields beyond name): still a reference (identity
        // preserved for grouping), but no nested source -> no "+fields" expansion.
        assertTrue(field(d, "Nomination", "target").reference());
        assertNull(ts.field("target").nested(), "name-only ref offers no expansion");

        // nominee -> OscarNominations (has fields) stays expandable.
        assertNotNull(ts.field("nominee").nested(), "a fielded ref stays expandable");
    }

    @Test void booleanFieldIsAScalar() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        DomainField won = field(d, "Nomination", "won");
        assertNotNull(won);
        assertTrue(won.scalar());
        assertEquals("Boolean", d.fieldTypes("Nomination").field("won").typeLabel());
    }
}
