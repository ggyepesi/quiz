package wikidata.explore.compiled;

import wikidata.explore.model.*;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Immutable runtime snapshot of {@link FieldSourceMapping}.
 */
public record CompiledFieldSource(
        String sourceQid,
        String sourceLabel,
        String propertyPid,
        String propertyLabel,
        String qualifierPid,
        String qualifierLabel,
        QualifierDateMode qualifierDateMode,
        String subjectField,
        String matchValueField,
        String matchRoleField,
        String inverseField,
        MissingQualifierPolicy missingQualifierPolicy,
        wikidata.explore.model.RoleKind roleKind,
        RuleDirection direction,
        boolean requireLabel,
        String labelLanguage,
        String valueLanguage,
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
        qualifierLabel = clean(qualifierLabel);
        qualifierDateMode = qualifierDateMode == null
                ? QualifierDateMode.YEAR : qualifierDateMode;
        subjectField = clean(subjectField);
        matchValueField = clean(matchValueField);
        matchRoleField = clean(matchRoleField);
        inverseField = clean(inverseField);
        roleKind = roleKind == null
                ? wikidata.explore.model.RoleKind.REFERENCE
                : roleKind;
        direction = direction == null ? RuleDirection.ITEM_TO_ROOT : direction;
        labelLanguage = clean(labelLanguage);
        if (labelLanguage.isBlank()) {
            labelLanguage = wikidata.WikidataLanguageDefaults.CODE;
        }
        valueLanguage = clean(valueLanguage);
        limit = Math.max(1, limit);
        rankBy = clean(rankBy);
        productionKind = productionKind == null
                ? FieldProductionKind.AUTO
                : productionKind;
        allowedQids = immutable(allowedQids);
        excludedQids = immutable(excludedQids);
        additionalTypeQids = immutable(additionalTypeQids);
        excludedTypeQids = immutable(excludedTypeQids);
        sourceType = sourceType == null ? FieldSourceType.SPARQL : sourceType;
    }

    public boolean qualifier() {
        return qualifierPid.matches("(?i)P\\d+");
    }

    public String displayProperty() {
        return wikidata.LabelledId.display(propertyLabel, propertyPid);
    }

    public String displayQualifier() {
        return wikidata.LabelledId.display(qualifierLabel, qualifierPid);
    }

    public static CompiledFieldSource from(FieldSourceMapping mapping) {
        FieldSourceMapping source =
                mapping == null ? new FieldSourceMapping() : mapping;
        return new CompiledFieldSource(
                source.sourceQid(),
                source.sourceLabel(),
                source.propertyPid(),
                source.propertyLabel(),
                source.qualifierPid(),
                source.qualifierLabel(),
                source.qualifierDateMode(),
                source.subjectField(),
                source.matchValueField(),
                source.matchRoleField(),
                source.inverseField(),
                source.missingQualifierPolicy(),
                source.roleKind(),
                source.direction(),
                source.requireLabel(),
                source.labelLanguage(),
                source.valueLanguage(),
                source.requireSitelink(),
                source.limit(),
                source.rankBy(),
                source.rankDescending(),
                source.productionKind(),
                source.allowedQids(),
                source.excludedQids(),
                source.additionalTypeQids(),
                source.excludedTypeQids(),
                source.sourceType());
    }

    private static Set<String> immutable(Set<String> values) {
        // Preserve insertion order (the editable model uses LinkedHashSet): the QID
        // sets become VALUES clauses, so their order must stay stable for
        // deterministic queries and for parity with the editable-model derivation.
        return values == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(values));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
