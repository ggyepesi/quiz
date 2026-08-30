package wikidata.explore.rule;

import datasource.schema.FieldType;

import wikidata.WikidataIds;

import wikidata.explore.compiled.CompiledClass;
import wikidata.explore.compiled.CompiledField;
import wikidata.explore.compiled.CompiledFieldSource;
import wikidata.explore.compiled.CompiledProjectModel;
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

        // Inherit the base class's membership when this class defines none.
        FieldSourceMapping m = clazz.effectiveInstanceMapping(project);

        RuleNode node = new RuleNode(clazz.className(), decap(clazz.className()));

        node.sourceQid(m.sourceQid());
        node.sourceLabel(m.sourceLabel());
        // Multi-QID membership: instance-of sourceQid OR any additional type
        // (e.g. + zodiacal constellation to admit Aries/Cancer).
        for (String extra : m.additionalTypeQids()) {
            node.addAdditionalSourceQid(extra);
        }
        // Excluded types: drop entities that are instance-of (P31) one of these
        // (e.g. exclude Roman deities from a Greek-character class).
        for (String exq : m.excludedTypeQids()) {
            String qid = RuleNode.cleanQid(exq);
            if (WikidataIds.isQid(qid)) {
                node.excludedPredicateObjects().add(
                        new RuleNode.PredicateObjectExclusion("P31", qid));
            }
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

        // Subclass discriminator: NARROW the inherited membership to instances
        // that also have ?value wdt:<pid> wd:<qid> (e.g. Person = the nominee
        // membership AND P31=human), via the node's secondary membership filter —
        // the intersection plain `extends` (inherit OR replace) can't express. The
        // property defaults to P31 but can be any relation (a non-type-like axis).
        if (clazz.hasDiscriminator()) {
            node.membershipPid(clazz.effectiveDiscriminatorPid());
            node.membershipQid(RuleNode.cleanQid(clazz.discriminatorQid()));
        }

        // Explicit instance QIDs: when membership is blank these ARE the
        // instances (VALUES ?value {…}); with a type they restrict it.
        for (String qid : clazz.seedQids()) {
            node.addIncludedQid(qid);
        }

        // Class-level ranking: keep the top `limit` by notability (sitelinks)
        // or a sortable field's property.
        node.rankDescending(m.rankDescending());
        String rankBy = m.rankBy();
        if (FieldSourceMapping.RANK_BY_SITELINKS.equals(rankBy)) {
            node.rankBySitelinks(true);
        } else if (rankBy != null && !rankBy.isBlank()) {
            for (GeneratedFieldModel rf : clazz.effectiveFields(project)) {
                if (rf != null && rankBy.equals(rf.name())
                        && !rf.mapping().propertyPid().isBlank()) {
                    node.rankPropertyPid(rf.mapping().propertyPid());
                    break;
                }
            }
        }

        visited.add(clazz.className());

        // Effective fields = inherited (from the extends chain) + own.
        for (GeneratedFieldModel f : clazz.effectiveFields(project)) {
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
        // A literal property (string/number/date) is ALWAYS outgoing — the value is
        // the object of the triple (?entity wdt:P ?literal). An "incoming" literal
        // is nonsensical and silently matches nothing (a date field left at the
        // ITEM_TO_ROOT default returns zero rows, no warning), so force outgoing
        // regardless of the configured direction.
        included.direction(FieldSemantics.effectiveDirection(field));

        return included;
    }

    private static void compileFieldIntoNode(
            RuleNode parent,
            GeneratedFieldModel field,
            GeneratedProjectModel project,
            Set<String> visited) {

        if (field == null) {
            return;
        }

        // A post-extraction source isn't a Wikidata property; it is filled by its own
        // enrichment or acquisition afterwards, so it must not be compiled into the
        // Wikidata rule tree.
        if (field.mapping().sourceType().filledAfterExtraction()) {
            return;
        }

        FieldProductionKind kind = resolvedProductionKind(field);
        FieldSourceMapping m = field.mapping();

        // INVERT and COMPANION_MATCH fields are DERIVED post-extraction (ModelInverts,
        // CompanionMatcher) — not fetched — so they don't belong in the query plan.
        // (COMPANION_MATCH's propertyPid is the companion property, not a value to load.)
        if (kind == FieldProductionKind.INVERT
                || kind == FieldProductionKind.COMPANION_MATCH
                || kind == FieldProductionKind.OWNED_COMPONENT) {
            return;
        }
        if (field.mapping().propertyPid().isBlank()) return;

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

            // The child's TYPE comes from the referenced class (applied below as
            // a membership filter), NOT from the parent — inheriting the parent's
            // sourceQid wrongly stamps e.g. a category's Q19020 onto its nominees.
            // For a relational referenced class (blank sourceQid) the edge alone
            // defines the child (?value <prop> ?parent), so leave sourceQid blank.
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
                FieldSourceMapping cm = childClass.effectiveInstanceMapping(project);
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
                for (GeneratedFieldModel cf : childClass.effectiveFields(project)) {
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
            // Constrain the OTHER end to the referenced class's type, when the
            // field refers to a class and "only related of that class" is on
            // (edgeMembership INHERIT) — e.g. episodes (P674) restricted to
            // Episode (P31=Q63143903), dropping videogames/novels that also list
            // the character via P674.
            if (!field.entityClassName().isBlank()
                    && field.edgeMembership() == EdgeMembershipMode.INHERIT
                    && project != null) {
                GeneratedClassModel refClass = project.findClass(field.entityClassName());
                if (refClass != null) {
                    FieldSourceMapping cm = refClass.effectiveInstanceMapping(project);
                    if (cm != null && !cm.sourceQid().isBlank()) {
                        included.membershipPid(cm.propertyPid().isBlank()
                                                       ? "P31" : cm.propertyPid());
                        included.membershipQid(cm.sourceQid());
                    }
                }
            }
            parent.includedFields().add(included);
        }
    }

    private static RuleIncludedField.FieldKind fieldKindFor(
            GeneratedFieldModel field) {

        if (field.type() == FieldType.IMAGE) {
            return RuleIncludedField.FieldKind.MEDIA;
        }

        // Before the collection test: a date is a date whether one or many, and the
        // value node is what carries its calendar either way.
        if (field.type() == FieldType.DATE) {
            return RuleIncludedField.FieldKind.DATE;
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

    // ---------------- compiled-model overloads ----------------
    // A structural duplicate of the editable-model compile above, reading the
    // resolved CompiledClass/CompiledField. It uses the CONFIGURED entity-class
    // name (not the resolved one) so the RuleNode class names — and therefore the
    // generated SPARQL — are byte-identical to the editable-model path. The
    // RuleTreeCompilerParityTest asserts exactly that on the live model.

    public static RuleNode compileProject(CompiledProjectModel project) {
        return compileClass(project.rootClass(), project, new HashSet<>());
    }

    private static RuleNode compileClass(
            CompiledClass clazz,
            CompiledProjectModel project,
            Set<String> visited) {

        CompiledFieldSource m = clazz.sourceMapping();

        RuleNode node = new RuleNode(clazz.className(), decap(clazz.className()));

        node.sourceQid(m.sourceQid());
        node.sourceLabel(m.sourceLabel());
        for (String extra : m.additionalTypeQids()) {
            node.addAdditionalSourceQid(extra);
        }
        for (String exq : m.excludedTypeQids()) {
            String qid = RuleNode.cleanQid(exq);
            if (WikidataIds.isQid(qid)) {
                node.excludedPredicateObjects().add(
                        new RuleNode.PredicateObjectExclusion("P31", qid));
            }
        }
        node.propertyPid(m.propertyPid().isBlank() ? "P31" : m.propertyPid());
        node.propertyLabel(m.propertyLabel().isBlank()
                                   ? "instance of"
                                   : m.propertyLabel());
        node.direction(RuleDirection.ITEM_TO_ROOT);
        node.limit(m.limit());
        node.requireSitelink(m.requireSitelink());
        node.labelConfig(new RuleLabelConfig(m.requireLabel(), m.labelLanguage()));

        if (clazz.hasDiscriminator()) {
            node.membershipPid(clazz.discriminatorPid());   // already effective
            node.membershipQid(RuleNode.cleanQid(clazz.discriminatorQid()));
        }

        for (String qid : clazz.seedQids()) {
            node.addIncludedQid(qid);
        }

        node.rankDescending(m.rankDescending());
        String rankBy = m.rankBy();
        if (FieldSourceMapping.RANK_BY_SITELINKS.equals(rankBy)) {
            node.rankBySitelinks(true);
        } else if (rankBy != null && !rankBy.isBlank()) {
            for (CompiledField rf : clazz.effectiveFields()) {
                if (rf != null && rankBy.equals(rf.name())
                        && !rf.source().propertyPid().isBlank()) {
                    node.rankPropertyPid(rf.source().propertyPid());
                    break;
                }
            }
        }

        visited.add(clazz.className());

        // Compiled effectiveFields already exclude name fields.
        for (CompiledField f : clazz.effectiveFields()) {
            compileFieldIntoNode(node, f, project, visited);
        }

        return node;
    }

    private static RuleIncludedField compileField(CompiledField field) {
        if (field == null || field.source().propertyPid().isBlank()) {
            return null;
        }
        CompiledFieldSource m = field.source();

        RuleIncludedField included = new RuleIncludedField(
                field.name(),
                m.propertyPid(),
                m.propertyLabel(),
                fieldKindFor(field),
                !field.required());

        included.collection(field.collection());
        included.direction(FieldSemantics.effectiveDirection(
                field.type(), field.cardinality(), m.direction()));

        return included;
    }

    private static void compileFieldIntoNode(
            RuleNode parent,
            CompiledField field,
            CompiledProjectModel project,
            Set<String> visited) {

        if (field == null) {
            return;
        }
        if (field.source().sourceType().filledAfterExtraction()) {
            return;
        }

        FieldProductionKind kind = resolvedProductionKind(field);
        CompiledFieldSource m = field.source();

        if (kind == FieldProductionKind.INVERT
                || kind == FieldProductionKind.COMPANION_MATCH
                || kind == FieldProductionKind.OWNED_COMPONENT) {
            return;
        }
        if (field.source().propertyPid().isBlank()) return;

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
                    field.configuredEntityClassName().isBlank()
                            ? cap(field.name())
                            : field.configuredEntityClassName(),
                    decap(field.name()));

            child.propertyPid(m.propertyPid());
            child.propertyLabel(m.propertyLabel());
            child.direction(m.direction());
            child.limit(m.limit());
            child.labelConfig(parent.labelConfig());
            child.sortFieldName(field.sortFieldName());
            child.sortDescending(field.sortDescending());

            CompiledClass childClass = project == null
                    ? null
                    : project.findClass(field.configuredEntityClassName()).orElse(null);
            if (childClass != null && !visited.contains(childClass.className())) {
                CompiledFieldSource cm = childClass.sourceMapping();
                if (field.edgeMembership() == EdgeMembershipMode.INHERIT
                        && !cm.sourceQid().isBlank()) {
                    child.membershipPid(cm.propertyPid().isBlank()
                                                ? "P31" : cm.propertyPid());
                    child.membershipQid(cm.sourceQid());
                }
                child.requireSitelink(cm.requireSitelink());

                visited.add(childClass.className());
                for (CompiledField cf : childClass.effectiveFields()) {
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
            if (!field.configuredEntityClassName().isBlank()
                    && field.edgeMembership() == EdgeMembershipMode.INHERIT
                    && project != null) {
                CompiledClass refClass = project.findClass(
                        field.configuredEntityClassName()).orElse(null);
                if (refClass != null) {
                    CompiledFieldSource cm = refClass.sourceMapping();
                    if (!cm.sourceQid().isBlank()) {
                        included.membershipPid(cm.propertyPid().isBlank()
                                                       ? "P31" : cm.propertyPid());
                        included.membershipQid(cm.sourceQid());
                    }
                }
            }
            parent.includedFields().add(included);
        }
    }

    private static RuleIncludedField.FieldKind fieldKindFor(CompiledField field) {
        if (field.type() == FieldType.IMAGE) {
            return RuleIncludedField.FieldKind.MEDIA;
        }
        if (field.type() == FieldType.DATE) {
            return RuleIncludedField.FieldKind.DATE;
        }
        if (field.type() == FieldType.ENTITY || field.collection()) {
            return RuleIncludedField.FieldKind.ENTITY;
        }
        return RuleIncludedField.FieldKind.AUTO;
    }

    private static FieldProductionKind resolvedProductionKind(CompiledField field) {
        CompiledFieldSource m = field.source();

        if (m.productionKind() != FieldProductionKind.AUTO) {
            return m.productionKind();
        }
        if (!field.nestedFields().isEmpty()) {
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
