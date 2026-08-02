package quiz.transform.app;

import objectview.Viewable;
import objectview.field.FieldRef;
import objectview.field.FieldSchema;
import objectview.viewconfig.FieldTypeSource;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import quiz.transform.ui.DomainSchemas;
import quiz.transform.ui.SchemaView;
import wikidata.explore.extract.SnapshotFieldGraph;

import javax.swing.JComponent;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Keeps a compiled model authoritative while retaining fields declared by the saved
 * snapshot. Older/manual snapshots can legitimately contain data not promoted into
 * their model; loading through the richer model path must not make those values vanish
 * from cards or field configuration.
 */
final class SnapshotCompatibleDomain implements DomainModel, SchemaView {
    private final DomainModel primary;
    private final SnapshotFieldGraph snapshot;
    private final java.util.Map<String, FieldSchema> schemas =
            new java.util.concurrent.ConcurrentHashMap<>();

    SnapshotCompatibleDomain(DomainModel primary, SnapshotFieldGraph snapshot) {
        this.primary = primary;
        this.snapshot = snapshot == null ? new SnapshotFieldGraph() : snapshot;
    }

    @Override public List<String> types() {
        LinkedHashSet<String> types = new LinkedHashSet<>(primary.types());
        types.addAll(snapshot.memberTypes());
        return List.copyOf(types);
    }

    @Override public String baseType(String type) {
        String declared = primary.baseType(type);
        return declared == null ? snapshot.baseType(type) : declared;
    }

    @Override public List<DomainField> fields(String type) {
        return DomainSchemas.fields(this, type);
    }

    @Override public FieldSchema fieldSchema(String type) {
        if (type == null) return null;
        return schemas.computeIfAbsent(type, this::combineSchema);
    }

    private FieldSchema combineSchema(String type) {
        LinkedHashMap<String, FieldRef> fields = new LinkedHashMap<>();
        FieldSchema model = primary.fieldSchema(type);
        if (model != null) {
            for (FieldRef field : model.fields()) fields.put(field.name(), field);
        }
        FieldSchema saved = snapshot.fieldSchema(type, java.util.Set.of());
        if (saved != null) {
            for (FieldRef field : saved.fields()) fields.putIfAbsent(field.name(), field);
        }
        List<FieldRef> result = List.copyOf(fields.values());
        return () -> result;
    }

    @Override public java.util.Set<String> structuralFields(String type) {
        return DomainSchemas.structuralFields(fieldSchema(type));
    }

    @Override public FieldTypeSource fieldTypes(String type) {
        return DomainSchemas.fieldTypes(this, type);
    }

    @Override public Viewable representativeSample(String type) {
        Viewable sample = snapshot.shapeSample(type);
        return sample == null ? primary.representativeSample(type) : sample;
    }

    @Override public Collection<? extends Viewable> instances() {
        return primary.instances();
    }

    @Override public Class<? extends Viewable> universe() {
        return primary.universe();
    }

    @Override public JComponent schemaView() {
        return primary instanceof SchemaView view ? view.schemaView() : null;
    }
}
