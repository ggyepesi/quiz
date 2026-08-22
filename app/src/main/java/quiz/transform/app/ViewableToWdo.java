package quiz.transform.app;

import objectview.field.DynamicFields;
import objectview.media.ImagePane;
import objectview.utils.swing.CachedImage;
import objectview.Viewable;
import domain.DomainModel;
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

    private record GroupKey(String memberType, String id) {}

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
        // One converted object per group identity. The bound tree is the AUTHORITATIVE
        // one — in TransformApp the edited EditableGroup copy — so it is converted first
        // and registered here. A member's `groups` back-reference still points at the
        // group loaded from the previous snapshot; it resolves to the object registered
        // here instead of adding a second representation of the same group. Converting
        // the loaded object instead would have kept identity intact but silently dropped
        // every added/removed child (including a TypeSpecGroup) on save.
        Map<GroupKey, WikidataDynamicObject> groups = new LinkedHashMap<>();
        List<ConvertedGroupRoot> convertedGroups = new ArrayList<>();
        if (groupRootBindings != null) {
            for (objectview.viewconfig.DomainGroupRoot binding : groupRootBindings) {
                Object converted = convert(
                        binding.root(), seen, groups, schema, binding.memberType());
                if (converted instanceof WikidataDynamicObject root) {
                    convertedGroups.add(new ConvertedGroupRoot(binding.memberType(), root));
                }
            }
        }
        List<WikidataDynamicObject> convertedMembers = new ArrayList<>();
        for (Viewable m : memberRoots == null ? List.<Viewable>of() : memberRoots) {
            Object c = convert(m, seen, groups, schema, null);
            if (c instanceof WikidataDynamicObject w) {
                convertedMembers.add(w);
            }
        }
        requireIdentities(seen.values());
        reportIdentifierCollisions(seen.values());
        return new ConvertedDomain(
                List.copyOf(convertedMembers),
                List.copyOf(convertedGroups),
                List.copyOf(seen.values()));
    }

    /** The authoritative conversion of the group with this identifier. A known member type
     *  IS the answer — the same path in another member type's tree is a DIFFERENT group,
     *  so no borrowing across trees. Only an unscoped lookup falls back to the one tree
     *  that holds the identity. If several trees hold it, an unbound member supplies no
     *  principled way to choose; returning null preserves its old reference instead. */
    private static WikidataDynamicObject canonicalGroup(
            Map<GroupKey, WikidataDynamicObject> groups, String memberType, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        if (memberType != null) {
            return groups.get(new GroupKey(memberType, id));
        }
        // A group genuinely registered outside any bound tree is an exact unscoped
        // match. Prefer it before considering the scoped trees with the same path.
        WikidataDynamicObject unscoped = groups.get(new GroupKey(null, id));
        if (unscoped != null) return unscoped;
        WikidataDynamicObject match = null;
        for (Map.Entry<GroupKey, WikidataDynamicObject> entry : groups.entrySet()) {
            if (!id.equals(entry.getKey().id())) continue;
            if (match != null && match != entry.getValue()) return null;
            match = entry.getValue();
        }
        return match;
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
            // Sharing an identifier across DIFFERENT types decides nothing: ⟨typeKey, id⟩
            // keeps those apart by construction, and it is now the NORMAL case — an owned
            // component borrows its owner's identity, so reporting it would print a block
            // per component. Only same-type copies, which MERGE, are worth reading about.
            boolean sameTypeCopies = byType.values().stream().anyMatch(v -> v.size() > 1);
            if (!sameTypeCopies) {
                continue;
            }
            report.append("  \"").append(e.getKey()).append("\"  ")
                    .append("— same-type copies merged; different types kept separate")
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
            Map<GroupKey, WikidataDynamicObject> groups, DomainModel schema,
            String groupMemberType) {
        if (v == null) {
            return null;
        }
        if (v instanceof WikidataDynamicObject w) {
            if (DynamicViewableGroup.isGroup(w)) {
                // A group carried over from the previous snapshot yields to the bound tree.
                WikidataDynamicObject canonical =
                        canonicalGroup(groups, groupMemberType, w.getIdentifier());
                if (canonical != null) return canonical;
                // "Absent from the tree" means "removed" only where a bound tree OWNS
                // this scope and can therefore speak for it. With no tree for the scope
                // — a pool() save with no bindings at all, or a member type nobody bound
                // — absence means unknown, and dropping the group would be data loss.
                return ownsScope(groups, groupMemberType) ? null : w;
            }
            // Loaded snapshot members are already WDOs. Copy the carrier so their group
            // references can be rebound without mutating the domain being saved.
            WikidataDynamicObject cached = seen.get(w);
            if (cached != null) return cached;
            WikidataDynamicObject copy = copyCarrier(w);
            seen.put(w, copy);
            // The owning tree is this carrier's OWN member type — never the tree it was
            // reached through. Inheriting would point a nested instance of an unbound type
            // at the wrong tree, where its groups look absent and would be dropped. Left
            // unscoped, they resolve against whichever tree actually holds them.
            String scope = memberTypeFor(w, groups, schema);
            // Every field is converted, at every depth: a group is recognized by BEING a
            // group, never by sitting under a field called "groups". A group reached by
            // any other name, or through a nested member, is rebound the same way.
            for (Map.Entry<String, Object> field : w.dynamicFields().entrySet()) {
                Object value = convert(field.getValue(), seen, groups, schema, scope);
                if (value != null) copy.put(field.getKey(), value);
            }
            return copy;
        }
        if (v instanceof Viewable q) {
            WikidataDynamicObject cached = seen.get(q);
            if (cached != null) {
                return cached;
            }
            if (q instanceof objectview.group.ViewableGroup<?>) {
                WikidataDynamicObject canonical =
                        canonicalGroup(groups, groupMemberType, q.getIdentifier());
                if (canonical != null) {
                    return canonical;
                }
            }
            // A VALUE object is inlined, not pooled — it needs no identity (its display
            // name is just a label). Everything else is an entity, keyed by identity.
            boolean value = q instanceof quiz.ValueObject;
            String id = value ? null : q.getIdentifier();
            if (!value && (id == null || id.isBlank())) {
                id = q.getDisplayName();
            }
            WikidataDynamicObject o = new WikidataDynamicObject(id, q.getDisplayName());
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
            // Registered BEFORE the fields are copied, so the members reached through this
            // group already resolve their own `groups` back-reference to it.
            if (q instanceof objectview.group.ViewableGroup<?>
                    && id != null && !id.isBlank()) {
                GroupKey key = new GroupKey(groupMemberType, id);
                // canonicalGroup() above returned before construction when this key was
                // already registered, so the key is necessarily new here.
                groups.putIfAbsent(key, o);
                // Groups share a logical display class, but their stable persistence
                // identity is scoped to the member-type tree that owns them.
                if (groupMemberType != null && !groupMemberType.isBlank()) {
                    o.typeKey(quiz.transform.EditableGroup.GROUP_TYPE
                            + "@" + groupMemberType);
                }
            }
            // Copy every field, whichever representation — no instanceof branch.
            objectview.field.FieldSet set = q.fields();
            objectview.field.FieldSchema declared =
                    schema == null ? null : schema.fieldSchema(concreteType);
            if (declared != null) {
                set = new objectview.field.SchemaFieldSet(set, declared);
            }
            // A group belongs to the tree it hangs in, so it passes that tree down to its
            // children and members. Anything else is scoped by its own bound member type,
            // exactly like a loaded carrier.
            String nestedScope = q instanceof objectview.group.ViewableGroup<?>
                    ? groupMemberType : memberTypeFor(q, groups, schema);
            for (objectview.field.FieldRef ref : set.fields()) {
                // Skip the by-contract computed fields — the rendered return values of
                // getIdentifier()/getDisplayName(), not stored data. Recognized by ROLE
                // (what they ARE), never by the fabricated key name: a real stored field
                // that happens to reuse the key has role NONE and is kept as data.
                if (ref.role() == objectview.field.FieldRole.IDENTITY
                        || ref.role() == objectview.field.FieldRole.DISPLAY) {
                    continue;
                }
                Object cv = convert(
                        set.read(ref.name()), seen, groups, schema, nestedScope);
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
                Object cv = convert(item, seen, groups, schema, groupMemberType);
                if (cv != null) out.add(cv);
            }
            return out;
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : m.entrySet()) {
                Object cv = convert(entry.getValue(), seen, groups, schema, groupMemberType);
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

    /** The bound member type this instance belongs to, or null when the bindings do not
     *  name exactly one — a group converted while walking a member registers under no
     *  member type at all, so the scan must tolerate an unscoped key. */
    private static String memberTypeFor(
            Viewable member, Map<GroupKey, WikidataDynamicObject> groups,
            DomainModel schema) {
        List<String> matches = groups.keySet().stream().map(GroupKey::memberType)
                .filter(java.util.Objects::nonNull).distinct()
                .filter(type -> type.equals(member.typeName())
                        || schema != null && schema.isInstanceOf(member, type))
                .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    /** Whether a bound tree owns this member type, and can therefore be taken as the
     *  complete account of which of its groups still exist. */
    private static boolean ownsScope(
            Map<GroupKey, WikidataDynamicObject> groups, String memberType) {
        return memberType != null && groups.keySet().stream()
                .anyMatch(key -> memberType.equals(key.memberType()));
    }

    private static WikidataDynamicObject copyCarrier(WikidataDynamicObject source) {
        WikidataDynamicObject copy = new WikidataDynamicObject(
                source.getIdentifier(), source.getDisplayName());
        copy.type(source.typeName());
        copy.directClasses(source.directClassNames());
        copy.typeKey(source.typeKey());
        copy.referenceLabel(source.getReferenceLabel());
        copy.valueObject(source.isValueObject());
        copy.wikidataEntityMissing(source.isWikidataEntityMissing());
        copy.fieldStatuses(source.fieldStatuses());
        copy.dynamicFieldSchema(source.dynamicFieldSchema());
        source.fieldOrigins().forEach(copy::recordOrigin);
        return copy;
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
