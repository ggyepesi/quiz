package wikidata.ui;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The labels of the Wikidata ids a configuration editor shows (#404) — one session-wide
 * cache, so every editor names an id the same way and asks for it once.
 *
 * <p>A label is a display aid, never configuration: nothing here is saved, and an id
 * renders as a link whether or not its label is known. The application supplies what
 * this package cannot reach on its own: {@link #seed} with the labels it already holds
 * (the local property list), and {@link #resolveWith} with the lookup that fetches the
 * rest — so the request runs through the application's query session and shows in its
 * log. Without a resolver an unknown id simply stays unlabelled.
 *
 * <p>Requests are coalesced: ids asked for within a short window go out together, in
 * batches the resolver can take, and an id is asked for at most once per session.
 */
public final class WikidataLabels {

    /** Fetches labels for up to {@link #BATCH} ids and hands back those it found. */
    @FunctionalInterface
    public interface Resolver {
        void resolve(List<String> ids, Consumer<Map<String, String>> found);
    }

    /** {@code wbgetentities} takes 50 ids per call. */
    static final int BATCH = 50;

    private static final Map<String, String> labels = new ConcurrentHashMap<>();
    private static final Set<String> requested = ConcurrentHashMap.newKeySet();
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();
    private static final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private static volatile Resolver resolver;
    private static Timer flush;

    private WikidataLabels() {
    }

    /** Labels the application already holds — they need no request. */
    public static void seed(Map<String, String> known) {
        if (known == null || known.isEmpty()) return;
        known.forEach((id, label) -> {
            if (WikidataLinks.isId(id) && label != null && !label.isBlank()) {
                labels.put(id.trim(), label);
                requested.add(id.trim());
            }
        });
        changed();
    }

    public static void resolveWith(Resolver lookup) {
        resolver = lookup;
    }

    /** The label of {@code id}, or null while it is unknown. */
    public static String label(String id) {
        return id == null ? null : labels.get(id.trim());
    }

    /** Ask for the labels of {@code ids} not yet known or asked for. */
    public static void request(Collection<String> ids) {
        if (ids == null || resolver == null) return;
        boolean added = false;
        for (String id : ids) {
            String clean = id == null ? "" : id.trim();
            if (WikidataLinks.isId(clean) && !labels.containsKey(clean)
                    && requested.add(clean)) {
                pending.add(clean);
                added = true;
            }
        }
        if (added) SwingUtilities.invokeLater(WikidataLabels::scheduleFlush);
    }

    /** Called on the event thread whenever new labels arrive. */
    public static void onChange(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeOnChange(Runnable listener) {
        listeners.remove(listener);
    }

    private static void scheduleFlush() {
        if (flush == null) {
            flush = new Timer(150, event -> flushPending());
            flush.setRepeats(false);
        }
        flush.restart();
    }

    static void flushPending() {
        Resolver lookup = resolver;
        List<String> ids = new ArrayList<>(pending);
        pending.removeAll(ids);
        if (lookup == null || ids.isEmpty()) return;
        for (int from = 0; from < ids.size(); from += BATCH) {
            List<String> batch = List.copyOf(ids.subList(from,
                    Math.min(ids.size(), from + BATCH)));
            lookup.resolve(batch, found -> {
                if (found == null || found.isEmpty()) return;
                found.forEach((id, label) -> {
                    if (id != null && label != null && !label.isBlank()) labels.put(id, label);
                });
                changed();
            });
        }
    }

    /** Forget everything — for tests, which share this session-wide state. */
    static void reset() {
        labels.clear();
        requested.clear();
        pending.clear();
        listeners.clear();
        resolver = null;
    }

    private static void changed() {
        if (SwingUtilities.isEventDispatchThread()) {
            listeners.forEach(Runnable::run);
        } else {
            SwingUtilities.invokeLater(() -> listeners.forEach(Runnable::run));
        }
    }
}
