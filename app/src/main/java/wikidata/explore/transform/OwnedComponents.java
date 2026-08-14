package wikidata.explore.transform;

import wikidata.explore.extract.GenerationLog;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;
import wikidata.explore.model.OwnedClassSemantics;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Materializes field-owned component objects. An edge such as
 * {@code Person.structuredName -> Name [OWNED_COMPONENT]} is the production site:
 * one Name is made per Person, with the Person identifier, and wired into the field.
 * The site is part of the stable type key so two component fields never collapse.
 */
public final class OwnedComponents {

    private OwnedComponents() {}

    public record Result(int created, List<WikidataDynamicObject> components) {
        /** Adds each component not already present as the same object. Remap pools can
         * already contain a component reached through an owner; identity is the right
         * boundary because equality is not the persistence identity contract. */
        public void addTo(Collection<WikidataDynamicObject> pool) {
            if (pool == null || components == null || components.isEmpty()) return;
            java.util.Set<WikidataDynamicObject> present =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            present.addAll(pool);
            for (WikidataDynamicObject component : components) {
                if (component != null && present.add(component)) pool.add(component);
            }
        }
    }

    public static Result apply(
            GeneratedProjectModel project,
            Collection<WikidataDynamicObject> roots,
            Collection<WikidataDynamicObject> evidenceRoots,
            GenerationLog log) {
        if (project == null || roots == null) return new Result(0, List.of());

        Map<String, WikidataDynamicObject> evidence = new LinkedHashMap<>();
        if (evidenceRoots != null) {
            for (WikidataDynamicObject value
                    : wikidata.explore.extract.WikidataObjectGraph.reachable(evidenceRoots)) {
                if (value != null) evidence.put(key(value.typeKey(), value.getIdentifier()), value);
            }
        }

        List<WikidataDynamicObject> objects =
                wikidata.explore.extract.WikidataObjectGraph.reachable(roots);
        Map<String, WikidataDynamicObject> current = new LinkedHashMap<>();
        for (WikidataDynamicObject value : objects) {
            if (value != null) current.put(key(value.typeKey(), value.getIdentifier()), value);
        }
        List<WikidataDynamicObject> made = new ArrayList<>();
        java.util.Set<WikidataDynamicObject> reported =
                Collections.newSetFromMap(new IdentityHashMap<>());
        int created = 0;
        // A component can own a component in turn (Person.structuredName -> Name,
        // Name.pronunciation -> Pronunciation), so what one round materializes are the
        // owners of the next; rounds stop when one adds nothing. Reaching the fixed point
        // is what keeps a nested component from appearing only on the NEXT generation,
        // when the previous run's pool happens to already hold its owner.
        //
        // This terminates even for a model the validator would reject as cyclic: a
        // component takes its OWNER's identifier, so the identity is invariant along a
        // chain, and a site yields at most one component per identity — coming round
        // again, a site finds its own work already done and creates nothing.
        List<WikidataDynamicObject> owners = objects;
        while (!owners.isEmpty()) {
            List<WikidataDynamicObject> materialized = new ArrayList<>();
            for (GeneratedClassModel ownerClass : project.classes()) {
                if (ownerClass == null) continue;
                // A production site belongs to the class that DECLARES the field.
                // Subclass instances match that owner below; inherited fields must not
                // manufacture a second site under the subclass name.
                for (GeneratedFieldModel field : ownerClass.fields()) {
                    if (!isSite(field, project)) continue;
                    GeneratedClassModel target = project.findClass(field.entityClassName());
                    if (target == null) continue; // validator reports the model error
                    String typeKey = target.className() + "@"
                            + ownerClass.className() + "." + field.name();
                    for (WikidataDynamicObject owner : owners) {
                        if (owner == null
                                || !isInstanceOf(owner, ownerClass.className(), project)) {
                            continue;
                        }
                        String identity = key(typeKey, owner.getIdentifier());
                        WikidataDynamicObject component = current.get(identity);
                        if (component == null) {
                            component = new WikidataDynamicObject(
                                    owner.getIdentifier(), owner.getDisplayName());
                            component.type(target.className());
                            component.directClasses(List.of(target.className()));
                            component.typeKey(typeKey);
                            component.referenceLabel(owner.getReferenceLabel());
                            current.put(identity, component);
                            created++;
                            materialized.add(component);
                        }

                        WikidataDynamicObject prior = evidence.get(identity);
                        if (prior != null) copyTargetFields(target, prior, component, project);

                        owner.put(field.name(), component);
                        // The same site/component may be encountered from more than one
                        // reachable owner copy or fixed-point round. Result is a set-like
                        // list in deterministic discovery order; callers must not need a
                        // second defensive de-duplication just to consume it.
                        if (reported.add(component)) made.add(component);
                    }
                }
            }
            owners = materialized;
        }
        if (log != null && created > 0) {
            log.message("Owned components: materialized " + created + " object(s).\n");
        }
        return new Result(created, List.copyOf(made));
    }

    private static boolean isSite(
            GeneratedFieldModel field, GeneratedProjectModel project) {
        return OwnedClassSemantics.isOwnerQidField(field, project);
    }

    private static void copyTargetFields(
            GeneratedClassModel target, WikidataDynamicObject source,
            WikidataDynamicObject destination, GeneratedProjectModel project) {
        for (GeneratedFieldModel field : target.effectiveFields(project)) {
            if (field == null || field.isNameField()) continue;
            Object value = source.get(field.name());
            if (value != null) destination.put(field.name(), value);
        }
    }

    private static boolean isInstanceOf(
            WikidataDynamicObject value, String expected, GeneratedProjectModel project) {
        for (String direct : value.directClassNames()) {
            if (project.isSameOrSubclass(direct, expected)) return true;
        }
        return false;
    }

    private static String key(String typeKey, String id) {
        return (typeKey == null ? "" : typeKey) + "\u0000" + (id == null ? "" : id);
    }
}
