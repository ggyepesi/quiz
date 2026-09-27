package wikidata.explore.model;

import datasource.schema.FieldType;
import quiz.transform.StructureDiscovery;

import java.util.ArrayList;
import java.util.List;

/** Model-declared two-field bridges available to shared-neighbour discovery. */
public final class StructureDiscoveryBridges {
    private StructureDiscoveryBridges() { }

    public static List<StructureDiscovery.Bridge> of(
            GeneratedProjectModel model, String memberClass) {
        if (model == null || memberClass == null || memberClass.isBlank()) return List.of();
        List<StructureDiscovery.Bridge> result = new ArrayList<>();
        for (GeneratedClassModel rowClass : model.classes()) {
            if (rowClass == null) continue;
            List<GeneratedFieldModel> fields = rowClass.effectiveFields(model).stream()
                    .filter(field -> field != null && field.type() == FieldType.ENTITY)
                    .toList();
            for (GeneratedFieldModel member : fields) {
                if (!accepts(model, member.entityClassName(), memberClass)) continue;
                for (GeneratedFieldModel shared : fields) {
                    if (shared == member || shared.entityClassName() == null
                            || shared.entityClassName().isBlank()) continue;
                    result.add(new StructureDiscovery.Bridge(rowClass.className(),
                            member.name(), shared.name()));
                }
            }
        }
        return List.copyOf(result);
    }

    private static boolean accepts(
            GeneratedProjectModel model, String fieldClass, String memberClass) {
        return fieldClass != null && !fieldClass.isBlank()
                && (model.isSameOrSubclass(memberClass, fieldClass)
                || model.isSameOrSubclass(fieldClass, memberClass));
    }
}
