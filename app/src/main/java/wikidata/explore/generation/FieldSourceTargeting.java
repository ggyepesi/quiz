package wikidata.explore.generation;

import datasource.api.SourceBindingTarget;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.OwnedComponentSite;

/** One interpretation of which pool objects a resolved field-source target applies to. */
final class FieldSourceTargeting {
    private FieldSourceTargeting() { }

    static boolean applies(GeneratedProjectModel model, WikidataDynamicObject object,
            GeneratedClassModel owner, SourceBindingTarget target) {
        if (model == null || object == null || owner == null) return false;
        if (target != null && target.contextual()) {
            OwnedComponentSite site = OwnedComponentSite.parse(object.typeKey());
            return site != null && target.className().equals(site.targetClass())
                    && target.contextClassName().equals(site.ownerClass())
                    && target.contextFieldPath().equals(site.ownerField());
        }
        for (String direct : object.directClassNames()) {
            for (GeneratedClassModel current = model.findClass(direct); current != null;
                    current = current.baseClassName().isBlank()
                            ? null : model.findClass(current.baseClassName())) {
                if (owner.className().equals(current.className())) return true;
            }
        }
        return false;
    }
}
