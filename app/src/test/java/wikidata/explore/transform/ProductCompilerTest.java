package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import quiz.transform.app.ProductDomain;
import domain.DomainField;
import quiz.curation.Correction;
import quiz.curation.Corrections;
import objectview.field.FieldPath;
import objectview.viewconfig.FieldTypeSource;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import domain.DomainModel;

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
        nom.statementSource(new wikidata.explore.model.StatementClassSource(
                "OscarNominations", "P1411"));   // -> reify `source` back-ref
        ref(nom, "nominee", "OscarNominations", FieldCardinality.AUTO);
        ref(nom, "forWork", "ForWork", FieldCardinality.AUTO);      // ForWork is UNMODELED
        ref(nom, "target", "Category", FieldCardinality.COLLECTION);
        // presenter targets a MODELED member class, but its referent won't be a member.
        ref(nom, "presenter", "OscarNominations", FieldCardinality.AUTO);
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
        osc.put("wikidata", "http://www.wikidata.org/entity/Q1");   // a real entity
        // P31 `type` with a real value plus Wikimedia-meta noise to be filtered.
        osc.put("type", new java.util.ArrayList<>(List.of(
                new WikidataDynamicObject("Q11424", "film"),
                new WikidataDynamicObject("Q13406463", "Wikimedia list article"))));

        WikidataDynamicObject film = substantive("Q2", "Schindler's List");   // ForWork

        // Category is stamped but identity-only (only the wikidata link) — still a
        // member, and now the link makes it a fielded (expandable) reference.
        WikidataDynamicObject cat = new WikidataDynamicObject("Q3", "Best Picture");
        cat.type("Category");
        cat.put("wikidata", "http://www.wikidata.org/entity/Q3");

        WikidataDynamicObject nom = new WikidataDynamicObject("Q4-abc", "Nomination 1");
        nom.type("Nomination");         // a reified statement — no wikidata link
        nom.put("nominee", osc);
        nom.put("forWork", film);
        nom.put("target", new java.util.ArrayList<>(List.of(cat)));
        // presenter's referent is an unstamped person — NOT a member of any class.
        nom.put("presenter", new WikidataDynamicObject("Q900", "A Presenter"));
        nom.put("won", Boolean.TRUE);
        nom.put("source", osc);   // the reify back-ref

        osc.put("__Nomination", new java.util.ArrayList<>(List.of(nom)));  // reify forward list

        return new java.util.ArrayList<>(List.of(osc, cat, nom));
    }

    private static DomainField field(ProductDomain d, String type, String path) {
        for (DomainField f : d.fields(type)) {
            if (f.field().equals(path)) {
                return f;
            }
        }
        return null;
    }

    @Test void declaredStampedClassesAreMembersIncludingIdentityOnly() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        // Category IS stamped (identity-only) -> a member you can select/group by.
        // Type is never stamped -> stays a reference-only label, not a member.
        assertEquals(List.of("OscarNominations", "Category", "Nomination"), d.types());
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
        assertTrue(d.entityOrigin("Nomination", FieldPath.parse("forWork")),
                "collapsed String retains its entity-field provenance");
    }

    /**
     * The panel is never handed the compiled domain directly — the workbench wraps it in
     * a WorkingDomain (base + derived classes). A wrapper that does not FORWARD this
     * question inherits the interface default, which answers from the runtime FieldRef;
     * a collapsed reference is no longer one, so the default says "not an entity field"
     * for exactly the fields that were, and every Unnamed count reads zero.
     */
    @Test void entityProvenanceSurvivesTheWorkingDomainWrapper() {
        ProductDomain compiled = ProductCompiler.compile(model(), pool());
        DomainModel working =
                new quiz.transform.ui.WorkingDomain(compiled);

        assertTrue(working.entityOrigin("Nomination", FieldPath.parse("forWork")),
                "a wrapper that drops this reports zero unnamed references everywhere");
        assertFalse(working.entityOrigin("Nomination", FieldPath.parse("won")));
    }

    @Test void ordinaryScalarDoesNotAcquireEntityProvenance() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        assertFalse(d.entityOrigin("Nomination", FieldPath.parse("won")));
    }

    @Test void qidScopedLabelRepairSurvivesCollapseOnTheNextLoad() {
        List<WikidataDynamicObject> first = unresolvedWorkPool();
        ProductCompiler.compile(model(), first);
        assertEquals("Q95709545", nomination(first).get("forWork"));

        // A new load starts from the snapshot graph again. Curation applies before
        // ProductCompiler, so collapse now sees the repaired entity display label.
        List<WikidataDynamicObject> reloaded = unresolvedWorkPool();
        Corrections.apply(reloaded, List.of(() -> List.of(
                Correction.entityLabel("Q95709545", "Mary Ramos", "wikidata"))));
        ProductCompiler.compile(model(), reloaded);

        assertEquals("Mary Ramos", nomination(reloaded).get("forWork"));
    }

    private static List<WikidataDynamicObject> unresolvedWorkPool() {
        WikidataDynamicObject work =
                new WikidataDynamicObject("Q95709545", "Q95709545");
        WikidataDynamicObject nomination =
                new WikidataDynamicObject("Q4-test", "Nomination");
        nomination.type("Nomination");
        nomination.put("forWork", work);
        return new java.util.ArrayList<>(List.of(nomination, work));
    }

    private static WikidataDynamicObject nomination(List<WikidataDynamicObject> pool) {
        return pool.stream().filter(o -> "Nomination".equals(o.typeName()))
                .findFirst().orElseThrow();
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

    @Test void sourceIsStructuralWikidataIsALinkOnEntities() {
        ProductDomain d = ProductCompiler.compile(model(), pool());

        // The reify `source` back-ref is the only structural field — plumbing.
        assertEquals(java.util.Set.of("source"), d.structuralFields("Nomination"));
        assertTrue(d.fieldTypes("Nomination").field("source").structural());
        assertNull(field(d, "Nomination", "source"), "not an operation argument");

        // wikidata is a first-class link on a real entity (not structural) — and a
        // statement class (Nomination) has none.
        FieldTypeSource.FieldTypeInfo link = d.fieldTypes("OscarNominations").field("Wikidata");
        assertNotNull(link, "a real entity keeps its Wikidata link");
        assertFalse(link.structural());
        assertEquals("Link", link.typeLabel());
        assertNull(d.fieldTypes("Nomination").field("Wikidata"),
                "a reified statement has no Wikidata page");
    }

    @Test void qidIsNeverAField() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        for (String type : d.types()) {
            for (DomainField f : d.fields(type)) {
                assertNotEquals("qid", f.field(), "qid must never surface as a field");
            }
        }
    }

    @Test void reifyForwardListIsDropped() {
        List<WikidataDynamicObject> pool = pool();
        ProductDomain d = ProductCompiler.compile(model(), pool);
        WikidataDynamicObject osc = pool.stream()
                .filter(o -> "OscarNominations".equals(o.typeName())).findFirst().orElseThrow();
        // One-directional (Constellation/Star): no auto-materialized inverse — not on
        // the instances, not in the schema.
        assertFalse(osc.dynamicFieldValues().containsKey("__Nomination"));
        assertFalse(osc.dynamicFieldValues().containsKey("nomination"));
        assertNull(d.fieldTypes("OscarNominations").field("nomination"));
    }

    @Test void nonMemberReferenceCollapsesToString() {
        List<WikidataDynamicObject> pool = pool();
        ProductDomain d = ProductCompiler.compile(model(), pool);
        // presenter targets the MODELED class OscarNominations, but its referent is an
        // unstamped person (not a member) — so it reads as a name, never a raw WDO.
        assertFalse(field(d, "Nomination", "presenter").reference());
        WikidataDynamicObject nom = pool.stream()
                .filter(o -> "Nomination".equals(o.typeName())).findFirst().orElseThrow();
        assertEquals("A Presenter", nom.dynamicFieldValues().get("presenter"));
    }

    @Test void referenceToAnEntityIsExpandableViaItsLink() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        FieldTypeSource ts = d.fieldTypes("Nomination");

        // target -> Category: a reference, and now expandable — Category is a real
        // entity, so it carries a Wikidata link (no longer a dead-end lone `name`).
        assertTrue(field(d, "Nomination", "target").reference());
        assertNotNull(ts.field("target").nested());
        assertNotNull(ts.field("target").nested().field("Wikidata"));

        // nominee -> OscarNominations (fielded) stays expandable.
        assertNotNull(ts.field("nominee").nested());
    }

    @Test void sourceStrippedWikidataRenamedNoiseFiltered() {
        List<WikidataDynamicObject> pool = pool();
        ProductCompiler.compile(model(), pool);
        WikidataDynamicObject osc = pool.stream()
                .filter(o -> "OscarNominations".equals(o.typeName())).findFirst().orElseThrow();

        // source stripped everywhere; the seeded `wikidata` key renamed to `Wikidata`.
        for (WikidataDynamicObject o : pool) {
            assertFalse(o.dynamicFieldValues().containsKey("source"), o.getDisplayName());
            assertFalse(o.dynamicFieldValues().containsKey("wikidata"), o.getDisplayName());
        }
        assertTrue(osc.dynamicFieldValues().containsKey("Wikidata"), "entity keeps the link");

        // Wikimedia-meta noise dropped from `type`; the real value survives.
        assertEquals("film", osc.dynamicFieldValues().get("type"));
    }

    @Test void instanceFieldsFollowTheClassOrderRegardlessOfInsertion() {
        WikidataDynamicObject cat = new WikidataDynamicObject("Q3", "Best Picture");
        cat.type("Category");
        WikidataDynamicObject nom = new WikidataDynamicObject("Q9-x", "N");
        nom.type("Nomination");
        // Inserted in REVERSE of the model order (nominee before won there).
        nom.put("won", Boolean.TRUE);
        nom.put("target", new java.util.ArrayList<>(List.of(cat)));
        nom.put("nominee", cat);

        List<WikidataDynamicObject> pool = new java.util.ArrayList<>(List.of(cat, nom));
        ProductCompiler.compile(model(), pool);

        List<String> order = new java.util.ArrayList<>(nom.dynamicFieldValues().keySet());
        assertTrue(order.indexOf("nominee") < order.indexOf("won"), order.toString());
        assertTrue(order.indexOf("nominee") < order.indexOf("target"), order.toString());
    }

    @Test void booleanFieldIsAScalar() {
        ProductDomain d = ProductCompiler.compile(model(), pool());
        DomainField won = field(d, "Nomination", "won");
        assertNotNull(won);
        assertTrue(won.scalar());
        assertEquals("Boolean", d.fieldTypes("Nomination").field("won").typeLabel());
    }
}
