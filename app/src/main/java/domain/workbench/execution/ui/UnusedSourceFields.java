package domain.workbench.execution.ui;

import domain.DomainModel;
import domain.config.ConfiguredInstanceFields;
import domain.datasource.Datasources;
import domain.transform.ui.ProjectBacking;
import objectview.Viewable;
import objectview.field.FieldRef;
import objectview.field.FieldRole;
import objectview.field.FieldSchema;
import objectview.field.FieldSet;
import objectview.field.ReflectionFieldSet;
import objectview.field.ValuePresence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Class-wide field coverage for the saved instances window, never the project declaration. */
final class UnusedSourceFields {
    private UnusedSourceFields() { }

    static Map<String, Set<String>> byClass(DomainModel domain) {
        Map<String, Set<String>> unused = new LinkedHashMap<>();
        Map<String, List<Viewable>> byType = new LinkedHashMap<>();
        for (Viewable value : domain.instances()) {
            if (value != null) {
                byType.computeIfAbsent(value.typeName(), ignored -> new ArrayList<>()).add(value);
            }
        }
        ProjectBacking backing = domain.capability(ProjectBacking.class);
        Map<Class<?>, List<FieldRef>> byCarrier = new LinkedHashMap<>();
        byType.forEach((type, members) -> {
            FieldSchema declared = domain.fieldSchema(type);
            if (declared == null) return;
            Set<String> declaredNames = new LinkedHashSet<>();
            Set<String> optional = new LinkedHashSet<>();
            for (FieldRef field : declared.fields()) {
                declaredNames.add(field.name());
                if (field.role() == FieldRole.PROVENANCE) optional.add(field.name());
            }
            if (backing != null && backing.projectModel() != null) {
                var project = backing.projectModel();
                ConfiguredInstanceFields.of(project.findClass(type), project, Datasources.standard())
                        .forEach(field -> optional.add(field.name()));
            }
            // Backing adapters can expose source metadata or reference side outputs
            // not declared by the class. An empty one shouldn't advertise a feature
            // (such as relation-route evidence) the class never used.
            Set<Class<?>> carriers = new LinkedHashSet<>();
            for (Viewable value : members) {
                if (!carriers.add(value.getClass())) continue;
                for (FieldRef field : byCarrier.computeIfAbsent(value.getClass(),
                        ignored -> new ReflectionFieldSet(value).fields())) {
                    if ((field.role() == FieldRole.PROVENANCE || field.annotatedReference())
                            && !declaredNames.contains(field.name())) {
                        optional.add(field.name());
                    }
                }
            }
            for (Viewable value : members) {
                if (optional.isEmpty()) break;
                FieldSet published = FieldSet.of(value);
                optional.removeIf(name -> ValuePresence.isPresent(published.read(name)));
            }
            if (!optional.isEmpty()) unused.put(type, Set.copyOf(optional));
        });
        return Map.copyOf(unused);
    }
}
