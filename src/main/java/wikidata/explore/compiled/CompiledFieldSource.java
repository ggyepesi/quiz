package wikidata.explore.compiled;

import wikidata.explore.model.*;
import java.util.Set;

/** Immutable runtime snapshot of FieldSourceMapping. */
public record CompiledFieldSource(
        String sourceQid,
        String sourceLabel,
        String propertyPid,
        String propertyLabel,
        String qualifierPid,
        String subjectField,
        String matchValueField,
        String matchRoleField,
        MissingQualifierPolicy missingQualifierPolicy,
        RuleDirection direction,
        boolean requireLabel,
        String labelLanguage,
        boolean requireSitelink,
        int limit,
        String rankBy,
        boolean rankDescending,
        FieldProductionKind productionKind,
        Set<String> allowedQids,
        Set<String> excludedQids,
        Set<String> additionalTypeQids,
        Set<String> excludedTypeQids,
        FieldSourceType sourceType) {

    public CompiledFieldSource {
        sourceQid = clean(sourceQid);
        sourceLabel = clean(sourceLabel);
        propertyPid = clean(propertyPid);
        propertyLabel = clean(propertyLabel);
        qualifierPid = clean(qualifierPid);
        subjectField = clean(subjectField);
        matchValueField = clean(matchValueField);
        matchRoleField = clean(matchRoleField);
        direction = direction == null ? RuleDirection.ITEM_TO_ROOT : direction;
        labelLanguage = clean(labelLanguage);
        if (labelLanguage.isBlank()) labelLanguage = "en";
        limit = Math.max(1, limit);
        rankBy = clean(rankBy);
        productionKind = productionKind == null
                ? FieldProductionKind.AUTO : productionKind;
        allowedQids = immutable(allowedQids);
        excludedQids = immutable(excludedQids);
        additionalTypeQids = immutable(additionalTypeQids);
        excludedTypeQids = immutable(excludedTypeQids);
        sourceType = sourceType == null ? FieldSourceType.SPARQL : sourceType;
    }

    public boolean qualifier() {
        return qualifierPid.matches("(?i)P\\d+");
    }

    public static CompiledFieldSource from(FieldSourceMapping m) {
        FieldSourceMapping s = m == null ? new FieldSourceMapping() : m;
        return new CompiledFieldSource(
                s.sourceQid(), s.sourceLabel(), s.propertyPid(), s.propertyLabel(),
                s.qualifierPid(), s.subjectField(), s.matchValueField(),
                s.matchRoleField(), s.missingQualifierPolicy(), s.direction(),
                s.requireLabel(), s.labelLanguage(), s.requireSitelink(), s.limit(),
                s.rankBy(), s.rankDescending(), s.productionKind(), s.allowedQids(),
                s.excludedQids(), s.additionalTypeQids(), s.excludedTypeQids(),
                s.sourceType());
    }

    private static Set<String> immutable(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
