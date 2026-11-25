package domain.build;

import domain.datasource.api.SourceBindingTarget;
import domain.runtime.WikidataDynamicObject;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.config.OwnedComponentSite;
import domain.config.OwnedFieldSources;

/** One interpretation of which pool objects a resolved field-source target applies to. */
final class FieldSourceTargeting {
    private FieldSourceTargeting() { }

    static boolean applies(GeneratedProjectModel model, WikidataDynamicObject object,
            GeneratedClassModel owner, SourceBindingTarget target) {
        if (model == null || object == null || owner == null) return false;
        OwnedComponentSite site = object.isPart() ? OwnedComponentSite.parse(object.typeKey()) : null;
        if (target != null && target.contextual()) {
            return site != null && target.className().equals(site.targetClass())
                    && target.contextClassName().equals(site.ownerClass())
                    && target.contextFieldPath().equals(site.ownerField());
        }
        if (target != null && site != null
                && OwnedFieldSources.override(OwnedFieldSources.ownershipField(model, site),
                        target.fieldPath()) != null) return false;
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
