package quiz.curation;

import wikidata.explore.extract.WikidataDynamicObject;

import objectview.Viewable;
import objectview.field.FieldAccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies the correction overlay to a loaded pool, matching instances by identifier.
 * Replay follows each correction's explicit {@link CorrectionPolicy}. Legacy manual
 * records map to REPLACE and legacy external records to FILL_IF_EMPTY; fresh records can
 * also add to collections while retaining their external provenance. Writes through
 * {@link FieldAccess} (map-held or declared fields alike).
 */
public final class Corrections {

    private Corrections() {}

    /** Overlay {@code sources} onto {@code pool}; returns how many values were set. */
    public static int apply(Collection<? extends Viewable> pool, List<CorrectionSource> sources) {
        if (pool == null || sources == null) {
            return 0;
        }
        Map<TargetKey, Viewable> byKey = new HashMap<>();
        // EVERY instance per qid, not the first: identity is <typeKey, qid>, so one QID
        // can be pooled under two type keys (the State / Group "France" case). A
        // type-less correction is a statement about the ENTITY, so it must reach all of
        // them — keeping only the first renamed one instance and left the other a bare
        // QID, picked by pool iteration order.
        Map<String, List<Viewable>> byQid = new HashMap<>();
        Map<String, Viewable> firstByQid = new HashMap<>();
        for (Viewable q : pool) {
            if (q != null && q.getIdentifier() != null) {
                byKey.putIfAbsent(new TargetKey(q.typeName(), q.getIdentifier()), q);
                byQid.computeIfAbsent(q.getIdentifier(), k -> new ArrayList<>()).add(q);
                firstByQid.putIfAbsent(q.getIdentifier(), q);
            }
        }

        List<Correction> all = new ArrayList<>();
        for (CorrectionSource source : sources) {
            if (source != null && source.corrections() != null) {
                all.addAll(source.corrections());
            }
        }

        Map<SampleKey, Object> sampleByField = sampleValues(pool, all);
        int applied = 0;
        Set<String> manualKeys = new HashSet<>();

        // A reference correction stores a QID; it resolves against THIS pool, so a
        // curated reference lands on the same instance every other field pointing at
        // that entity already uses.
        // A reference resolves to ONE instance — the value of a field is a single
        // object — so the reference pool keeps first-wins.
        REFERENCE_POOL.set(firstByQid);
        try {

        // Pass 1 — authoritative reviewed values override or extend the base.
        for (Correction c : all) {
            if (c.effectivePolicy() == CorrectionPolicy.EVIDENCE_ONLY) continue;
            if (!c.authoritative()) {
                continue;
            }
            List<Viewable> targets = targets(c, byKey, byQid);
            if (targets.isEmpty()) {
                continue;
            }
            for (Viewable target : targets) {
                applyCorrection(target, c, sampleByField.get(sampleKey(c)));
                applied++;
            }
            manualKeys.add(key(c));
        }

        // Pass 2 — rules / external sources only fill a field that's still a GAP: absent,
        // blank, or an empty collection (isValidQuizValue), so a "dbpedia" flag fills an
        // empty flagVersions the same as it fills a null portrait — matching what the
        // coverage view counts as missing. Never clobbers a field that already has data.
        for (Correction c : all) {
            if (c.effectivePolicy() == CorrectionPolicy.EVIDENCE_ONLY) continue;
            if (c.authoritative() || manualKeys.contains(key(c))) {
                continue;
            }
            for (Viewable target : targets(c, byKey, byQid)) {
                if (objectview.ViewableAdapter.isValidQuizValue(
                        FieldAccess.getPath(target, c.field()))) {
                    continue;   // this instance already has a value; the next may not
                }
                applyCorrection(target, c, sampleByField.get(sampleKey(c)));
                applied++;
            }
        }

        return applied;
        } finally {
            REFERENCE_POOL.remove();
        }
    }

    private static void applyCorrection(Viewable target, Correction correction,
                                        Object sample) {
        // The display label is a COMPUTED field — projected from getDisplayName(), with
        // no setter to write through — so renaming an instance cannot go through
        // FieldAccess like an ordinary value. Keyed on the reserved DISPLAY role rather
        // than on a field called "name", so it is a declared contract and not a guess
        // about what a field means from its spelling.
        if (objectview.field.ViewableContractFieldSet.DISPLAY_KEY.equals(correction.field())
                && correction.value() instanceof String label && !label.isBlank()) {
            if (target instanceof WikidataDynamicObject wdo) {
                wdo.name(label);
            }
            return;
        }
        Object reviewed = coerceCorrection(correction, target, sample);
        CorrectionPolicy policy = correction.effectivePolicy();
        if (policy == CorrectionPolicy.ADD_TO_COLLECTION) {
            Object current = FieldAccess.getPath(target, correction.field());
            List<Object> combined = new ArrayList<>();
            addDistinct(combined, current);
            addDistinct(combined, reviewed);
            FieldAccess.setPath(target, correction.field(), combined);
        } else {
            FieldAccess.setPath(target, correction.field(), reviewed);
        }
    }

    private static void addDistinct(List<Object> target, Object value) {
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (!target.contains(item)) target.add(item);
            }
        } else if (value != null && !target.contains(value)) {
            target.add(value);
        }
    }

    /** A representative existing value per corrected field, so {@link #coerce} can
     *  match its runtime type (the curation stores plain JSON/text values). */
    private static Map<SampleKey, Object> sampleValues(Collection<? extends Viewable> pool,
                                                       List<Correction> corrections) {
        Set<SampleKey> fields = new HashSet<>();
        for (Correction c : corrections) {
            fields.add(sampleKey(c));
        }
        Map<SampleKey, Object> out = new HashMap<>();
        for (Viewable q : pool) {
            if (out.size() == fields.size()) {
                break;
            }
            for (SampleKey f : fields) {
                if ((f.type() == null || f.type().equals(q.typeName()))
                        && !out.containsKey(f)) {
                    Object v = FieldAccess.getPath(q, f.field());
                    // A VALID (non-empty) representative — so coerce sees a non-empty
                    // collection and can read its element type (an empty flagVersions
                    // would give coerce nothing to shape a media value from).
                    if (objectview.ViewableAdapter.isValidQuizValue(v)) {
                        out.put(f, v);
                    }
                }
            }
        }
        return out;
    }

    /** Coerce a stored (plain) value to the field's runtime type, matched from an
     *  existing {@code sample} value — Boolean/number/String directly, else a
     *  single-arg {@code int} or {@code String} constructor (e.g. FlexibleDate(year)).
     *  Falls back to the raw value when it can't. Shared with the transform layer
     *  (a projection shaping a referent's value into the target field's type). */
    public static Object coerce(Object value, Object sample) {
        if (value == null || sample == null || sample.getClass().isInstance(value)) {
            return value;
        }
        // MEDIA: a plain URL string filling a media field (single value or a collection
        // of them). Build a same-class MediaValue reflectively — so this layer needs no
        // wikidata.explore import — from the sample's runtime type. Lets a DBpedia image
        // URL become a WikidataMediaValue (a laureate portrait / a state flag) on apply.
        if (value instanceof String url) {
            Object media = mediaFrom(sample, url);
            if (media != null) {
                return media;
            }
        }
        String s = String.valueOf(value).trim();
        try {
            if (sample instanceof Boolean) {
                return Boolean.parseBoolean(s);
            }
            if (sample instanceof Integer) {
                return Integer.valueOf(s);
            }
            if (sample instanceof Long) {
                return Long.valueOf(s);
            }
            if (sample instanceof Double) {
                return Double.valueOf(s);
            }
            if (sample instanceof String) {
                return String.valueOf(value);
            }
            try {
                return sample.getClass().getConstructor(int.class).newInstance(Integer.parseInt(s));
            } catch (ReflectiveOperationException | NumberFormatException ignored) {
                return sample.getClass().getConstructor(String.class).newInstance(String.valueOf(value));
            }
        } catch (Exception e) {
            return value;
        }
    }

    /** The pool a reference correction resolves its QID against. Set for the duration of
     *  one apply, so a curated reference points at the SAME instance every other field
     *  referring to that entity does. */
    private static final ThreadLocal<java.util.Map<String, Viewable>> REFERENCE_POOL =
            new ThreadLocal<>();

    private static Object coerceCorrection(Correction correction, Viewable target, Object sample) {
        if (correction.value() instanceof String qid
                && (Correction.REFERENCE.equals(correction.valueKind())
                    || Correction.REFERENCE_COLLECTION.equals(correction.valueKind()))) {
            Viewable referent = referent(qid);
            boolean collection =
                    Correction.REFERENCE_COLLECTION.equals(correction.valueKind());
            return collection ? List.of(referent) : referent;
        }
        if (sample == null && correction.value() instanceof String url
                && (Correction.MEDIA.equals(correction.valueKind())
                    || Correction.MEDIA_COLLECTION.equals(correction.valueKind()))) {
            boolean collection = Correction.MEDIA_COLLECTION.equals(correction.valueKind());
            Object declared = declaredMedia(target, correction.field(), url, collection);
            if (declared != null) {
                return declared;
            }
            Object dynamicMedia = dynamicMedia(target, url);
            objectview.media.MediaValue media = dynamicMedia instanceof objectview.media.MediaValue m
                    ? m : new CuratedMediaValue(mediaLabel(url), url, isSvg(url));
            return collection ? List.of(media) : media;
        }
        return coerce(correction.value(), sample);
    }

    /**
     * The pooled instance for {@code qid}, or a stub named by the QID when the pool has
     * none.
     *
     * <p>A stub rather than a dropped value: the decision was made and recorded, and
     * losing it because the entity is not in this particular pool would be worse than
     * showing it unnamed — which the UNNAMED_REFERENCE curation scope then lists for a
     * label fill.
     */
    private static Viewable referent(String qid) {
        java.util.Map<String, Viewable> pool = REFERENCE_POOL.get();
        Viewable pooled = pool == null ? null : pool.get(qid);
        if (pooled != null) {
            return pooled;
        }
        return new quiz.transform.DynamicViewable(qid, qid);
    }

    /** A URL → a MediaValue matching {@code sample}'s shape: a single value when the
     *  field holds one, or a one-element collection when it holds a list of them; null
     *  when {@code sample} isn't media (so coerce falls through to scalar handling). */
    private static Object mediaFrom(Object sample, String url) {
        if (sample instanceof objectview.media.MediaValue) {
            return buildMediaValue(sample.getClass(), url);
        }
        if (sample instanceof Collection<?> c && !c.isEmpty()
                && c.iterator().next() instanceof objectview.media.MediaValue element) {
            Object mediaValue = buildMediaValue(element.getClass(), url);
            if (mediaValue == null) {
                return null;
            }
            List<Object> out = new ArrayList<>();
            out.add(mediaValue);
            return out;
        }
        return null;
    }

    /** Construct {@code mediaClass}(label, url, svg) reflectively — the ctor shape shared
     *  by the pool's MediaValue types (e.g. WikidataMediaValue). */
    private static Object buildMediaValue(Class<?> mediaClass, String url) {
        try {
            boolean svg = isSvg(url);
            return mediaClass.getConstructor(String.class, String.class, boolean.class)
                    .newInstance(mediaLabel(url), url, svg);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** The image's file name (decoded, underscores → spaces) as a display label. */
    private static String mediaLabel(String url) {
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 && slash + 1 < url.length() ? url.substring(slash + 1) : url;
        int query = name.indexOf('?');
        if (query >= 0) {
            name = name.substring(0, query);
        }
        try {
            name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
            // keep the raw name
        }
        return name.replace('_', ' ');
    }

    private static boolean isSvg(String url) {
        int end = url.length();
        int query = url.indexOf('?');
        int fragment = url.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return url.substring(0, end).toLowerCase(java.util.Locale.ROOT).endsWith(".svg");
    }

    private static Object declaredMedia(Viewable target, String path, String url,
                                        boolean collection) {
        try {
            String[] parts = path.split("\\.");
            Object owner = target;
            for (int i = 0; i < parts.length - 1; i++) {
                owner = FieldAccess.getPath(owner, parts[i]);
                if (owner == null) return null;
            }
            java.lang.reflect.Field field =
                    objectview.ViewableAdapter.getField(owner.getClass(), parts[parts.length - 1]);
            if (field == null) return null;
            Class<?> mediaClass = field.getType();
            if (collection && field.getGenericType() instanceof java.lang.reflect.ParameterizedType p
                    && p.getActualTypeArguments()[0] instanceof Class<?> elementClass) {
                mediaClass = elementClass;
            }
            Object media = buildMediaValue(mediaClass, url);
            return media == null ? null : collection ? List.of(media) : media;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Dynamic snapshot fields have no declared Java field to inspect. Use their
     *  backing media value when it is present, without coupling curation to that class. */
    private static Object dynamicMedia(Viewable target, String url) {
        if (!"wikidata.explore.extract.WikidataDynamicObject"
                .equals(target.getClass().getName())) {
            return null;
        }
        try {
            Class<?> mediaClass =
                    Class.forName("wikidata.explore.extract.WikidataMediaValue");
            return buildMediaValue(mediaClass, url);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    /**
     * The instances a correction applies to.
     *
     * <p>A typed correction names one instance: ⟨typeKey, qid⟩ IS the identity. An
     * untyped one is a statement about the entity behind the QID — a resolved label
     * belongs to the person, not to whichever class happens to reference her — so it
     * reaches every instance carrying that QID.
     */
    private static List<Viewable> targets(Correction correction,
                                          Map<TargetKey, Viewable> byKey,
                                          Map<String, List<Viewable>> byQid) {
        if (correction.type() == null) {
            return byQid.getOrDefault(correction.qid(), List.of());
        }
        Viewable typed = byKey.get(new TargetKey(correction.type(), correction.qid()));
        return typed == null ? List.of() : List.of(typed);
    }

    private static SampleKey sampleKey(Correction correction) {
        return new SampleKey(correction.type(), correction.field());
    }

    private static String key(Correction c) {
        return String.valueOf(c.type()) + "\u0000" + c.qid() + "\u0000" + c.field();
    }

    private record TargetKey(String type, String qid) { }
    private record SampleKey(String type, String field) { }

    private record CuratedMediaValue(String mediaLabel, String mediaUrl, boolean mediaSvg)
            implements objectview.media.MediaValue { }
}
