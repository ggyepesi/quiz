package wikidata.explore.model;

import datasource.api.SourceRecipe;
import datasource.wikidata.WikidataDatasourceProvider;
import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Migration boundary between the established class-population fields and the
 * provider/operation binding exposed by the datasource catalogue.
 *
 * <p>For Wikidata classes the old fields remain the single editable truth for now:
 * every editor and the rule compiler already use them. This projection makes that
 * choice explicit and persistable without introducing a second configuration which
 * could drift. Once editors bind offerings directly, this is the one adapter that can
 * be removed.
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
            clearMembership(clazz.instanceMapping());
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
            clearMembership(clazz.instanceMapping());
            clazz.seedQids().clear();
            for (String qid : binding.parameter("ids").split("[,\\s|]+")) {
                addQid(clazz.seedQids(), qid);
            }
            if (clazz.seedQids().isEmpty()) {
                throw new IllegalArgumentException("A seed-list binding requires QIDs");
            }
        } else if (WikidataDatasourceProvider.STATEMENT_MEMBERSHIP.equals(
                binding.operationId())) {
            if (Boolean.parseBoolean(binding.parameter("includeSubclasses"))) {
                throw new IllegalArgumentException(
                        "The current class model cannot persist subclass-closure membership yet");
            }
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
            FieldSourceMapping mapping = clazz.instanceMapping();
            mapping.sourceLabel("");
            mapping.propertyLabel("");
            mapping.propertyPid(pid);
            mapping.sourceQid(values.getFirst());
            mapping.additionalTypeQids().clear();
            values.stream().skip(1).forEach(mapping.additionalTypeQids()::add);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported Wikidata class-population operation: "
                            + binding.operationId());
        }
        clazz.declaredPopulationSource(fromLegacy(clazz));
    }

    static SourceRecipe fromLegacy(GeneratedClassModel clazz) {
        if (clazz == null || clazz.ownedClass() || clazz.reifiesStatements()) return null;
        FieldSourceMapping mapping = clazz.instanceMapping();
        String pid = clean(mapping.propertyPid()).toUpperCase();
        List<String> targets = targets(mapping);
        if (WikidataIds.isPid(pid) && !targets.isEmpty()) {
            LinkedHashMap<String, String> parameters = new LinkedHashMap<>();
            parameters.put("property", pid);
            parameters.put("values", String.join(",", targets));
            parameters.put("includeSubclasses", "false");
            return new SourceRecipe(WikidataDatasourceProvider.ID,
                    WikidataDatasourceProvider.STATEMENT_MEMBERSHIP, parameters);
        }
        List<String> seeds = clazz.seedQids().stream().map(PopulationSourceBindings::clean)
                .map(String::toUpperCase).filter(WikidataIds::isQid).distinct().toList();
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

    private static List<String> targets(FieldSourceMapping mapping) {
        ArrayList<String> result = new ArrayList<>();
        addQid(result, mapping.sourceQid());
        mapping.additionalTypeQids().forEach(value -> addQid(result, value));
        return List.copyOf(result);
    }

    private static void addQid(List<String> result, String value) {
        String qid = clean(value).toUpperCase();
        if (WikidataIds.isQid(qid) && !result.contains(qid)) result.add(qid);
    }

    private static void clearMembership(FieldSourceMapping mapping) {
        mapping.sourceQid("");
        mapping.sourceLabel("");
        mapping.propertyPid("");
        mapping.propertyLabel("");
        mapping.additionalTypeQids().clear();
        mapping.excludedTypeQids().clear();
    }

    private static String clean(String value) {
        if (value == null) return "";
        String clean = value.trim();
        int slash = clean.lastIndexOf('/');
        return slash < 0 ? clean : clean.substring(slash + 1);
    }
}
