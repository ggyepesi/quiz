package quiz.transform.app;

import objectview.Viewable;
import quiz.curation.Curatable;
import quiz.curation.ManualCuration;
import quiz.transform.ui.DomainField;
import quiz.transform.ui.DomainModel;
import quiz.transform.ui.SchemaView;
import objectview.field.FieldSchema;
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
    private final Collection<? extends Viewable> memberRoots;
    private final List<objectview.viewconfig.DomainGroupRoot> groupRootBindings;

    CuratableDomain(DomainModel base, ManualCuration curation) {
        this(base, curation, base.memberRoots(), base.groupRootBindings());
    }

    CuratableDomain(
            DomainModel base,
            ManualCuration curation,
            Collection<? extends Viewable> memberRoots,
            List<objectview.viewconfig.DomainGroupRoot> groupRootBindings) {
        this.base = base;
        this.curation = curation;
        this.memberRoots = memberRoots == null ? List.of() : List.copyOf(memberRoots);
        this.groupRootBindings = groupRootBindings == null
                ? List.of() : List.copyOf(groupRootBindings);
    }

    @Override public ManualCuration curation() { return curation; }

    @Override public JComponent schemaView() {
        return base instanceof SchemaView sv ? sv.schemaView() : null;
    }

    @Override public List<String> types() { return base.types(); }
    @Override public String baseType(String type) { return base.baseType(type); }
    @Override public List<DomainField> fields(String type) { return base.fields(type); }
    @Override public FieldSchema fieldSchema(String type) { return base.fieldSchema(type); }
    @Override public Set<String> structuralFields(String type) { return base.structuralFields(type); }
    @Override public FieldTypeSource fieldTypes(String type) { return base.fieldTypes(type); }
    @Override public Viewable representativeSample(String type) { return base.representativeSample(type); }
    @Override public Collection<? extends Viewable> instances() { return base.instances(); }
    @Override public Collection<? extends Viewable> memberRoots() { return memberRoots; }
    @Override public List<? extends objectview.group.ViewableGroup<?>> groupRoots() {
        return DomainModel.super.groupRoots();
    }
    @Override public List<objectview.viewconfig.DomainGroupRoot> groupRootBindings() {
        return groupRootBindings;
    }
    @Override public Class<? extends Viewable> universe() { return base.universe(); }
}
