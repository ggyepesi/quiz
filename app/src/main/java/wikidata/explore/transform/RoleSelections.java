package wikidata.explore.transform;

import objectview.Viewable;
import wikidata.explore.extract.WikidataDynamicObject;
import datasource.schema.FieldType;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.MembershipPattern;
import wikidata.explore.model.RoleSelection;
import wikidata.explore.model.Selection;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Defines and materializes field-derived semantic roles over canonical entities. */
public final class RoleSelections {
    private RoleSelections() {}

    /** Explicit ROLE selections plus a compatibility inference for statement fields
     * targeting referenced-only classes (Nominee, ForWork, Ceremony, ...). */
    public static List<RoleSelection> definitions(GeneratedProjectModel model) {
        LinkedHashMap<String, RoleSelection> out = new LinkedHashMap<>();
        if (model == null) return List.of();
        java.util.Set<String> representations =
                wikidata.explore.model.EntityRepresentations
                        .representationClassNames(model);
        for (Selection selection : model.selections()) {
            if (selection instanceof RoleSelection role && role.isConfigured()) {
                out.put(role.key().toLowerCase(java.util.Locale.ROOT), role.copy());
            }
        }
        for (GeneratedClassModel owner : model.classes()) {
            if (owner == null || !owner.reifiesStatements()) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                if (field == null || field.type() != FieldType.ENTITY) continue;
                GeneratedClassModel target = model.findClass(field.entityClassName());
                if (target == null || MembershipPattern.of(target, model)
                        != MembershipPattern.REFERENCED
                        || representations.contains(target.className())) continue;
                String key = (target.className() + " [" + owner.className() + "."
                        + field.name() + "]").toLowerCase(java.util.Locale.ROOT);
                out.putIfAbsent(key, new RoleSelection(
                        target.className(), owner.className(), field.name()));
            }
        }
        return List.copyOf(out.values());
    }

    public static Map<String, List<Viewable>> materialize(
            GeneratedProjectModel model, Collection<WikidataDynamicObject> pool) {
        LinkedHashMap<String, List<Viewable>> result = new LinkedHashMap<>();
        if (pool == null) return result;
        for (RoleSelection role : definitions(model)) {
            LinkedHashMap<String, Viewable> members = new LinkedHashMap<>();
            for (WikidataDynamicObject owner : pool) {
                if (owner == null || !owner.directClassNames().contains(role.ownerClassName())) {
                    continue;
                }
                add(owner.get(role.fieldName()), members);
            }
            result.put(role.key(), List.copyOf(members.values()));
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * Class names used as contextual roles rather than final representations.
     *
     * <p>The statement-field inference predates explicit ROLE selections, but a role
     * explicitly named by an entity-representation rule is a role regardless of its
     * population declaration. Requiring it to look REFERENCED made an UNBOUNDED role
     * such as History's PositionHolder get stamped back onto a Person after kind
     * classification had deliberately retracted it.</p>
     */
    public static java.util.Set<String> roleClassNames(GeneratedProjectModel model) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        if (model == null) return names;
        java.util.Set<String> representations =
                wikidata.explore.model.EntityRepresentations
                        .representationClassNames(model);
        for (wikidata.explore.model.EntityRepresentationRule rule
                : model.entityRepresentationRules()) {
            if (rule != null && rule.isConfigured()) names.add(rule.roleClassName());
        }
        for (GeneratedClassModel owner : model.classes()) {
            if (owner == null || !owner.reifiesStatements()) continue;
            for (GeneratedFieldModel field : owner.fields()) {
                if (field == null || field.type() != FieldType.ENTITY) continue;
                GeneratedClassModel target = model.findClass(field.entityClassName());
                if (target != null && MembershipPattern.of(target, model)
                        == MembershipPattern.REFERENCED
                        && !representations.contains(target.className())) {
                    names.add(target.className());
                }
            }
        }
        return java.util.Collections.unmodifiableSet(names);
    }

    private static void add(Object value, Map<String, Viewable> into) {
        if (value instanceof Viewable member) {
            String id = member.getIdentifier();
            if (id != null && !id.isBlank()) {
                into.putIfAbsent(member.identityTypeName() + "\0" + id, member);
            }
        } else if (value instanceof Collection<?> values) {
            values.forEach(item -> add(item, into));
        }
    }
}
