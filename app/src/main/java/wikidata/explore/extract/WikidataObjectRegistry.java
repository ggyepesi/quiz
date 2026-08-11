package wikidata.explore.extract;

import wikidata.explore.extract.WikidataDynamicObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class WikidataObjectRegistry {

    /**
     * Canonicalizes WikidataDynamicObjects by QID.
     *
     * QID defines identity; labels, URLs and dynamic fields are attributes.
     * Therefore all references to the same QID resolve to the same object.
     */
    private final Map<String, WikidataDynamicObject> byQid =
            new LinkedHashMap<>();

    public WikidataDynamicObject getOrCreate1(String qid, String label) {
        return new WikidataDynamicObject(qid, label);
    }
    public WikidataDynamicObject getOrCreate(String qid, String label) {
        String key = cleanQid(qid);
        if (key.isBlank()) {
            throw new IllegalArgumentException("null/blank qid");
        }

        return byQid.computeIfAbsent(
                key,
                k -> new WikidataDynamicObject(k, label));
    }

    public WikidataDynamicObject get(String qid) {
        String key = cleanQid(qid);
        return key.isBlank() ? null : byQid.get(key);
    }

    /** Adopt an already-built object into the registry if its QID is not present —
     *  keeping the SAME instance (not a fresh empty one), so references to it stay
     *  valid. Used to fold discovered POPULATION subjects into the shared pool. */
    public void adoptIfAbsent(WikidataDynamicObject object) {
        if (object == null) {
            return;
        }
        String key = cleanQid(object.qid());
        if (!key.isBlank()) {
            byQid.putIfAbsent(key, object);
        }
    }

    public Collection<WikidataDynamicObject> values() {
        return byQid.values();
    }

    /** The QIDs currently pooled. A caller that must later tell ITS OWN additions
     *  apart from what was already here takes this before it starts. */
    public Set<String> qids() {
        return new LinkedHashSet<>(byQid.keySet());
    }

    /**
     * Drops a QID from the pool. For a candidate that a discovery pass created and
     * then rejected: the pool — not the caller's returned list — is what gets saved,
     * so a rejected candidate left here is persisted as a field-less instance.
     *
     * <p>Only ever call this for an object the caller itself created. The pool is
     * shared across class runs, and removing one another run owns would delete a
     * live instance out from under it.
     */
    public void remove(String qid) {
        String key = cleanQid(qid);
        if (!key.isBlank()) {
            byQid.remove(key);
        }
    }

    private static String cleanQid(String qid) {
        if (qid == null) return "";

        qid = qid.trim();

        if (qid.startsWith("wd:")) {
            qid = qid.substring(3);
        }

        int slash = qid.lastIndexOf('/');
        if (slash >= 0) {
            qid = qid.substring(slash + 1);
        }

        return qid.trim();
    }
}