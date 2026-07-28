package quiz.transform.app;

import quiz.Quizable;
import quiz.curation.Curatable;
import quiz.curation.ManualCuration;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import quiz.transform.ui.SchemaView;
import objectview.viewconfig.FieldTypeSource;

import javax.swing.JComponent;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * A {@link DomainModel} that also carries its {@link ManualCuration} store, so the
 * workbench can offer curation. Delegates every schema/instance query to the compiled
 * domain — it only adds the {@link Curatable} capability (and forwards {@link
 * SchemaView} when the base has one).
 */
final class CuratableDomain implements DomainModel, SchemaView, Curatable {

    private final DomainModel base;
    private final ManualCuration curation;

    CuratableDomain(DomainModel base, ManualCuration curation) {
        this.base = base;
        this.curation = curation;
    }

    @Override public ManualCuration curation() { return curation; }

    @Override public JComponent schemaView() {
        return base instanceof SchemaView sv ? sv.schemaView() : null;
    }

    @Override public List<String> types() { return base.types(); }
    @Override public List<DomainField> fields(String type) { return base.fields(type); }
    @Override public Set<String> structuralFields(String type) { return base.structuralFields(type); }
    @Override public FieldTypeSource fieldTypes(String type) { return base.fieldTypes(type); }
    @Override public Quizable representativeSample(String type) { return base.representativeSample(type); }
    @Override public Collection<? extends Quizable> instances() { return base.instances(); }
    @Override public Class<? extends Quizable> universe() { return base.universe(); }
}
