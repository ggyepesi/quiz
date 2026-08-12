package quiz.curation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-local staging area for edits awaiting the user's explicit Apply action.
 *
 * <p>The important boundary is that pending records live here, not in
 * {@link ManualCuration}. Consequently a save performed by another modeless tool cannot
 * accidentally persist them. Sessions are shared per sidecar, so two open curation panels
 * see one pending count and one transaction rather than competing private buffers.</p>
 */
public final class CurationStaging implements CorrectionSource {

    private static final Map<ManualCuration, CurationStaging> SESSIONS =
            new IdentityHashMap<>();

    private final ManualCuration target;
    private final Map<CorrectionKey, Correction> corrections = new LinkedHashMap<>();
    private final Map<IdentityKey, IdentityLink> identities = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private CurationStaging(ManualCuration target) {
        this.target = target;
    }

    public static synchronized CurationStaging forCuration(ManualCuration target) {
        if (target == null) return null;
        return SESSIONS.computeIfAbsent(target, CurationStaging::new);
    }

    public synchronized void stage(Correction correction) {
        if (correction == null) return;
        corrections.put(new CorrectionKey(
                correction.type(), correction.qid(), correction.field()), correction);
        changed();
    }

    public synchronized void stage(IdentityLink link) {
        if (link == null) return;
        identities.put(new IdentityKey(
                link.type(), link.targetId(), link.sourceKind()), link);
        changed();
    }

    @Override public synchronized List<Correction> corrections() {
        return List.copyOf(corrections.values());
    }

    public synchronized List<IdentityLink> identityLinks() {
        return List.copyOf(identities.values());
    }

    /** Discard only pending identity work, leaving staged field corrections intact. */
    public synchronized void discardIdentityLinks() {
        if (identities.isEmpty()) return;
        identities.clear();
        changed();
        releaseIfUnused();
    }

    public synchronized int size() {
        return corrections.size() + identities.size();
    }

    public synchronized void addChangeListener(Runnable listener) {
        if (listener != null && !listeners.contains(listener)) listeners.add(listener);
    }

    public synchronized void removeChangeListener(Runnable listener) {
        listeners.remove(listener);
        releaseIfUnused();
    }

    /**
     * Merge the pending records into the durable model and save once. If saving fails,
     * restore every affected durable entry exactly, leaving the staging session intact so
     * the user can retry.
     */
    public synchronized void apply() throws IOException {
        if (size() == 0) return;

        List<Correction> previousCorrections = target.corrections();
        List<IdentityLink> previousIdentities = target.identityLinks();
        try {
            for (Correction correction : corrections.values()) {
                target.put(correction.type(), correction.qid(), correction.field(),
                        correction.value(), correction.origin(), correction.valueKind(),
                        correction.policy(), correction.source());
            }
            for (IdentityLink link : identities.values()) target.putIdentityLink(link);
            target.save();
        } catch (IOException | RuntimeException failure) {
            restore(previousCorrections, previousIdentities);
            throw failure;
        }

        corrections.clear();
        identities.clear();
        changed();
        releaseIfUnused();
    }

    /** Persist only pending identity links. An identity review must not commit unrelated
     *  field corrections staged by another modeless curation surface. */
    public synchronized void applyIdentityLinks() throws IOException {
        if (identities.isEmpty()) return;
        List<IdentityLink> previous = target.identityLinks();
        try {
            for (IdentityLink link : identities.values()) target.putIdentityLink(link);
            target.save();
        } catch (IOException | RuntimeException failure) {
            for (IdentityLink pending : identities.values()) {
                target.removeIdentityLink(
                        pending.type(), pending.targetId(), pending.sourceKind());
                previous.stream().filter(old -> sameIdentityKey(old, pending))
                        .forEach(target::putIdentityLink);
            }
            throw failure;
        }
        identities.clear();
        changed();
        releaseIfUnused();
    }

    private void restore(List<Correction> previousCorrections,
                         List<IdentityLink> previousIdentities) {
        // Only touched keys need rollback; unrelated records may have been added by another
        // tool between staging and Apply and must not be erased.
        for (Correction pending : corrections.values()) {
            target.remove(pending.type(), pending.qid(), pending.field());
            previousCorrections.stream()
                    .filter(old -> sameCorrectionKey(old, pending))
                    .forEach(target::restore);
        }
        for (IdentityLink pending : identities.values()) {
            target.removeIdentityLink(
                    pending.type(), pending.targetId(), pending.sourceKind());
            previousIdentities.stream()
                    .filter(old -> sameIdentityKey(old, pending))
                    .forEach(target::putIdentityLink);
        }
    }

    private void changed() {
        // Copy because a listener may detach itself while responding.
        List.copyOf(listeners).forEach(Runnable::run);
    }

    private void releaseIfUnused() {
        if (!corrections.isEmpty() || !identities.isEmpty() || !listeners.isEmpty()) return;
        synchronized (CurationStaging.class) {
            SESSIONS.remove(target, this);
        }
    }

    private static boolean sameCorrectionKey(Correction a, Correction b) {
        // ManualCuration.put treats an unqualified legacy entry as the same slot as a
        // fresh type-qualified correction; rollback must mirror that replacement rule.
        return (a.type() == null || java.util.Objects.equals(a.type(), b.type()))
                && java.util.Objects.equals(a.qid(), b.qid())
                && java.util.Objects.equals(a.field(), b.field());
    }

    private static boolean sameIdentityKey(IdentityLink a, IdentityLink b) {
        return java.util.Objects.equals(a.type(), b.type())
                && java.util.Objects.equals(a.targetId(), b.targetId())
                && java.util.Objects.equals(a.sourceKind(), b.sourceKind());
    }

    private record CorrectionKey(String type, String id, String field) { }
    private record IdentityKey(String type, String id, String sourceKind) { }
}
