package wikidata.explore.transform;

import wikidata.WikidataIds;

import wikidata.explore.extract.WikidataDynamicObject;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Stamps a class instance's ENTITY-field referents with the class the field
 * declares (its {@code entityClassName}), so a reference resolves to a real
 * (bare) class and reads as "Nominee: Meryl Streep" — instead of collapsing to a
 * bare, untyped label ({@link BareReferenceCollapse} turns an unstamped referent
 * into a plain string, which is why nominee/forWork looked like "not clear what
 * this references").
 *
 * <p>The declared target may be either a modeled class (e.g. {@code Nominee}) OR
 * a named VOCABULARY {@link wikidata.explore.model.Selection} (e.g. {@code
 * category -> OscarCategories}, where the closed category vocabulary IS the
 * referent's type). Only stamps when the target actually EXISTS as one of those,
 * so a dangling {@code entityClassName} (a typo, or a target since removed) is
 * left as an untyped label rather than conjuring a phantom type. Membership is
 * additive while the referent is still role-only: when the same pooled entity is
 * reached through fields declaring different targets, it carries every declared role
 * (for example both Nominee
 * and ForWork). Once classification has assigned a non-role kind, legacy role
 * classes are selections and are not stamped back onto the entity. For a previously
 * untyped referent the legacy singular carrier
 * type/storage key is selected deterministically from the complete role set, not
 * from whichever field happened to be visited first. Existing real types are
 * preserved. Roles remain semantic memberships, not separate copies. The referents
 * are shared pool instances (QID identity), so the accumulated memberships are
 * visible everywhere the entity is referenced.
 */
public final class ReferentClassStamp {

    private ReferentClassStamp() {}

    /** @return the number of new referent class memberships assigned. */
    public static int apply(
            GeneratedProjectModel model,
            Collection<WikidataDynamicObject> instances) {

        if (model == null || instances == null) {
            return 0;
        }

        // className -> (fieldName -> declared entityClassName), only for ENTITY
        // fields whose declared target is a real class in the model. Effective fields:
        // an inherited field stamps for a subclass's members exactly as for the base's.
        Map<String, Map<String, String>> fieldClasses = new LinkedHashMap<>();
        // className -> the fields that lie on a reference cycle (#394).
        Map<String, java.util.Set<String>> cyclicFields = new LinkedHashMap<>();
        wikidata.explore.model.ReferenceCycles cycles =
                wikidata.explore.model.ReferenceCycles.of(model);
        for (GeneratedClassModel c : model.classes()) {
            if (c == null) {
                continue;
            }
            Map<String, String> byField = new LinkedHashMap<>();
            for (GeneratedFieldModel f : c.effectiveFields(model)) {
                if (f == null || f.type() != FieldType.ENTITY) {
                    continue;
                }
                String target = f.entityClassName();
                if (target == null || target.isBlank()
                        || (model.findClass(target) == null
                                && model.findSelection(target) == null)) {
                    continue;
                }
                byField.put(f.name(), target);
                if (cycles.onCycle(c.className(), f)) {
                    cyclicFields.computeIfAbsent(c.className(),
                            ignored -> new java.util.HashSet<>()).add(f.name());
                }
            }
            if (!byField.isEmpty()) {
                fieldClasses.put(c.className(), byField);
            }
        }
        if (fieldClasses.isEmpty()) {
            return 0;
        }

        int stamped = 0;
        java.util.Set<String> roleClasses = RoleSelections.roleClassNames(model);
        // A kind admitted by evidence (Person: P31 = Q5) is assigned by classification,
        // never by the field that holds a value — those values are kind candidates.
        java.util.Set<String> admittedKinds = wikidata.explore.model.EntityRepresentations.admissions(model).stream()
                .map(wikidata.explore.model.EntityRepresentations.Admission::className)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<WikidataDynamicObject, Boolean> originallyTyped = new IdentityHashMap<>();
        for (WikidataDynamicObject o : instances) {
            if (o == null || o.typeName() == null) {
                continue;
            }
            Map<String, String> byField = fieldClasses.get(o.typeName());
            if (byField == null) {
                continue;
            }
            for (Map.Entry<String, String> e : byField.entrySet()) {
                if (admittedKinds.contains(e.getValue())) continue;
                stamped += stamp(o.get(e.getKey()), e.getValue(), model, roleClasses,
                        originallyTyped, cyclicFields.getOrDefault(o.typeName(),
                                java.util.Set.of()).contains(e.getKey()));
            }
        }
        // Unrelated role classes have no "most specific" winner. Normalize their
        // carrier type independently of model/field/query traversal order; callers
        // must still use the complete directClasses set for membership and a selected
        // class for contextual rendering. The rule matches the save path's
        // (deepest-first, then alphabetical), so a role that IS a subclass of another
        // still wins — otherwise the in-memory carrier and the persisted type would
        // disagree the moment the model grows a hierarchy.
        originallyTyped.forEach((referent, hadType) -> {
            if (hadType || !referent.hasTypeStamp()) return;
            List<String> classes = referent.directClassNames().stream().sorted().toList();
            if (classes.isEmpty()) return;
            String carrier = mostSpecific(classes, model);
            referent.directClasses(new LinkedHashSet<>(classes));
            referent.type(carrier);
            referent.typeKey(carrier);
        });
        return stamped;
    }

    /** The deepest class in the model's hierarchy, ties broken alphabetically — the rule
     *  {@code WikidataDynamicObjectJsonStore.mostSpecificClass} applies when it persists
     *  the same object, restated here against the generated model. */
    private static String mostSpecific(List<String> classes, GeneratedProjectModel model) {
        String best = null;
        int bestDepth = -1;
        for (String candidate : classes) {
            int depth = 0;
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (String current = candidate; current != null && seen.add(current);) {
                depth++;
                GeneratedClassModel c = model.findClass(current);
                String base = c == null ? null : c.baseClassName();
                current = base == null || base.isBlank() ? null : base;
            }
            if (depth > bestDepth
                    || (depth == bestDepth && candidate.compareTo(best) < 0)) {
                best = candidate;
                bestDepth = depth;
            }
        }
        return best;
    }

    private static int stamp(Object value, String className,
                             GeneratedProjectModel model,
                             java.util.Set<String> roleClasses,
                             Map<WikidataDynamicObject, Boolean> originallyTyped,
                             boolean onCycle) {
        if (value instanceof WikidataDynamicObject w) {
            if (w.qid() != null && WikidataIds.isQid(w.qid())) {
                originallyTyped.putIfAbsent(w, w.hasTypeStamp());
            }
            // A field on a reference cycle links members; it is not another membership
            // producer. Otherwise one root constellation plus P47 neighbours, one Person
            // plus spouse, or a Character's episodes and their characters close
            // transitively over everything reachable and silently change the authored
            // population (ReferenceCycles, #394). Existing members are already stamped,
            // so leaving a new bare referent bare preserves the link without admitting
            // it as another class instance.
            if (onCycle && !originallyTyped.getOrDefault(w, false)) {
                return 0;
            }
            // A field declaration constrains its value; it does not reclassify an
            // entity already configured as an unrelated class. In particular, a
            // PositionWithHolders QID encountered in a Person-valued qualifier stays
            // a position. Compatibility is the same question the mapper asks before
            // storing a value in that field — inheritance, or an explicit
            // role -> representation rule — so both ask it the same way.
            if (originallyTyped.getOrDefault(w, false)
                    && !wikidata.explore.model.EntityRepresentations.fieldAccepts(
                            model, className, w.directClassNames())) {
                return 0;
            }
            // Roles are materialized from their owning field by RoleSelections. Before
            // kind classification, their legacy classes keep bare referents stamped and
            // allow one entity to occupy several roles. After classification, putting a
            // role class back would let persistence choose it as the carrier again
            // (Person -> Nominee), undoing the classifier's conclusion.
            if (w.hasTypeStamp() && roleClasses.contains(className)
                    && w.directClassNames().stream().anyMatch(c -> !roleClasses.contains(c))) {
                return 0;
            }
            // Already a member: the class itself, or a subclass of it — a
            // PositionWithHolders IS a Position, so a Position-typed field adds nothing.
            // Assigning the base as a second direct class would only change what the
            // entity is counted as.
            if (w.directClassNames().stream()
                    .anyMatch(existing -> model.isSameOrSubclass(existing, className))) {
                return 0;
            }
            if (w.qid() != null && WikidataIds.isQid(w.qid())) {
                // The reverse order is the same fact: when the base was encountered
                // first, replace that direct claim with the more-specific class rather
                // than keeping both. Otherwise declaration order decides whether an
                // object carries {Base, Subclass} or only {Subclass}.
                w.directClassNames().stream()
                        .filter(existing -> model.isSameOrSubclass(className, existing))
                        .toList().forEach(w::removeClass);
                w.assignClass(className);
                return 1;
            }
            return 0;
        }
        if (value instanceof List<?> list) {
            int n = 0;
            for (Object item : list) {
                n += stamp(item, className, model, roleClasses, originallyTyped,
                        onCycle);
            }
            return n;
        }
        return 0;
    }
}
