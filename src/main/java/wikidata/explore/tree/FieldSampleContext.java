package wikidata.explore.tree;

import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;

public record FieldSampleContext(
        GeneratedClassModel ownerClass,
        GeneratedFieldModel field
) {
}
