package quiz.transform.app;

import objectview.Viewable;
import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;
import quiz.transform.ui.DomainModel;
import wikidata.explore.model.FieldSourceMapping;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;
import wikidata.explore.model.RuleDirection;

import java.io.File;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Curation reads the rule its model already declares.
 *
 * <p>Promotion made the model write-only from curation's side: a domain generated with
 * {@code locations -> P840} still opened the property picker, because nothing read the
 * declaration back. The user was asked to re-enter configuration the domain was built
 * from.
 */
class DeclaredSourceSeedTest {

    private static File modelWith(String className, String field, String pid, String label)
            throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModel();
        model.name("Movies");
        GeneratedClassModel owner = new GeneratedClassModel();
        owner.className(className);
        model.rootClass(owner);
        if (pid != null) {
            var declared = owner.addField(field, wikidata.explore.model.FieldType.ENTITY,
                    wikidata.explore.model.FieldCardinality.COLLECTION);
            declared.mapping().propertyPid(pid);
            declared.mapping().propertyLabel(label);
            declared.mapping().direction(RuleDirection.ROOT_TO_ITEM);
        }
        File file = Files.createTempFile("model-seed", ".model.json").toFile();
        file.deleteOnExit();
        new GeneratedProjectModelStore().save(model, file);
        return file;
    }

    @Test void aDeclaredPropertyIsReadBackAsTheFieldsSource() throws Exception {
        File model = modelWith("Movies", "locations", "P840", "narrative location");

        FieldSourceMapping declared = new ModelFieldRulePromoter(model, new TestDomain())
                .declaredSource("Movies", "locations");

        assertNotNull(declared, "the model declares this field — curation must not re-ask");
        assertEquals("P840", declared.propertyPid());
        assertEquals("narrative location", declared.propertyLabel());
        assertEquals(RuleDirection.ROOT_TO_ITEM, declared.direction());
    }

    /** "Declared as nothing" and "not declared" are different: only the second should
     *  send the user to the property picker, and only null says so. */
    @Test void aFieldWithNoPropertyDeclaredYieldsNothing() throws Exception {
        File model = modelWith("Movies", "locations", null, null);

        assertNull(new ModelFieldRulePromoter(model, new TestDomain())
                           .declaredSource("Movies", "locations"));
    }

    @Test void anUnknownFieldOrClassYieldsNothing() throws Exception {
        File model = modelWith("Movies", "locations", "P840", "narrative location");
        ModelFieldRulePromoter promoter = new ModelFieldRulePromoter(model, new TestDomain());

        assertNull(promoter.declaredSource("Movies", "composer"));
        assertNull(promoter.declaredSource("Person", "locations"));
    }

    /** A domain with no model at all must degrade to asking, not to an error — the
     *  seed is an improvement on the old flow, never a new way for it to break. */
    @Test void aDomainWithNoModelSimplyContributesNoSeed() {
        assertNull(new ModelFieldRulePromoter(null, new TestDomain())
                           .declaredSource("Movies", "locations"));
        assertNull(new ModelFieldRulePromoter(
                new File("/nonexistent/none.model.json"), new TestDomain())
                           .declaredSource("Movies", "locations"));
    }

    /** The default on the capability keeps a non-model-backed domain unaffected. */
    @Test void aDomainThatIsNotModelBackedDeclaresNothing() {
        quiz.curation.FieldRulePromoter plain = new quiz.curation.FieldRulePromoter() {
            @Override public PromotionPreview previewPromotion(quiz.curation.Correction c) {
                return PromotionPreview.ineligible("n/a");
            }
            @Override public PromotionPreview promote(quiz.curation.Correction c) {
                return PromotionPreview.ineligible("n/a");
            }
        };

        assertNull(plain.declaredSource("Movies", "locations"));
    }

    private record TestDomain() implements DomainModel {
        @Override public List<String> types() { return List.of("Movies"); }
        @Override public objectview.field.FieldSchema fieldSchema(String type) {
            return List::of;
        }
        @Override public Collection<? extends Viewable> instances() { return List.of(); }
        @Override public Class<? extends Viewable> universe() { return Film.class; }
    }

    private static final class Film extends ViewableAdapter {
        @Override public String getIdentifier() { return "f"; }
        @Override public String getDisplayName() { return "f"; }
    }
}
