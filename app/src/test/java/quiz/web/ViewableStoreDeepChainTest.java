package quiz.web;

import objectview.Viewable;
import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The store indexes every object reachable from a served one. The walk was recursive,
 * and History's reference chains run deeper than a request thread's stack: smoke-testing
 * the web app, a person's card answered "not found" because the index build had died of
 * a StackOverflowError half way.
 */
class ViewableStoreDeepChainTest {

    private record Source(List<Viewable> items) implements ViewableSource {
        @Override public String type() { return "Holding"; }
        @Override public Collection<? extends Viewable> load() { return items; }
    }

    @Test void aChainDeeperThanTheCallStackIsIndexedWhole() throws Exception {
        Link last = null;
        for (int i = 0; i < 200_000; i++) last = new Link("holding-" + i, last);
        ViewableStore store = new ViewableStore();
        store.register(new Source(List.of(last)), "History");

        assertNotNull(store.get(new ViewableStore.Address("History", "Holding"), "holding-0"),
                "the far end of the chain is reachable, so it is indexed");
    }

    private static final class Link extends ViewableAdapter {
        private final String id;
        @SuppressWarnings("unused") private final Link replaces;

        private Link(String id, Link replaces) {
            this.id = id;
            this.replaces = replaces;
        }

        @Override public String typeName() { return "Holding"; }
        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return id; }
    }
}
