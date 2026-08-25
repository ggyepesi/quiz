package wikidata.explore.workbench;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Where a browser has been.
 *
 * <p>Entries are recorded where a navigation <em>arrives</em>, never where it is
 * intended. Loads are asynchronous and can fail, and recording on intent produced two
 * bugs at once: a failed navigation left the abandoned category on the stack while the
 * browser still showed the old one, so Back "returned" to the page already displayed;
 * and a destination typed into the field rather than clicked was never recorded, so
 * Back from it skipped a step.
 *
 * <p>Arrival is the one event both kinds of navigation share, which is why one rule
 * covers both. Going back is an intent like any other — the entry is popped when the
 * load lands, so a failed Back leaves the stack untouched.
 */
final class BrowseHistory {

    private final Deque<String> entries = new ArrayDeque<>();
    private String current = "";
    private boolean returning;

    /** The destination now being loaded came from {@link #back()}. */
    void goingBack() {
        returning = true;
    }

    /** A fresh destination is being loaded — clicked, typed, or re-entered. */
    void goingForward() {
        returning = false;
    }

    /** The in-flight load failed, so nothing arrived and nothing moves. */
    void abandoned() {
        returning = false;
    }

    boolean canGoBack() {
        return !entries.isEmpty();
    }

    /** The entry Back would return to, without committing to it. Null when empty. */
    String back() {
        return entries.peek();
    }

    String current() {
        return current;
    }

    /** A load landed on {@code category}: the only place the stack moves. */
    void arrived(String category) {
        String destination = category == null ? "" : category;
        if (returning) {
            if (!entries.isEmpty()) {
                entries.pop();
            }
        } else if (!current.isBlank() && !current.equals(destination)) {
            // Re-loading the category already shown is not a step, so Back does not
            // fill up with copies of it.
            entries.push(current);
        }
        returning = false;
        current = destination;
    }
}
