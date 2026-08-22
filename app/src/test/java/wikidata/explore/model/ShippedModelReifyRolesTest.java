package wikidata.explore.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import wikidata.explore.transform.ModelStatementReifications;
import wikidata.explore.transform.ReifyConstruct;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reify roles the CHECKED-IN models actually produce.
 *
 * <p>Every other reify test builds its {@link ReifyConstruct.Role}s by hand, so
 * {@code fallbackRoles} — the code that reads those roles OFF a model — was exercised by
 * nothing. Flipping the default for an omitted missing-qualifier policy from
 * STATEMENT_SUBJECT to MISSING therefore left the whole suite green while silently
 * reducing the shipped Oscars model from two subject-fallback roles to none.
 *
 * <p>That is not a small difference. {@code dedupBy} is ⟨category, forWork, nominee,
 * ceremony⟩, and neither endpoint carries both qualifiers: a person's statement names the
 * work but not the nominee (she IS the subject), a work's statement names the nominee but
 * not the work. Each side depends on the fallback for the half it lacks. Without the
 * roles the two keys differ, the duplicate never collapses, and every shared nomination
 * is served twice with a hole in it — while the self-referential phantom drop, which
 * looks for records whose role fields ALL fell back to the subject, stops firing at all.
 *
 * <p>So this test asserts the derived roles rather than the policy fields: it is the
 * behaviour that matters, and it fails on a default change, a migration that missed a
 * field, or an edit that clears one.
 */
class ShippedModelReifyRolesTest {

    private static final File OSCARS =
            new File("../data/wikidata/oscarnominations/oscarnominations.model.json");

    static boolean oscarsModelPresent() {
        return OSCARS.isFile();
    }

    @EnabledIf("oscarsModelPresent")
    @Test void theOscarsNominationKeepsBothSubjectFallbacks() throws Exception {
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(OSCARS);

        ReifyConstruct reify = nominationReify(model);
        Map<String, ReifyConstruct.Role> byField = reify.roles().stream()
                .collect(Collectors.toMap(ReifyConstruct.Role::field, role -> role));

        assertTrue(byField.containsKey("nominee"),
                "nominee resolves to the subject when the statement carries no P2453 — "
                        + "without it a person-rooted nomination has no nominee at all. "
                        + "Roles derived: " + byField.keySet());
        assertTrue(byField.get("nominee").fallbackToSource());
        assertEquals(RoleKind.IDENTITY, byField.get("nominee").kind(),
                "the nominee IS the subject, which is what keeps the phantom drop from "
                        + "treating a witness that merely names her as a duplicate");

        assertTrue(byField.containsKey("forWork"),
                "forWork resolves to the subject when the statement carries no P1686 — "
                        + "this is the half a work-rooted copy depends on. Roles derived: "
                        + byField.keySet());
        assertTrue(byField.get("forWork").fallbackToSource());
        assertEquals(RoleKind.REFERENCE, byField.get("forWork").kind());
    }

    @EnabledIf("oscarsModelPresent")
    @Test void theCeremonyDeliberatelyHasNoFallback() throws Exception {
        // #95: an absent P805 must stay absent, or the film becomes its own ceremony and
        // the field expectation can never see the gap.
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(OSCARS);

        List<String> fields = nominationReify(model).roles().stream()
                .map(ReifyConstruct.Role::field).toList();

        assertTrue(!fields.contains("ceremony"),
                "ceremony must have no fallback role: " + fields);
    }

    @EnabledIf("oscarsModelPresent")
    @Test void everyStatementFieldStatesItsPolicyRatherThanInheritingOne() throws Exception {
        // The migration's own guard. A field left unspecified takes whatever the default
        // happens to be that month, which is exactly how this broke.
        GeneratedProjectModel model = new GeneratedProjectModelStore().load(OSCARS);

        for (GeneratedClassModel c : allClasses(model)) {
            if (c.statementSource() == null) continue;
            for (GeneratedFieldModel f : c.fields()) {
                if (!StatementFieldSemantics.supportsMissingQualifierPolicy(c, f)) continue;
                assertNotNull(f.mapping().missingQualifierPolicy(),
                        c.className() + "." + f.name() + " has no explicit "
                                + "missing-qualifier policy, so it silently follows the "
                                + "current default");
            }
        }
    }

    @Test void theValidatorNamesAFieldThatStatesNoPolicy() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("probe");
        project.rootClass(new GeneratedClassModel("Root"));

        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("Root", "P1411"));
        GeneratedFieldModel nominee =
                nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nominee.mapping().qualifierPid("P2453");          // no policy stated
        project.addClass(nomination);

        String report = GeneratedProjectModelValidator.validate(project).format();

        assertTrue(report.contains("No missing-qualifier policy"),
                "an unstated policy must be visible before it silently follows a "
                        + "default that can change: " + report);
    }

    @Test void theValidatorIsQuietOnceThePolicyIsStated() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        project.name("probe");
        project.rootClass(new GeneratedClassModel("Root"));

        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSource(new StatementClassSource("Root", "P1411"));
        GeneratedFieldModel nominee =
                nomination.addField("nominee", FieldType.ENTITY, FieldCardinality.SINGLE);
        nominee.mapping().qualifierPid("P2453");
        nominee.mapping().missingQualifierPolicy(MissingQualifierPolicy.STATEMENT_SUBJECT);
        project.addClass(nomination);

        assertTrue(!GeneratedProjectModelValidator.validate(project).format()
                        .contains("No missing-qualifier policy"),
                "stating it — with any of the three answers — is what the warning asks for");
    }

    private static List<GeneratedClassModel> allClasses(GeneratedProjectModel model) {
        // rootClass and the same-named entry in classes() deserialize as DISTINCT
        // instances, so a migration that touched only one would leave the other behind.
        List<GeneratedClassModel> all = new java.util.ArrayList<>(model.classes());
        if (model.rootClass() != null) {
            all.add(model.rootClass());
        }
        return all;
    }

    private static ReifyConstruct nominationReify(GeneratedProjectModel model) {
        for (GeneratedClassModel c : model.classes()) {
            if (!"Nomination".equals(c.className())) continue;
            ModelStatementReifications.Reification r =
                    ModelStatementReifications.deriveOne(c, model);
            assertNotNull(r, "Nomination must still derive a reification");
            return r.reify();
        }
        throw new AssertionError("the Oscars model no longer has a Nomination class");
    }
}
