package wikidata.explore.model;

import datasource.api.SourceRecipe;
import datasource.wikidata.WikidataDatasourceProvider;
import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The crossing between a class's authored population and the provider/operation binding
 * the datasource catalogue exposes.
 *
 * <p>The authored form is one value — {@link GeneratedClassModel#membership()} — so this
 * projects rather than reconciles. It used to translate three fields, and translating
 * back tore a list apart as first-and-rest: {@code assign} put the first QID in {@code
 * sourceQid} and the others in {@code additionalTypeQids}, a shape the value it came
 * from did not have. It also had to REFUSE a binding asking for subclass closure,
 * because the three fields could not persist one. A bound carries it.
 */
final class PopulationSourceBindings {

    private PopulationSourceBindings() { }

    static SourceRecipe effective(GeneratedClassModel clazz) {
        SourceRecipe legacy = fromLegacy(clazz);
        return legacy == null ? clazz.declaredPopulationSource() : legacy;
    }

    static void assign(GeneratedClassModel clazz, SourceRecipe binding) {
        if (clazz == null) return;
        if (binding == null) {
            clearMembership(clazz);
            clazz.seedQids().clear();
            clazz.declaredPopulationSource(null);
            return;
        }
        if (!WikidataDatasourceProvider.ID.equals(binding.providerId())) {
            throw new IllegalArgumentException(
                    "Class population generation does not yet execute provider '"
                            + binding.providerId() + "'");
        }
        if (clazz.ownedClass() || clazz.reifiesStatements()) {
            throw new IllegalStateException(
                    "Owned and statement classes cannot declare an independent population");
        }
        if (WikidataDatasourceProvider.SEED_LIST.equals(binding.operationId())) {
            clearMembership(clazz);
            clazz.seedQids().clear();
            for (String qid : binding.parameter("ids").split("[,\\s|]+")) {
                addQid(clazz.seedQids(), qid);
            }
            if (clazz.seedQids().isEmpty()) {
                throw new IllegalArgumentException("A seed-list binding requires QIDs");
            }
        } else if (WikidataDatasourceProvider.STATEMENT_MEMBERSHIP.equals(
                binding.operationId())) {
            String pid = clean(binding.parameter("property")).toUpperCase();
            List<String> values = new ArrayList<>();
            for (String qid : binding.parameter("values").split("[,\\s|]+")) {
                addQid(values, qid);
            }
            if (!WikidataIds.isPid(pid) || values.isEmpty()) {
                throw new IllegalArgumentException(
                        "A statement-membership binding requires a PID and value QIDs");
            }
            clazz.seedQids().clear();
            clazz.instanceMapping().sourceLabel("");
            clazz.instanceMapping().propertyLabel("");
            clazz.membership(EntityBound.relation(pid, values,
                    Boolean.parseBoolean(binding.parameter("includeSubclasses"))));
        } else {
            throw new IllegalArgumentException(
                    "Unsupported Wikidata class-population operation: "
                            + binding.operationId());
        }
        clazz.declaredPopulationSource(fromLegacy(clazz));
    }

    static SourceRecipe fromLegacy(GeneratedClassModel clazz) {
        return fromLegacy(clazz, null);
    }

    /** Includes inherited membership; subclass discriminators remain separate rules. */
    static SourceRecipe fromLegacy(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (clazz == null || clazz.ownedClass() || clazz.reifiesStatements()) return null;
        EntityBound membership = project == null
                ? clazz.membership() : clazz.effectiveMembership(project);
        String pid = clean(membership.relationPid()).toUpperCase();
        List<String> targets = new ArrayList<>();
        membership.qids().forEach(value -> addQid(targets, value));
        List<String> seeds = clazz.seedQids().stream().map(PopulationSourceBindings::clean)
                .map(String::toUpperCase).filter(WikidataIds::isQid).distinct().toList();

        // A bound AND seeds is an INTERSECTION — the seeds restrict the membership
        // ("the twelve Olympians, and only those that are gods"), which the rule tree
        // emits as the membership triple plus VALUES ?value. No catalogue operation can
        // say that: each maps to ONE PopulationRequest, RELATION or EXPLICIT. This used
        // to answer with the membership alone, silently dropping the restriction, and
        // assigning that answer back deleted the seeds. Naming a parameter for it would
        // only move the drop into the provider, which ignores what it does not read, so
        // this class has no catalogue binding until one can express the intersection.
        if (!targets.isEmpty() && !seeds.isEmpty()) {
            return null;
        }

        if (WikidataIds.isPid(pid) && !targets.isEmpty()) {
            LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
            parameters.put("property", pid);
            parameters.put("values", String.join(",", targets));
            parameters.put("includeSubclasses",
                    String.valueOf(membership.includeDescendants()));
            return new SourceRecipe(WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.STATEMENT_MEMBERSHIP, parameters);
        }
        if (!seeds.isEmpty()) {
            return new SourceRecipe(WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.SEED_LIST,
                    java.util.Map.of("ids", String.join(",", seeds)));
        }
        return null;
    }

    static void synchronize(GeneratedProjectModel model) {
        if (model == null) return;
        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz == null) continue;
            SourceRecipe derived = fromLegacy(clazz);
            SourceRecipe declared = clazz.declaredPopulationSource();
            if (derived != null) {
                clazz.declaredPopulationSource(derived);
            } else if (declared != null
                    && WikidataDatasourceProvider.ID.equals(declared.providerId())) {
                // A removed legacy Wikidata population must also retract its old
                // persisted projection; bindings owned by another provider survive.
                clazz.declaredPopulationSource(null);
            }
        }
    }

    private static void addQid(List<String> result, String value) {
        String qid = clean(value).toUpperCase();
        if (WikidataIds.isQid(qid) && !result.contains(qid)) result.add(qid);
    }

    private static void clearMembership(GeneratedClassModel clazz) {
        clazz.membership(EntityBound.unbounded());
        FieldSourceMapping mapping = clazz.instanceMapping();
        mapping.sourceLabel("");
        mapping.propertyLabel("");
        mapping.excludedTypeQids().clear();
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.trim();
        int slash = clean.lastIndexOf('/');
        return slash < 0 ? clean : clean.substring(slash + 1);
    }
}
