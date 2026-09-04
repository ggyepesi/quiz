package wikidata.explore.generation;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.GeneratedProjectModelStore;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * "Has this project changed?" is answered by the fingerprint that already answers it.
 *
 * <p>The workbench asked before every domain switch whether to discard unsaved changes,
 * whether or not there were any — and a dialog that always appears is one a reader learns
 * to dismiss without reading, which is exactly when it needed to be read. The alternative
 * to a flag set by every edit path (there are a dozen, and a new one arrives with every
 * control) is the signature the staleness guard already computes. These are the
 * properties that answer has to have to be used for this.
 */
class UnchangedModelSignsTheSameTest {

    private static GeneratedProjectModel nobel() throws Exception {
        return new GeneratedProjectModelStore().load(
                new File("../data/wikidata/nobelprizes/nobelprizes.model.json"));
    }

    /** Loading and re-signing an untouched model says unchanged. */
    @Test void aModelJustLoadedSignsAsItsFile() throws Exception {
        GeneratedProjectModel project = nobel();

        String atLoad = DomainSave.signature(project);
        assertFalse(atLoad.isBlank(), "a valid model has a fingerprint");
        assertEquals(atLoad, DomainSave.signature(project),
                "asking twice is not a change");
    }

    /**
     * Asking must not BE a change.
     *
     * <p>The check runs on every switch, so if computing it touched the live model the
     * dialog would report changes it had caused itself. DomainSave signs a copy; this is
     * that guarantee held, since serializing does mutate what it is given.
     */
    @Test void signingDoesNotTouchTheModel() throws Exception {
        GeneratedProjectModel project = nobel();
        String before = new GeneratedProjectModelStore().toJson(project);

        DomainSave.signature(project);

        assertEquals(before, new GeneratedProjectModelStore().toJson(project),
                "an inspection that edits is not an inspection");
    }

    /** A real edit changes it. */
    @Test void anEditChangesTheFingerprint() throws Exception {
        GeneratedProjectModel project = nobel();
        String before = DomainSave.signature(project);

        project.findClass("NobelPrize").canonical().keyFields().add("motivation");

        assertNotEquals(before, DomainSave.signature(project),
                "a changed key is a changed model");
    }

    /**
     * An invalid model has no fingerprint, and that is not a claim that nothing changed.
     *
     * <p>Mid-edit a model is often uncompilable, so the check has to say "I cannot tell"
     * rather than "unchanged" — the caller then asks, which is the safe direction: the
     * cost of asking is a dialog, the cost of not asking is lost work.
     */
    @Test void anUncompilableModelHasNoFingerprintToCompare() {
        GeneratedProjectModel broken = new GeneratedProjectModel();
        wikidata.explore.model.GeneratedClassModel alpha =
                new wikidata.explore.model.GeneratedClassModel("Alpha");
        wikidata.explore.model.GeneratedClassModel beta =
                new wikidata.explore.model.GeneratedClassModel("Beta");
        alpha.baseClassName("Beta");
        beta.baseClassName("Alpha");
        broken.addClass(alpha);
        broken.addClass(beta);

        assertTrue(DomainSave.signature(broken).isBlank(),
                "no claim, rather than a false claim of no change");
    }
}
