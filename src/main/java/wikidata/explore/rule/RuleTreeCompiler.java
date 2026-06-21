package wikidata.explore.rule;

import wikidata.explore.model.RuleDirection;
import wikidata.explore.model.*;

import java.util.HashSet;
import java.util.Set;

public final class RuleTreeCompiler {

    private RuleTreeCompiler() {
    }

    public static RuleNode compileProject(GeneratedProjectModel project) {
        return compileClass(project.rootClass(), project);
    }

    /** Compiles a single class with no project context — child-object fields
     *  can't resolve their referenced class's own fields (used for previews). */
    public static RuleNode compileClass(GeneratedClassModel clazz) {
        return compileClass(clazz, null);
    }

    /** Compiles a class; with a {@code project}, a child-object field's
     *  referenced class is looked up so the child node carries that class's
     *  own fields (e.g. a Star's apparent magnitude). */
    public static RuleNode compileClass(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        return compileClass(clazz, project, new HashSet<>());
    }

    private static RuleNode compileClass(
            GeneratedClassModel clazz,
            GeneratedProjectModel project,
            Set<String> visited) {

        FieldSourceMapping m = clazz.instanceMapping();

        RuleNode node = new RuleNode(clazz.className(), decap(clazz.className()));

        node.sourceQid(m.sourceQid());
        node.sourceLabel(m.sourceLabel());
        // Multi-QID membership: instance-of sourceQid OR any additional type
        // (e.g. + zodiacal constellation to admit Aries/Cancer).
        for (String extra : m.additionalTypeQids()) {
            node.addAdditionalSourceQid(extra);
        }
        node.propertyPid(m.propertyPid().isBlank() ? "P31" : m.propertyPid());
        node.propertyLabel(m.propertyLabel().isBlank()
                ? "instance of"
                : m.propertyLabel());
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.limit(m.limit());
        node.requireSitelink(m.requireSitelink());
        node.labelConfig(new RuleLabelConfig(
                m.requireLabel(),
                m.labelLanguage()));

        visited.add(clazz.className());

        for (GeneratedFieldModel f : clazz.fields()) {
            if (f.isNameField()) {
                continue;
            }
            compileFieldIntoNode(node, f, project, visited);
        }

        return node;
    }

    public static RuleIncludedField compileField(
            GeneratedFieldModel field) {

        if (field == null || field.mapping().propertyPid().isBlank()) {
            return null;
        }

        FieldSourceMapping m = field.mapping();

        RuleIncludedField included = new RuleIncludedField(
                field.name(),
                m.propertyPid(),
                m.propertyLabel(),
                fieldKindFor(field),
                !field.required());

        included.collection(field.collection());

        return included;
    }

    private static void compileFieldIntoNode(
            RuleNode parent,
            GeneratedFieldModel field,
            GeneratedProjectModel project,
            Set<String> visited) {

        if (field == null || field.mapping().propertyPid().isBlank()) {
            return;
        }

        // DBpedia-sourced fields aren't Wikidata properties; they're filled by a
        // separate post-extraction enrichment (DBpediaEnrichment), so they must
        // not be compiled into the Wikidata rule tree.
        if (field.mapping().sourceType() == FieldSourceType.DBPEDIA) {
            return;
        }

        FieldProductionKind kind = resolvedProductionKind(field);
        FieldSourceMapping m = field.mapping();

        // A numeric filter on this field (e.g. apparentMagnitude <= 3) constrains
        // the owning class's instances to those that satisfy it.
        if (field.hasValueFilter()) {
            parent.valueFilters().add(new wikidata.explore.filter.WikidataValueFilter(
                    field.name(),
                    m.propertyPid(),
                    m.propertyLabel(),
                    field.filterOperator(),
                    field.filterValue(),
                    true));
        }

        if (kind == FieldProductionKind.CHILD_OBJECTS) {
            RuleNode child = new RuleNode(
                    field.entityClassName().isBlank()
                            ? cap(field.name())
                            : field.entityClassName(),
                    decap(field.name()));

            child.sourceQid(parent.sourceQid());
            child.sourceLabel(parent.sourceLabel());
            child.propertyPid(m.propertyPid());
            child.propertyLabel(m.propertyLabel());
            child.direction(m.direction());
            child.limit(m.limit());
            child.labelConfig(parent.labelConfig());
            // Sort the child entities (e.g. by apparentMagnitude ascending =
            // brightest first) so the per-parent limit keeps the brightest.
            child.sortFieldName(field.sortFieldName());
            child.sortDescending(field.sortDescending());

            // Pull in the referenced class's own fields so the child objects
            // carry them (e.g. a Star's magnitude), not just a name. The
            // visited guard stops infinite recursion on cyclic class graphs.
            GeneratedClassModel childClass = project == null
                    ? null
                    : project.findClass(field.entityClassName());
            if (childClass != null && !visited.contains(childClass.className())) {
                // Constrain the edge's values to the referenced class — e.g.
                // a constellation's stars are reached via P59, but P59 points at
                // every object in the constellation; the class membership
                // (P31 = Q523 "star") keeps only the stars. A per-edge override
                // (edgeMembership = NONE) drops this for the relationship while
                // the root class keeps it — so a constellation's "stars" can
                // include bright NAMED variable/double stars (typed as a
                // subclass, not Q523) reached via P59 + magnitude + label.
                FieldSourceMapping cm = childClass.instanceMapping();
                if (field.edgeMembership() == EdgeMembershipMode.INHERIT
                        && cm != null && !cm.sourceQid().isBlank()) {
                    child.membershipPid(cm.propertyPid().isBlank()
                            ? "P31" : cm.propertyPid());
                    child.membershipQid(cm.sourceQid());
                }
                // "Notable only" is a notability axis (independent of membership):
                // carry it onto the edge so children are restricted to notable
                // entities too.
                if (cm != null) {
                    child.requireSitelink(cm.requireSitelink());
                }

                visited.add(childClass.className());
                for (GeneratedFieldModel cf : childClass.fields()) {
                    if (cf.isNameField()) {
                        continue;
                    }
                    compileFieldIntoNode(child, cf, project, visited);
                }
            }

            parent.edges().add(new RuleEdge(
                    field.name(),
                    child,
                    field.collection()));
            return;
        }

        RuleIncludedField included = compileField(field);

        if (included != null) {
            parent.includedFields().add(included);
        }
    }

    private static RuleIncludedField.FieldKind fieldKindFor(
            GeneratedFieldModel field) {

        if (field.type() == FieldType.IMAGE) {
            return RuleIncludedField.FieldKind.MEDIA;
        }

        if (field.type() == FieldType.ENTITY || field.collection()) {
            return RuleIncludedField.FieldKind.ENTITY;
        }

        return RuleIncludedField.FieldKind.AUTO;
    }

    private static FieldProductionKind resolvedProductionKind(
            GeneratedFieldModel field) {

        FieldSourceMapping m = field.mapping();

        if (m.productionKind() != FieldProductionKind.AUTO) {
            return m.productionKind();
        }

        if (!field.fields().isEmpty()) {
            return FieldProductionKind.CHILD_OBJECTS;
        }

        if (field.collection()) {
            return FieldProductionKind.DELAYED_ENTITY_FIELD;
        }

        if (field.type() == FieldType.ENTITY) {
            return FieldProductionKind.DELAYED_ENTITY_FIELD;
        }

        return FieldProductionKind.INLINE_VALUE;
    }

    private static String decap(String s) {
        if (s == null || s.isBlank()) {
            return "item";
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String cap(String s) {
        if (s == null || s.isBlank()) {
            return "Item";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
