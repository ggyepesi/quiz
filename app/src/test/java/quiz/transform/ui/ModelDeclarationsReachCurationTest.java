package quiz.transform.ui;

import objectview.Viewable;
import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityKindRule;
import wikidata.explore.model.WikipediaCategoryRule;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * What the producing model declares must survive the working layer.
 *
 * <p>{@link WorkingDomain} wraps a base domain to layer PROJECT-derived classes over it,
 * and Java has no automatic delegation: a {@link DomainModel} method it does not override
 * silently becomes the interface default. The category recipe went that way — the model
 * declared a rule, curation asked the working domain, and got the default null, so the
 * rule was invisible the moment a derived class existed. Kind rules would have followed it
 * the same day. Both are asked THROUGH the working domain, so both are tested through it.
 */
class ModelDeclarationsReachCurationTest {

    @Test void aCategoryRecipeDeclaredByTheModelSurvivesTheWorkingLayer() {
        WorkingDomain working = new WorkingDomain(new DeclaringDomain());

        WikipediaCategoryRule rule = working.wikipediaCategoryRule("Movie", "location");

        assertNotNull(rule, "the working layer must not answer for the model");
        assertEquals("Films set in <value>", rule.pattern());
    }

    @Test void theKindRuleThatDecidesWhatAClassMeansSurvivesTheWorkingLayer() {
        WorkingDomain working = new WorkingDomain(new DeclaringDomain());

        EntityKindRule kind = working.entityKindRule("Location");

        assertNotNull(kind, "without this a category candidate is checked against nothing");
        assertEquals(List.of("Q6256"), kind.evidenceQids());
        assertEquals("P31", kind.propertyPid());
    }

    /**
     * The same hole, in the OTHER wrapper. A group-scoped view projects a restricted schema
     * over the domain, and it forwarded the eleven methods whoever wrote it thought of. The
     * category recipe and the kind rule were not among them, so selecting a group would have
     * silenced both — the identical failure to the one above, waiting for the first curation
     * flow to ask through a selected group.
     */
    @Test void aGroupScopedViewDoesNotAnswerForTheDomainItProjects() {
        DomainModel domain = new WorkingDomain(new DeclaringDomain());
        TypeSpecDomainView scoped = new TypeSpecDomainView(
                domain, new quiz.transform.TypeSpec("Movie", java.util.Map.of()));

        assertNotNull(scoped.wikipediaCategoryRule("Movie", "location"),
                "projecting a schema says nothing about what a category means");
        assertEquals("Films set in <value>",
                scoped.wikipediaCategoryRule("Movie", "location").pattern());
        assertNotNull(scoped.entityKindRule("Location"),
                "nor about what a class means");
        assertEquals(List.of("Q6256"), scoped.entityKindRule("Location").evidenceQids());
    }

    /** …and it still projects: forwarding the declarations must not flatten the wrapper
     *  back into its base. */
    @Test void theProjectionItselfSurvivesTheForwarding() {
        TypeSpecDomainView scoped = new TypeSpecDomainView(
                new DeclaringDomain(), new quiz.transform.TypeSpec("Movie", java.util.Map.of()));

        assertEquals(List.of("Movie"), scoped.types());
        assertNotNull(scoped.fieldSchema("Movie"), "the view answers for its own schema");
    }

    @Test void aClassTheModelSaysNothingAboutStaysNull() {
        assertEquals(null, new WorkingDomain(new DeclaringDomain()).entityKindRule("Award"));
    }

    /** A base that declares exactly what a producing model declares, and nothing else. */
    private static final class DeclaringDomain implements DomainModel {
        @Override public WikipediaCategoryRule wikipediaCategoryRule(String type, String field) {
            if (!"Movie".equals(type) || !"location".equals(field)) return null;
            WikipediaCategoryRule rule = new WikipediaCategoryRule();
            rule.pattern("Films set in <value>");
            return rule;
        }

        @Override public EntityKindRule entityKindRule(String className) {
            return "Location".equals(className)
                    ? new EntityKindRule("Location", List.of("Q6256")) : null;
        }

        @Override public List<String> types() { return List.of("Movie"); }
        @Override public List<String> servedTypes() { return List.of("Movie"); }
        @Override public java.util.Collection<? extends Viewable> instances() {
            return List.of();
        }
        @Override public Class<? extends Viewable> universe() { return Viewable.class; }
        @Override public objectview.field.FieldSchema fieldSchema(String type) {
            return List::of;
        }
    }
}
