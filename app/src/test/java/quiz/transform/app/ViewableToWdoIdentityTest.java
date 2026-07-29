package quiz.transform.app;

import org.junit.jupiter.api.Test;
import objectview.ViewableAdapter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The blank-identity guard: {@link ViewableToWdo#pool} must FAIL LOUD when a reachable
 * entity has a blank identity, because the qid-keyed snapshot store silently drops
 * such entities (their refs serialize as null — the bug that ate Nobel's laureates).
 */
class ViewableToWdoIdentityTest {

    /** A nested entity that forgot to define an identity. */
    static final class Blank extends ViewableAdapter {
        @Override public String getIdentifier() { return ""; }
        @Override public String getDisplayName() { return ""; }
    }

    static final class WithBlankChild extends ViewableAdapter {
        private final Blank child = new Blank();
        @Override public String getIdentifier() { return "R1"; }
        @Override public String getDisplayName() { return "Root"; }
    }

    static final class Named extends ViewableAdapter {
        @Override public String getIdentifier() { return "N1"; }
        @Override public String getDisplayName() { return "N1"; }
    }

    static final class WithNamedChild extends ViewableAdapter {
        private final Named child = new Named();
        @Override public String getIdentifier() { return "R2"; }
        @Override public String getDisplayName() { return "Root2"; }
    }

    @Test void blankIdentityFailsLoud() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ViewableToWdo.pool(List.of(new WithBlankChild())));
        assertTrue(ex.getMessage().contains("BLANK identity"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Blank"), ex.getMessage()); // the offending type
    }

    @Test void properIdentitiesSucceed() {
        assertEquals(1, ViewableToWdo.pool(List.of(new WithNamedChild())).size());
    }
}
