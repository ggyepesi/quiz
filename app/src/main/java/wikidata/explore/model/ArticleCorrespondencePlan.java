package wikidata.explore.model;

import datasource.EntityRef;
import datasource.api.BindingScope;
import datasource.api.SourceExecutionPlan;
import datasource.api.SourceInputRequirement;

import java.util.LinkedHashSet;
import java.util.Set;

/** Classes whose configured sources require their Wikipedia article correspondence. */
public final class ArticleCorrespondencePlan {
    private ArticleCorrespondencePlan() { }

    public static Set<String> classes(
            GeneratedProjectModel model, SourceExecutionPlan plan) {
        if (plan == null) return Set.of();
        LinkedHashSet<String> direct = new LinkedHashSet<>();
        plan.steps(BindingScope.SOURCE_CORRESPONDENCE).stream()
                .filter(step -> datasource.wikidata.WikidataDatasourceProvider.ID
                        .equals(step.recipe().providerId())
                        && datasource.wikidata.WikidataDatasourceProvider.SITELINK
                        .equals(step.recipe().operationId()))
                .map(step -> step.target().className()).forEach(direct::add);
        plan.steps(BindingScope.FIELD_VALUE).stream()
                .filter(step -> step.prepared().inputRequirements().stream()
                        .anyMatch(requirement -> EntityRef.WIKIDATA.equals(
                                        requirement.sourceId())
                                && requirement.kind() == SourceInputRequirement.Kind
                                        .ARTICLE_CORRESPONDENCE))
                .forEach(step -> articleCarriers(model, step.target()).forEach(direct::add));
        if (model == null || direct.isEmpty()) return Set.copyOf(direct);
        LinkedHashSet<String> effective = new LinkedHashSet<>();
        for (GeneratedClassModel clazz : model.classes()) {
            if (clazz == null) continue;
            for (String owner : direct) {
                if (model.isSameOrSubclass(clazz.className(), owner)) {
                    effective.add(clazz.className());
                    break;
                }
            }
        }
        return Set.copyOf(effective);
    }

    private static Set<String> articleCarriers(
            GeneratedProjectModel model, datasource.api.SourceBindingTarget target) {
        if (target.contextual()) return Set.of(target.contextClassName());
        GeneratedClassModel clazz = model == null ? null : model.findClass(target.className());
        if (clazz == null || !clazz.ownedClass()) return Set.of(target.className());
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        for (GeneratedClassModel owner : MembershipPattern.owningEntityClasses(clazz, model)) {
            owners.add(owner.className());
        }
        return Set.copyOf(owners);
    }
}
