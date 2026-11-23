package domain.curation;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Process-local transaction buffer for an action that produces several curation records.
 *
 * <p>The important boundary is that pending records live here, not in
 * {@link ManualCuration}. Consequently a save performed by another modeless tool cannot
 * accidentally persist them. Sessions are shared per sidecar, so two open curation panels
 * see one transaction rather than competing private buffers. This is internal machinery:
 * an accepting user action normally stages and applies it before returning.</p>
 */
public final class CurationStaging implements CorrectionSource {

    private static final Map<ManualCuration, CurationStaging> SESSIONS =
            new IdentityHashMap<>();

    private final ManualCuration target;
    private final Map<CorrectionKey, Correction> corrections = new LinkedHashMap<>();
    private final Map<IdentityKey, IdentityLink> identities = new LinkedHashMap<>();
    private final Map<InstanceKey, CuratedInstance> instances = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private CurationStaging(ManualCuration target) {
        this.target = target;
    }

    public static synchronized CurationStaging forCuration(ManualCuration target) {
        if (target == null) return null;
        return SESSIONS.computeIfAbsent(target, CurationStaging::new);
    }

    /** Pending work for an already-open curation transaction, without creating a
     * transaction merely because another action is checking whether it may save. */
    public static synchronized int pendingCount(ManualCuration target) {
        CurationStaging staging = target == null ? null : SESSIONS.get(target);
        return staging == null ? 0 : staging.size();
    }

    public synchronized void stage(Correction correction) {
        if (correction == null) return;
        corrections.entrySet().removeIf(entry ->
                correction.replacesExisting(entry.getValue()));
        corrections.put(key(correction), correction);
        changed();
    }

    private static CorrectionKey key(Correction correction) {
        return new CorrectionKey(
                correction.type(), correction.qid(), correction.field(),
                correction.additive() ? new AdditiveContribution(
                        correction.value(), correction.valueKind(),
                        correction.referenceType()) : null);
    }

    private static IdentityKey key(IdentityLink link) {
        return new IdentityKey(link.type(), link.targetId(), link.sourceKind());
    }

    private static InstanceKey key(CuratedInstance instance) {
        return new InstanceKey(instance.type(), instance.id());
    }

    public synchronized void stage(IdentityLink link) {
        if (link == null) return;
        identities.put(key(link), link);
        changed();
    }

    public synchronized void stage(CuratedInstance instance) {
        if (instance == null) return;
        instances.put(key(instance), instance);
        changed();
    }

    @Override public synchronized List<Correction> corrections() {
        return List.copyOf(corrections.values());
    }

    public synchronized List<IdentityLink> identityLinks() {
        return List.copyOf(identities.values());
    }

    public synchronized List<CuratedInstance> instances() {
        return List.copyOf(instances.values());
    }

    /** Discard only pending identity work, leaving staged field corrections intact. */
    public synchronized void discardIdentityLinks() {
        if (identities.isEmpty()) return;
        identities.clear();
        changed();
        releaseIfUnused();
    }

    public synchronized int size() {
        return corrections.size() + identities.size() + instances.size();
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
        commit(Set.copyOf(corrections.keySet()), Set.copyOf(identities.keySet()),
                Set.copyOf(instances.keySet()));
    }

    /**
     * Persist exactly the records of one accepted batch and save once, leaving every
     * other pending record staged. Accepting a review must not also save manual edits
     * another curation surface is deliberately collecting. On failure the durable model
     * is restored and the batch stays pending, so it can be retried.
     */
    public synchronized void applyBatch(List<Correction> batchCorrections,
                                        List<IdentityLink> batchIdentities,
                                        List<CuratedInstance> batchInstances)
            throws IOException {
        Set<CorrectionKey> correctionKeys = new LinkedHashSet<>();
        if (batchCorrections != null) {
            batchCorrections.stream().filter(java.util.Objects::nonNull)
                    .map(CurationStaging::key).filter(corrections::containsKey)
                    .forEach(correctionKeys::add);
        }
        Set<IdentityKey> identityKeys = new LinkedHashSet<>();
        if (batchIdentities != null) {
            batchIdentities.stream().filter(java.util.Objects::nonNull)
                    .map(CurationStaging::key).filter(identities::containsKey)
                    .forEach(identityKeys::add);
        }
        Set<InstanceKey> instanceKeys = new LinkedHashSet<>();
        if (batchInstances != null) {
            batchInstances.stream().filter(java.util.Objects::nonNull)
                    .map(CurationStaging::key).filter(instances::containsKey)
                    .forEach(instanceKeys::add);
        }
        commit(correctionKeys, identityKeys, instanceKeys);
    }

    private void commit(Set<CorrectionKey> correctionKeys, Set<IdentityKey> identityKeys,
                        Set<InstanceKey> instanceKeys) throws IOException {
        List<Correction> pendingCorrections = correctionKeys.stream()
                .map(corrections::get).toList();
        List<IdentityLink> pendingIdentities = identityKeys.stream()
                .map(identities::get).toList();
        List<CuratedInstance> pendingInstances = instanceKeys.stream()
                .map(instances::get).toList();
        if (pendingCorrections.isEmpty() && pendingIdentities.isEmpty()
                && pendingInstances.isEmpty()) return;

        List<Correction> previousCorrections = target.corrections();
        List<IdentityLink> previousIdentities = target.identityLinks();
        List<CuratedInstance> previousInstances = target.instances();
        try {
            for (Correction correction : pendingCorrections) {
                target.put(correction.type(), correction.qid(), correction.field(),
                        correction.value(), correction.origin(), correction.valueKind(),
                        correction.policy(), correction.source(),
                        correction.referenceType());
            }
            for (IdentityLink link : pendingIdentities) target.putIdentityLink(link);
            for (CuratedInstance instance : pendingInstances) target.putInstance(instance);
            target.save();
        } catch (IOException | RuntimeException failure) {
            restore(pendingCorrections, pendingIdentities, pendingInstances,
                    previousCorrections, previousIdentities, previousInstances);
            throw failure;
        }

        correctionKeys.forEach(corrections::remove);
        identityKeys.forEach(identities::remove);
        instanceKeys.forEach(instances::remove);
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

    private void restore(List<Correction> pendingCorrections,
                         List<IdentityLink> pendingIdentities,
                         List<CuratedInstance> pendingInstances,
                         List<Correction> previousCorrections,
                         List<IdentityLink> previousIdentities,
                         List<CuratedInstance> previousInstances) {
        // Only touched keys need rollback; unrelated records may have been added by another
        // tool between staging and Apply and must not be erased.
        for (Correction pending : pendingCorrections) {
            target.remove(pending.type(), pending.qid(), pending.field());
            previousCorrections.stream()
                    .filter(old -> sameCorrectionKey(old, pending))
                    .forEach(target::restore);
        }
        for (IdentityLink pending : pendingIdentities) {
            target.removeIdentityLink(
                    pending.type(), pending.targetId(), pending.sourceKind());
            previousIdentities.stream()
                    .filter(old -> sameIdentityKey(old, pending))
                    .forEach(target::putIdentityLink);
        }
        for (CuratedInstance pending : pendingInstances) {
            target.removeInstance(pending.type(), pending.id());
            previousInstances.stream()
                    .filter(old -> java.util.Objects.equals(old.type(), pending.type())
                            && java.util.Objects.equals(old.id(), pending.id()))
                    .forEach(target::putInstance);
        }
    }

    private void changed() {
        // Copy because a listener may detach itself while responding.
        List.copyOf(listeners).forEach(Runnable::run);
    }

    private void releaseIfUnused() {
        if (!corrections.isEmpty() || !identities.isEmpty() || !instances.isEmpty()
                || !listeners.isEmpty()) return;
        synchronized (CurationStaging.class) {
            SESSIONS.remove(target, this);
        }
    }

    private static boolean sameCorrectionKey(Correction a, Correction b) {
        return a.sameTargetField(b);
    }

    private static boolean sameIdentityKey(IdentityLink a, IdentityLink b) {
        return java.util.Objects.equals(a.type(), b.type())
                && java.util.Objects.equals(a.targetId(), b.targetId())
                && java.util.Objects.equals(a.sourceKind(), b.sourceKind());
    }

    private record CorrectionKey(
            String type, String id, String field, AdditiveContribution contribution) { }
    private record AdditiveContribution(Object value, String valueKind,
                                        String referenceType) { }
    private record IdentityKey(String type, String id, String sourceKind) { }
    private record InstanceKey(String type, String id) { }
}
