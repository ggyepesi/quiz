package wikidata.explore.transform;

import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.CanonicalSpec;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalizationTest {

    private GeneratedProjectModel projectWithNominationSpec() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel nomination = new GeneratedClassModel("Nomination");
        nomination.statementSourceClass("OscarNominations");
        nomination.canonical(new CanonicalSpec()
                .kind(CanonicalSpec.Kind.DERIVED)
                .displayNameMode(CanonicalSpec.DisplayNameMode.FIELD)
                .displayNameField("nominee"));
        project.addClass(nomination);
        return project;
    }

    @Test
    void reifiedAtomTakesItsNomineeAsDisplayName() {
        GeneratedProjectModel project = projectWithNominationSpec();

        WikidataDynamicObject nominee =
                new WikidataDynamicObject("Q574756", "Valerie Curtin");
        WikidataDynamicObject atom = new WikidataDynamicObject(
                "Q426517-GUID",
                "...And Justice for All — Academy Award for Best Writing");
        atom.type("Nomination");
        atom.put("nominee", nominee);

        Canonicalization.apply(project, List.of(atom), null);

        assertEquals("Valerie Curtin", atom.getDisplayName(),
                "a reified Nomination shows its nominee, not the reify heuristic name");
    }

    @Test
    void entityWithoutSpecIsUntouched() {
        GeneratedProjectModel project = projectWithNominationSpec();

        WikidataDynamicObject person =
                new WikidataDynamicObject("Q41163", "Al Pacino");
        person.type("OscarNominations");   // no canonical spec for this class

        Canonicalization.apply(project, List.of(person), null);

        assertEquals("Al Pacino", person.getDisplayName());
    }
}
