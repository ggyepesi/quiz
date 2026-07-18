package wikidata.explore.transform;

import wikidata.WikidataSparqlClient;
import wikidata.explore.extract.FieldOrigin;
import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies Transform constructs to a loaded snapshot pool (the
 * {@link WikidataDynamicObject} qid-pool), materializing new view structures
 * <i>in place</i>. The augmented pool is then saved + served exactly like a
 * generated class — {@code GeneratedSource.registerAll} serves every stamped
 * type, so a construct's output is searchable/sortable/view-configurable with no
 * extra plumbing.
 */
public class TransformEngine {

    // Duplicate reified records dropped by dedup/canonicalize and un-stamped: they
    // must NOT be served (they'd show as duplicate untyped cards). Collected here so
    // the caller can also EXCLUDE them from the pool, not just leave them un-typed.
    private final java.util.Set<WikidataDynamicObject> demoted =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Reified records dropped as duplicates (un-stamped) — exclude from the pool. */
    public java.util.Set<WikidataDynamicObject> demoted() {
        return demoted;
    }

    // Structured audit of the self-referential phantom drop (#99): one entry per
    // fully self-referential atom — DROPPED (with the witnessing atom) or KEPT (no
    // witness) — with the exact identity fields the decision keyed on, so it can be
    // double-checked in the generation log.
    private final List<SelfRefFinding> selfRefFindings = new ArrayList<>();

    /** Whether a fully self-referential atom was dropped as a witnessed phantom. */
    public enum SelfRefDecision { DROPPED, KEPT }

    /**
     * A structured record of one self-referential-phantom decision (#99): the atom,
     * the witnessing atom (null when kept), the identity fields the match keyed on,
     * and a human-readable reason. Its {@link #toString()} is the audit line.
     */
    public record SelfRefFinding(SelfRefDecision decision, WikidataDynamicObject atom,
                                 WikidataDynamicObject witness,
                                 List<String> identityFields, String reason) {
        @Override
        public String toString() {
            return decision + " self-nomination " + describe(atom)
                    + (witness != null ? " — witnessed by " + describe(witness) : "")
                    + " on " + identityFields + " — " + reason;
        }
    }

    /** Structured audit of the self-referential phantom drop (#99). */
    public List<SelfRefFinding> selfReferenceFindings() {
        return selfRefFindings;
    }

    /**
     * Applies a domain's whole Transform to the pool, returning the objects it
     * created (the reified ones; inverts mutate existing objects in place). The
     * created objects are also added to {@code pool} so a later save/serve picks
     * them up.
     */
    public List<WikidataDynamicObject> apply(
            List<WikidataDynamicObject> pool, TransformConfig config) {
        return apply(pool, config, null, null);
    }

    /**
     * Applies a domain's whole Transform. With a non-null {@code client}, any
     * {@link QualifierLoadConfig}s run FIRST (a Load step that attaches nested
     * statement objects via {@link QualifierLoader}), so a following
     * {@code reify(promote)} can lift them into top-level events. The created
     * objects (statements + reified) are returned and added to {@code pool}.
     */
    public List<WikidataDynamicObject> apply(
            List<WikidataDynamicObject> pool, TransformConfig config,
            WikidataSparqlClient client, GenerationLog log) {
        List<WikidataDynamicObject> created = new ArrayList<>();
        if (pool == null || config == null) {
            return created;
        }
        if (client != null && config.qualifierLoads != null) {
            QualifierLoader loader = new QualifierLoader();
            for (QualifierLoadConfig c : config.qualifierLoads) {
                // Statement objects are attached to their entity; they become
                // top-level only if a reify(promote) lifts them, so don't add
                // them to `created` here (avoids double-listing pre-promotion).
                loader.enrich(pool, c, client, log);
            }
        }
        for (InvertConstruct c : config.inverts) {
            applyInvert(pool, c);
        }
        for (ReifyConstruct c : config.reifies) {
            created.addAll(applyReify(pool, c));
        }
        return created;
    }

    /**
     * Inverts {@code sourceType.refField} into {@code targetType.backRefField}.
     * For each source object of {@code sourceType}, each referenced object is
     * stamped {@code targetType} and gains {@code backRefField} += source.
     */
    public void applyInvert(Collection<WikidataDynamicObject> pool,
                            InvertConstruct c) {
        if (pool == null || c == null
                || c.sourceType() == null || c.refField() == null
                || c.targetType() == null || c.backRefField() == null) {
            return;
        }

        // Collect sources first: we mutate targets (also in the pool), so iterate
        // a stable snapshot of the sources.
        List<WikidataDynamicObject> sources = new ArrayList<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null && c.sourceType().equals(o.typeName())) {
                sources.add(o);
            }
        }

        for (WikidataDynamicObject src : sources) {
            for (WikidataDynamicObject target : referencedObjects(src.get(c.refField()))) {
                target.type(c.targetType());
                target.merge(c.backRefField(), src);
            }
        }
    }

    /**
     * Projects {@code targetType.outField} from a typed PATH on the entity a
     * reference points to: {@code out ← via.<sourcePath>}. The path can address a
     * type's views (e.g. {@code date.year}, {@code date.monthDay} via
     * {@link objectview.utils.Addressable}) — so extraction is the path, not a convention baked
     * into a construct. The referent's value is authoritative (it OVERWRITES) and
     * is coerced to the target field's runtime type (an int year → a FlexibleDate).
     * No query — the referent is already in the pool, resolved by qid to the
     * field-bearing instance. Returns the number of instances whose {@code
     * outField} value was set/changed (so callers can report the effect).
     */
    public int applyProjection(Collection<WikidataDynamicObject> pool,
                               String targetType, String viaField,
                               String sourcePath, String outField) {
        if (pool == null || targetType == null || viaField == null
                || sourcePath == null || sourcePath.isBlank() || outField == null) {
            return 0;
        }
        Map<String, WikidataDynamicObject> byQid = new HashMap<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null && o.qid() != null && !o.qid().isBlank()) {
                byQid.putIfAbsent(o.qid(), o);
            }
        }
        Object sample = sampleValue(pool, targetType, outField);
        int changed = 0;
        for (WikidataDynamicObject o : pool) {
            if (o == null || !targetType.equals(o.typeName())) {
                continue;
            }
            WikidataDynamicObject referent = referent(o.get(viaField), byQid);
            if (referent == null) {
                continue;
            }
            Object value = objectview.field.FieldAccess.getPath(referent, sourcePath);
            if (value != null) {
                Object coerced = quiz.curation.Corrections.coerce(value, sample);
                if (!java.util.Objects.equals(coerced, o.get(outField))) {
                    o.put(outField, coerced);
                    changed++;
                }
            }
        }
        return changed;
    }

    // The entity a reference field points to, resolved by qid to the canonical
    // (field-bearing) instance in the pool — a qualifier-loaded reference may be a
    // bare copy while the generated one carries the fields.
    private WikidataDynamicObject referent(Object viaValue,
                                           Map<String, WikidataDynamicObject> byQid) {
        for (WikidataDynamicObject ref : referencedObjects(viaValue)) {
            return ref.qid() != null && byQid.containsKey(ref.qid())
                    ? byQid.get(ref.qid())
                    : ref;
        }
        return null;
    }

    // A representative existing value of the target field, so a projected value can
    // be coerced to the field's runtime type (e.g. an int year → FlexibleDate).
    private static Object sampleValue(Collection<WikidataDynamicObject> pool,
                                      String targetType, String outField) {
        for (WikidataDynamicObject o : pool) {
            if (o != null && targetType.equals(o.typeName()) && o.get(outField) != null) {
                return o.get(outField);
            }
        }
        return null;
    }

    /**
     * Reifies {@code sourceType.listField}: creates a {@code targetType} object
     * per (source, element) pair, holding the source and element. Created
     * objects are added to {@code pool} and returned.
     */
    public List<WikidataDynamicObject> applyReify(
            List<WikidataDynamicObject> pool, ReifyConstruct c) {
        return applyReify(pool, c, null);
    }

    /**
     * As {@link #applyReify(List, ReifyConstruct)}, but told the reified statement's
     * VALUE field (the category, from {@code QualifierLoadConfig.valueField}). That
     * field is the primary identity of the reified event slot, so the self-referential
     * phantom witness match keys on it (plus any real role value) rather than on every
     * incidentally-loaded field. Pass {@code null} to key on the legacy broad context.
     */
    public List<WikidataDynamicObject> applyReify(
            List<WikidataDynamicObject> pool, ReifyConstruct c, String valueField) {
        // Per-call audit state: reset so a reused engine doesn't carry a prior
        // call's demotions/findings into this result. Callers that aggregate across
        // several applyReify calls must collect after each call (see reify()).
        demoted.clear();
        selfRefFindings.clear();
        List<WikidataDynamicObject> created = new ArrayList<>();
        if (pool == null || c == null || c.sourceType() == null
                || c.listField() == null || c.targetType() == null) {
            return created;
        }
        List<WikidataDynamicObject> sources = new ArrayList<>();
        for (WikidataDynamicObject o : pool) {
            if (o != null && c.sourceType().equals(o.typeName())) {
                sources.add(o);
            }
        }
        String srcField = c.sourceField() == null || c.sourceField().isBlank()
                ? c.sourceType().toLowerCase() : c.sourceField();
        String elField = c.elementField() == null || c.elementField().isBlank()
                ? "value" : c.elementField();

        // Records that carry an inverse role straight from a qualifier (e.g. a
        // person's P1686 "for work") — the denormalized copy of a nomination whose
        // canonical form is the work's own statement. Tracked for canonicalization.
        java.util.Set<WikidataDynamicObject> hadInverseQual =
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        for (WikidataDynamicObject src : sources) {
            for (WikidataDynamicObject el : referencedObjects(src.get(c.listField()))) {
                if (c.promote()) {
                    // The element is already a rich statement object — lift it in
                    // place: stamp the target type, add the source back-ref, keep
                    // its own fields (award, year, …). Flat, not nested.
                    el.type(c.targetType());
                    el.put(srcField, src);
                    // Canonical roles: each role field = its qualifier value ∨ the
                    // source entity, so a relation denormalized onto both endpoints
                    // resolves to the SAME (subject, work) on either side.
                    Object subjectForName = null;
                    boolean inverseQual = false;
                    for (ReifyConstruct.Role r : c.roles()) {
                        if (r == null || r.field() == null || r.field().isBlank()) {
                            continue;
                        }
                        Object raw = r.from() == null || r.from().isBlank()
                                ? null : el.get(r.from());
                        if (raw != null) {
                            inverseQual = true;   // a real qualifier (the inverse copy)
                        }
                        Object v = raw == null && r.fallbackToSource() ? src : raw;
                        if (v != null) {
                            el.put(r.field(), v);
                            el.recordOrigin(r.field(), originOf(raw, r));
                            if (subjectForName == null) {
                                subjectForName = v;
                            }
                        } else {
                            el.recordOrigin(r.field(), FieldOrigin.MISSING);
                        }
                    }
                    if (inverseQual) {
                        hadInverseQual.add(el);
                    }
                    Object namePrefix = subjectForName != null ? subjectForName : src;
                    el.name(display(namePrefix) + " — " + el.getDisplayName());
                    created.add(el);
                } else {
                    WikidataDynamicObject n = new WikidataDynamicObject(
                            src.qid() + "__" + el.qid(),
                            src.getDisplayName() + " — " + el.getDisplayName());
                    n.type(c.targetType());
                    n.put(srcField, src);
                    n.put(elField, el);
                    created.add(n);
                }
            }
        }
        // De-denormalize. Two strategies:
        //  - canonicalize-by-list: the record that carries the shared-award nominee
        //    list IS the nomination; a record that only carries the inverse role (a
        //    person's "for work" copy) is dropped; a record with neither makes its
        //    own subject the sole nominee (work-less / honorary). Captures sharing.
        //  - dedupBy key: collapse records equal on a key (the legacy path).
        List<WikidataDynamicObject> result =
                c.canonicalizesByList()
                        ? canonicalize(created, c.primaryListField(), srcField,
                                       hadInverseQual)
                        : c.dedupBy().isEmpty() ? created : dedup(created, c.dedupBy());

        // The SAME nomination can still arrive from both the work's statement and
        // the nominee's own back-reference — a person's P1411 "nominated for" with
        // pq:P1686=work (Katharine Hepburn's 12 Best Actress statements, one per
        // film). Those copies are identical on the dedup key (category, year,
        // forWork, nominee) but differ in source, and canonicalize-by-list keeps
        // both. Collapse them, keeping the WORK-anchored copy: the one whose source
        // equals a role value (source==forWork), not the nominee's self-copy
        // (source==the list). Uses the final field values, so it's robust to when
        // the qualifiers were loaded.
        if (!c.dedupBy().isEmpty()) {
            result = dedupPreferringWorkAnchored(result, c.dedupBy(), srcField, c.roles());
        }

        // #99: drop WITNESSED self-referential phantom atoms. A fully self-referential
        // atom (every role field fell back to the subject) is a phantom only when a
        // WITNESS exists — another atom in the same category whose real qualifier
        // references this atom's subject, i.e. the genuine record that this one is a
        // denormalized self-copy of. Without a witness it is a legitimate
        // self-nomination (a film IS its Best-Picture nominee; an honorary subject IS
        // the nominee) and is kept. Generic — reads Phase-1 origins, no domain constants.
        if (c.promote()) {
            result = dropWitnessedSelfReferences(
                    result, c, srcField, valueField, selfRefFindings);
        }

        // Dedup must remove duplicates from the SERVED set, not merely the returned
        // list. A promoted statement is type-stamped IN PLACE (and is also reachable
        // from its source's list field + the pool), so a dropped duplicate stays
        // stamped as the target type and re-surfaces in the snapshot — the served
        // set is "every pooled object of this type", not just `result`. Un-stamp the
        // dropped duplicates so only the kept representative is served/listed.
        if (c.promote() && result.size() < created.size()) {
            java.util.Set<WikidataDynamicObject> keep =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            keep.addAll(result);
            java.util.Set<WikidataDynamicObject> dropped =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (WikidataDynamicObject o : created) {
                if (!keep.contains(o)) {
                    o.type(null);   // anonymous referenced child, not a served event
                    demoted.add(o); // …and drop from the served pool, not just untype
                    dropped.add(o);
                }
            }
            // Unlink dropped stubs from their source's list field (e.g.
            // OscarNominations.__Nomination), so they're unreachable and never
            // serialized/served as duplicate untyped cards.
            for (WikidataDynamicObject src : sources) {
                Object lst = src.get(c.listField());
                if (lst instanceof java.util.Collection<?> col) {
                    try {
                        col.removeIf(dropped::contains);
                    } catch (UnsupportedOperationException immutable) {
                        src.put(c.listField(), new ArrayList<>(col).stream()
                                .filter(x -> !dropped.contains(x)).toList());
                    }
                }
            }
        }
        pool.addAll(result);
        return result;
    }

    /**
     * Keep one record per nomination using the shared-award nominee list:
     * <ul>
     *   <li>has the primary list (≥1 nominee) → <b>canonical</b>, keep;</li>
     *   <li>no list but carried an inverse role from a qualifier (a "for work"
     *       copy) → drop (left for the demote pass to un-stamp);</li>
     *   <li>no list and no inverse qualifier → work-less/honorary, so the subject
     *       is the sole nominee: set the list to the source and keep.</li>
     * </ul>
     */
    private static List<WikidataDynamicObject> canonicalize(
            List<WikidataDynamicObject> created, String primaryField, String srcField,
            java.util.Set<WikidataDynamicObject> hadInverseQual) {
        List<WikidataDynamicObject> result = new ArrayList<>();
        for (WikidataDynamicObject o : created) {
            if (nonEmpty(o.get(primaryField))) {
                result.add(o);
            } else if (hadInverseQual.contains(o)) {
                // denormalized copy — its nomination is the work's own (canonical)
                // statement; not added, so the demote pass un-stamps it.
            } else {
                Object src = o.get(srcField);
                if (src != null) {
                    o.put(primaryField, src);
                }
                result.add(o);
            }
        }
        return result;
    }

    private static boolean nonEmpty(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Collection<?> col) {
            return !col.isEmpty();
        }
        return true;
    }

    // ---- #99: witnessed self-referential phantom drop ----

    private static List<WikidataDynamicObject> dropWitnessedSelfReferences(
            List<WikidataDynamicObject> atoms, ReifyConstruct c, String srcField,
            String valueField, List<SelfRefFinding> findingsOut) {

        List<WikidataDynamicObject> kept = new ArrayList<>();
        for (WikidataDynamicObject atom : atoms) {
            Object subject = atom.get(srcField);
            if (subject == null
                    || !isFullySelfReferential(atom, subject, c.roles())) {
                kept.add(atom);
                continue;
            }
            List<String> identity = identityFields(atom, c, srcField, valueField);
            if (identity.isEmpty()) {
                // No field identifies this event slot — can't confirm a witness is
                // about the SAME event, so keep it (an empty context would match any
                // subject-referencing atom, a false positive).
                findingsOut.add(new SelfRefFinding(SelfRefDecision.KEPT, atom, null,
                        identity, "fully self-referential but nothing identifies its slot"));
                kept.add(atom);
                continue;
            }
            WikidataDynamicObject witness =
                    findWitness(atom, subject, atoms, c, identity);
            if (witness != null) {
                findingsOut.add(new SelfRefFinding(SelfRefDecision.DROPPED, atom, witness,
                        identity, "a real record references this subject through a "
                                + "REFERENCE role on the same slot"));
                continue;   // a genuine record exists → this is its denormalized self-copy
            }
            findingsOut.add(new SelfRefFinding(SelfRefDecision.KEPT, atom, null,
                    identity, "fully self-referential but no witness on the same slot"));
            kept.add(atom);
        }
        return kept;
    }

    private static String describe(WikidataDynamicObject o) {
        return o.qid() + " \"" + o.getDisplayName() + "\"";
    }

    /**
     * A reified atom is fully self-referential when every role field that got a value
     * fell back to the statement subject — origin {@link FieldOrigin#SUBJECT_FALLBACK}
     * AND the value actually IS the subject — and none came from a real qualifier or
     * the statement value. The value check guards against a mislabeled origin.
     */
    private static boolean isFullySelfReferential(
            WikidataDynamicObject atom, Object subject, List<ReifyConstruct.Role> roles) {

        boolean anyFallbackRole = false;
        for (ReifyConstruct.Role r : roles) {
            if (r == null || r.field() == null || r.field().isBlank()) {
                continue;
            }
            FieldOrigin origin = atom.origin(r.field());
            if (origin == FieldOrigin.QUALIFIER
                    || origin == FieldOrigin.STATEMENT_VALUE) {
                return false;   // a real value (qualifier or statement value) anchors it
            }
            if (origin == FieldOrigin.SUBJECT_FALLBACK) {
                if (!sameEntity(atom.get(r.field()), subject)) {
                    return false;   // labeled a fallback but the value isn't the subject
                }
                anyFallbackRole = true;
            }
        }
        return anyFallbackRole;
    }

    /**
     * The fields that identify a reified event's slot: the reified value (the
     * category, {@code valueField}) plus any role the atom anchored on a REAL value.
     * Incidental loaded qualifiers (song, edition, year) are deliberately excluded —
     * they vary within one slot and would make a genuine witness look different. When
     * no {@code valueField} is known, fall back to the legacy broad context (every
     * field that is not a role, the source, or the element back-ref).
     */
    private static List<String> identityFields(
            WikidataDynamicObject atom, ReifyConstruct c, String srcField,
            String valueField) {

        java.util.Set<String> roleFields = new java.util.HashSet<>();
        for (ReifyConstruct.Role r : c.roles()) {
            if (r != null && r.field() != null && !r.field().isBlank()) {
                roleFields.add(r.field());
            }
        }

        List<String> fields = new ArrayList<>();
        if (valueField != null && !valueField.isBlank()) {
            if (!valueField.equals(srcField) && !valueField.equals(c.elementField())
                    && nonEmpty(atom.get(valueField))) {
                fields.add(valueField);
            }
            for (ReifyConstruct.Role r : c.roles()) {
                if (r == null || r.field() == null || r.field().isBlank()) {
                    continue;
                }
                FieldOrigin o = atom.origin(r.field());
                if (o == FieldOrigin.QUALIFIER || o == FieldOrigin.STATEMENT_VALUE) {
                    fields.add(r.field());
                }
            }
            return fields;
        }

        // Legacy broad context (no valueField supplied).
        for (java.util.Map.Entry<String, Object> e : atom.dynamicFields().entrySet()) {
            String f = e.getKey();
            if (!roleFields.contains(f) && !f.equals(srcField)
                    && !f.equals(c.elementField()) && nonEmpty(e.getValue())) {
                fields.add(f);
            }
        }
        return fields;
    }

    // The origin of a role's value: a real qualifier (fallback-to-source role with a
    // present qualifier), the reified statement's own value (a STATEMENT_VALUE role,
    // fallbackToSource == false), or the subject fallback (absent qualifier).
    private static FieldOrigin originOf(Object raw, ReifyConstruct.Role r) {
        if (raw == null) {
            return FieldOrigin.SUBJECT_FALLBACK;
        }
        return r.fallbackToSource()
                ? FieldOrigin.QUALIFIER
                : FieldOrigin.STATEMENT_VALUE;
    }

    // A witness confirms `phantom` is a denormalized self-copy of a genuine record:
    // another atom that (a) is not itself a fully self-referential phantom, (b)
    // references the phantom's subject via a REAL (qualifier-origin) REFERENCE role,
    // and (c) agrees with the phantom on its identity fields (the category and any
    // real role value). (c) is what keeps a film's legitimate Best-Picture
    // self-nomination (a different category) from being dropped by a different-slot
    // witness.
    private static WikidataDynamicObject findWitness(
            WikidataDynamicObject phantom, Object subject,
            List<WikidataDynamicObject> atoms, ReifyConstruct c,
            List<String> identityFields) {

        for (WikidataDynamicObject w : atoms) {
            if (w == phantom
                    || isFullySelfReferential(w, w.get(sourceField(c)), c.roles())) {
                continue;
            }
            if (referencesSubject(w, subject, c.roles())
                    && agreesOnIdentity(w, phantom, identityFields)) {
                return w;
            }
        }
        return null;
    }

    private static String sourceField(ReifyConstruct c) {
        return c.sourceField() == null || c.sourceField().isBlank()
                ? c.sourceType().toLowerCase() : c.sourceField();
    }

    private static boolean referencesSubject(
            WikidataDynamicObject w, Object subject, List<ReifyConstruct.Role> roles) {

        for (ReifyConstruct.Role r : roles) {
            if (r == null || r.field() == null || r.field().isBlank()) {
                continue;
            }
            // Only a REFERENCE role counts (e.g. forWork): the witness's work IS the
            // phantom's subject, so the phantom is its denormalized self-copy. A
            // witness that merely names the subject through the IDENTITY role (e.g.
            // nominee) is a DIFFERENT record — see the Diane Warren case (#99).
            if (r.kind() == wikidata.explore.model.RoleKind.IDENTITY) {
                continue;
            }
            if (w.origin(r.field()) == FieldOrigin.QUALIFIER
                    && sameEntity(w.get(r.field()), subject)) {
                return true;
            }
        }
        return false;
    }

    // The witness must match the phantom on EVERY identity field (category + any real
    // role value). Comparing by the phantom's values, not a snapshot, so it stays
    // correct regardless of when qualifiers were loaded.
    private static boolean agreesOnIdentity(
            WikidataDynamicObject w, WikidataDynamicObject phantom,
            List<String> identityFields) {

        for (String f : identityFields) {
            if (!sameEntity(w.get(f), phantom.get(f))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameEntity(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof WikidataDynamicObject x && b instanceof WikidataDynamicObject y) {
            return x.qid() != null && x.qid().equals(y.qid());
        }
        return a.equals(b);
    }

    private static List<WikidataDynamicObject> dedup(
            List<WikidataDynamicObject> objs, List<String> keyFields) {
        java.util.Map<String, WikidataDynamicObject> byKey =
                new java.util.LinkedHashMap<>();
        for (WikidataDynamicObject o : objs) {
            byKey.putIfAbsent(keyOf(o, keyFields), o);
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Collapse records equal on {@code keyFields}, preferring the WORK-anchored
     * copy: the one whose {@code srcField} value equals one of its role values
     * (e.g. source==forWork — the work's own statement) over a nominee's
     * denormalized back-reference (source==the nominee). Robust to qualifier-load
     * timing since it reads final field values, not the reify-time inverse flag.
     */
    private static List<WikidataDynamicObject> dedupPreferringWorkAnchored(
            List<WikidataDynamicObject> objs, List<String> keyFields,
            String srcField, List<ReifyConstruct.Role> roles) {
        java.util.Map<String, WikidataDynamicObject> byKey =
                new java.util.LinkedHashMap<>();
        for (WikidataDynamicObject o : objs) {
            String k = keyOf(o, keyFields);
            WikidataDynamicObject cur = byKey.get(k);
            if (cur == null
                    || (!workAnchored(cur, srcField, roles)
                        && workAnchored(o, srcField, roles))) {
                byKey.put(k, o);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    // The source is (also) a role value — e.g. source==forWork: the statement sits
    // on the work, so this is the canonical copy (not a nominee's back-reference).
    private static boolean workAnchored(
            WikidataDynamicObject o, String srcField, List<ReifyConstruct.Role> roles) {
        String src = refQid(o.get(srcField));
        if (src == null || src.isBlank() || roles == null) {
            return false;
        }
        for (ReifyConstruct.Role r : roles) {
            if (r != null && r.field() != null && !r.field().isBlank()
                    && src.equals(refQid(o.get(r.field())))) {
                return true;
            }
        }
        return false;
    }

    private static String refQid(Object v) {
        return v instanceof WikidataDynamicObject w ? w.qid() : null;
    }

    private static String keyOf(WikidataDynamicObject o, List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (String f : fields) {
            sb.append('|').append(valueKey(o.get(f)));
        }
        return sb.toString();
    }

    private static String valueKey(Object v) {
        if (v instanceof WikidataDynamicObject w) {
            return w.qid() != null ? w.qid() : w.getDisplayName();
        }
        if (v instanceof Collection<?> col) {
            return col.stream().map(TransformEngine::valueKey).sorted()
                    .collect(java.util.stream.Collectors.joining(","));
        }
        return String.valueOf(v);
    }

    private static String display(Object v) {
        return v instanceof WikidataDynamicObject w ? w.getDisplayName()
                : String.valueOf(v);
    }

    private static List<WikidataDynamicObject> referencedObjects(Object value) {
        if (value instanceof WikidataDynamicObject w) {
            return List.of(w);
        }
        if (value instanceof Collection<?> col) {
            List<WikidataDynamicObject> out = new ArrayList<>();
            for (Object o : col) {
                if (o instanceof WikidataDynamicObject w) {
                    out.add(w);
                }
            }
            return out;
        }
        return List.of();
    }
}
