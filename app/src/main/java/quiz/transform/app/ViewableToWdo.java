package quiz.transform.app;

import objectview.field.DynamicFields;
import objectview.media.ImagePane;
import objectview.utils.swing.CachedImage;
import objectview.Viewable;
import quiz.transform.ui.DomainModel;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.extract.WikidataMediaValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a Viewable graph to a {@link WikidataDynamicObject} pool so a transform
 * result can be SAVED as a snapshot (the store is WDO-based). A WDO passes through;
 * a {@link DynamicFields} object (e.g. a PROJECT-derived DynamicViewable) copies its
 * property map; a hand-written Viewable (State, NobelPrize, …) is materialized by
 * reflection. Viewable-valued fields are converted recursively (deduped by
 * identity, cycle-safe). The wikidata bridge for the transform layer.
 */
public final class ViewableToWdo {

    private ViewableToWdo() {}

    public record ConvertedDomain(
            List<WikidataDynamicObject> memberRoots,
            List<ConvertedGroupRoot> groupRootBindings,
            List<WikidataDynamicObject> allObjects) {
        public List<WikidataDynamicObject> groupRoots() {
            return groupRootBindings.stream().map(ConvertedGroupRoot::root).toList();
        }
    }

    public record ConvertedGroupRoot(String memberType, WikidataDynamicObject root) {}

    public static List<WikidataDynamicObject> pool(Collection<? extends Viewable> members) {
        return pool(members, null);
    }

    /**
     * Converts with the producing domain's canonical schema. The same declared
     * fields are therefore enumerated for typed and dynamic instances; observed
     * extra fields are retained by {@code SchemaFieldSet}.
     */
    public static List<WikidataDynamicObject> pool(
            Collection<? extends Viewable> members, DomainModel schema) {
        return convertDomain(members, List.of(), schema).memberRoots();
    }

    public static ConvertedDomain convertDomain(
            Collection<? extends Viewable> memberRoots,
            Collection<objectview.viewconfig.DomainGroupRoot> groupRootBindings,
            DomainModel schema) {
        Map<Object, WikidataDynamicObject> seen = new IdentityHashMap<>();
        List<WikidataDynamicObject> convertedMembers = new ArrayList<>();
        for (Viewable m : memberRoots == null ? List.<Viewable>of() : memberRoots) {
            Object c = convert(m, seen, schema);
            if (c instanceof WikidataDynamicObject w) {
                convertedMembers.add(w);
            }
        }
        List<ConvertedGroupRoot> convertedGroups = new ArrayList<>();
        if (groupRootBindings != null) {
            for (objectview.viewconfig.DomainGroupRoot binding : groupRootBindings) {
                // Prefer the member-referenced group already converted via a member's
                // `groups` reference, so the binding and the member path resolve to ONE
                // ⟨type, id⟩ and the transform-app's EditableGroup copy never enters the
                // snapshot. Fall back to converting the binding root only when no member
                // reaches it (e.g. an empty group has nothing pointing back at it).
                String rootId = binding.root() == null
                        ? null : binding.root().getIdentifier();
                WikidataDynamicObject root = memberReferencedGroup(seen, rootId);
                if (root == null) {
                    Object c = convert(binding.root(), seen, schema);
                    root = c instanceof WikidataDynamicObject w ? w : null;
                }
                if (root != null) {
                    convertedGroups.add(new ConvertedGroupRoot(binding.memberType(), root));
                }
            }
        }
        requireIdentities(seen.values());
        reportIdentifierCollisions(seen.values());
        return new ConvertedDomain(
                List.copyOf(convertedMembers),
                List.copyOf(convertedGroups),
                List.copyOf(seen.values()));
    }

    /** The already-converted group (reached via a member's group reference) with this
     *  identifier — the member-referenced group, never a transform-app EditableGroup
     *  copy. Null when no member reaches a group with this id (e.g. an empty group). */
    private static WikidataDynamicObject memberReferencedGroup(
            Map<Object, WikidataDynamicObject> seen, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (Map.Entry<Object, WikidataDynamicObject> e : seen.entrySet()) {
            if (e.getKey() instanceof quiz.transform.EditableGroup) {
                continue;   // never resolve a binding to a transform-app editing copy
            }
            if (id.equals(e.getValue().getIdentifier())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Report explicitly when distinct data instances share one identifier. Cross-type
     *  shares are kept apart by ⟨typeKey, id⟩. Same-type copies are deliberately
     *  union-merged by the store (a rich carrier plus field-poor references is normal);
     *  only conflicting canonical names for the same ⟨typeKey, id⟩ are ambiguous. */
    private static void reportIdentifierCollisions(
            Collection<WikidataDynamicObject> entities) {
        // Each entity here is a distinct SOURCE object (seen is an identity map), so two
        // entries under one id are two different objects claiming the same identifier.
        Map<String, List<WikidataDynamicObject>> byId = new LinkedHashMap<>();
        for (WikidataDynamicObject w : entities) {
            if (w.isValueObject() || w.getIdentifier() == null || w.getIdentifier().isBlank()) {
                continue;
            }
            byId.computeIfAbsent(w.getIdentifier(), k -> new ArrayList<>()).add(w);
        }

        StringBuilder report = new StringBuilder();
        StringBuilder ambiguous = new StringBuilder();
        for (Map.Entry<String, List<WikidataDynamicObject>> e : byId.entrySet()) {
            List<WikidataDynamicObject> instances = e.getValue();
            if (instances.size() < 2) {
                continue;
            }
            Map<String, List<WikidataDynamicObject>> byType = new LinkedHashMap<>();
            for (WikidataDynamicObject instance : instances) {
                byType.computeIfAbsent(instance.typeKey(), ignored -> new ArrayList<>())
                        .add(instance);
            }
            boolean sameTypeCopies = byType.values().stream().anyMatch(v -> v.size() > 1);
            report.append("  \"").append(e.getKey()).append("\"  ")
                    .append(sameTypeCopies
                            ? "— same-type copies merged; different types kept separate"
                            : "— kept separate by ⟨type, id⟩")
                    .append('\n');
            for (WikidataDynamicObject w : instances) {
                report.append("      · ⟨").append(w.typeKey()).append(", ")
                        .append(w.getIdentifier()).append("⟩  name=\"").append(w.getDisplayName())
                        .append("\"  fields=").append(w.dynamicFields().keySet()).append('\n');
            }
            for (Map.Entry<String, List<WikidataDynamicObject>> typed : byType.entrySet()) {
                List<String> names = typed.getValue().stream()
                        .map(WikidataDynamicObject::getDisplayName)
                        .filter(name -> name != null && !name.isBlank())
                        .distinct()
                        .toList();
                if (names.size() > 1) {
                    ambiguous.append("  ⟨").append(typed.getKey()).append(", ")
                            .append(e.getKey()).append("⟩ has conflicting names ")
                            .append(names).append('\n');
                }
            }
        }
        if (ambiguous.length() > 0) {
            throw new IllegalArgumentException(
                    "Snapshot has conflicting typed identifiers:\n" + ambiguous);
        }
        if (report.length() > 0) {
            System.err.println("Snapshot: identifier(s) shared by distinct instances "
                    + "(⟨type, id⟩ keeps different types apart; consistent copies merge):\n"
                    + report);
        }
    }

    /** Fail loud if any converted entity has a BLANK identity. The snapshot store keys
     *  by qid and SILENTLY DROPS blank-qid entities (their refs then serialize as null),
     *  so a blank identity is data loss, not a cosmetic issue — surface it at save
     *  instead of shipping a broken snapshot. */
    private static void requireIdentities(Collection<WikidataDynamicObject> entities) {
        Map<String, Integer> blankByType = new LinkedHashMap<>();
        for (WikidataDynamicObject w : entities) {
            if (w.isValueObject()) {
                continue;   // value objects are inlined — a blank id is expected/correct
            }
            if (w.getIdentifier() == null || w.getIdentifier().isBlank()) {
                blankByType.merge(
                        w.typeName() == null || w.typeName().isBlank() ? "?" : w.typeName(),
                        1, Integer::sum);
            }
        }
        if (!blankByType.isEmpty()) {
            int total = blankByType.values().stream().mapToInt(Integer::intValue).sum();
            throw new IllegalStateException(
                    total + " entity(ies) have a BLANK identity and would be silently "
                    + "dropped from the snapshot (blank getIdentifier() -> no qid -> "
                    + "nested refs become null). Give them a non-blank identity. "
                    + "By type: " + blankByType);
        }
    }

    private static Object convert(
            Object v, Map<Object, WikidataDynamicObject> seen,
            DomainModel schema) {
        if (v == null) {
            return null;
        }
        if (v instanceof WikidataDynamicObject w) {
            return w;
        }
        if (v instanceof Viewable q) {
            WikidataDynamicObject cached = seen.get(q);
            if (cached != null) {
                return cached;
            }
            // A VALUE object is inlined, not pooled — it needs no identity (its display
            // name is just a label). Everything else is an entity, keyed by identity.
            boolean value = q instanceof quiz.ValueObject;
            String id = value ? null : q.getIdentifier();
            if (!value && (id == null || id.isBlank())) {
                id = q.getDisplayName();
            }
            WikidataDynamicObject o = new WikidataDynamicObject(id, q.getDisplayName());
            if (q instanceof quiz.source.Anchorable anchorable) {
                o.anchor(anchorable.anchor());
            }
            String concreteType = schema == null
                    ? q.directClassNames().stream().findFirst().orElse(q.typeName())
                    : schema.mostSpecificClass(q);
            if (concreteType == null || concreteType.isBlank()) concreteType = q.typeName();
            o.type(concreteType);
            o.directClasses(schema == null
                    ? q.directClassNames() : schema.directClasses(q));
            // Use the stable LOGICAL class name, shared by the editable model, DomainModel,
            // curation links and manual buildView classes. A Java package/class refactor
            // must not change persisted object identity.
            o.typeKey(q.typeName());
            o.referenceLabel(q.getReferenceLabel());
            o.valueObject(value);
            seen.put(q, o);
            // Copy every field, whichever representation — no instanceof branch.
            objectview.field.FieldSet set = q.fields();
            objectview.field.FieldSchema declared =
                    schema == null ? null : schema.fieldSchema(concreteType);
            if (declared != null) {
                set = new objectview.field.SchemaFieldSet(set, declared);
            }
            for (objectview.field.FieldRef ref : set.fields()) {
                // The source identity is carried by the dynamic carrier contract and
                // persisted explicitly by the snapshot DTO, never duplicated as data.
                if (q instanceof quiz.source.Anchorable && "anchor".equals(ref.name())) {
                    continue;
                }
                Object cv = convert(set.read(ref.name()), seen, schema);
                // Skip null AND blank scalars — a blank value carries no information and
                // would otherwise render as an empty field row.
                if (cv != null && !(cv instanceof String s && s.isBlank())) {
                    o.put(ref.name(), cv);
                }
            }
            return o;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object item : c) {
                Object cv = convert(item, seen, schema);
                if (cv != null) out.add(cv);
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                Object cv = convert(entry.getValue(), seen, schema);
                if (cv != null) {
                    out.put(String.valueOf(entry.getKey()), cv);
                }
            }
            return out;
        }
        // A live Swing ImagePane can't be serialized into the pool — persist it as a
        // metadata-only WikidataMediaValue (label + source url + svg), which round-trips
        // and renders back to an ImagePane on load via FieldKind.MEDIA.
        if (v instanceof ImagePane p) {
            return toMediaValue(p);
        }
        // A hand-written domain's enum (e.g. NobelPrize.Domain) can't round-trip through
        // the pool's locked-down Jackson typing — store its display string, which becomes
        // a plain scalar facet dimension.
        if (v instanceof Enum<?> e) {
            return e.toString();
        }
        return v;
    }

    private static Object toMediaValue(ImagePane p) {
        CachedImage image = p.getCachedImage();
        if (image == null) {
            return null;   // an in-memory-only image (no source) — nothing to persist
        }
        String url = image.sourceUrl();
        if (url == null || url.isBlank()) {
            return null;
        }
        return new WikidataMediaValue(p.getTitle(), url, image.isSvg());
    }
}
