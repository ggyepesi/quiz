package quiz.transform.ui;

import objectview.Viewable;
import objectview.field.FieldSchema;
import objectview.viewconfig.FieldTypeSource;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A domain that wraps another and changes some of it.
 *
 * <p>Every {@link Declared} fact is forwarded here, so a wrapper starts out saying exactly
 * what its base says and overrides only what it actually changes. That is the opposite of
 * how the wrappers were written: each re-implemented the forwarding methods it happened to
 * think of, and every one it did not think of silently answered the interface default — a
 * model's category recipe, a declared fallback source, whatever came next. Nothing failed;
 * the declaration simply stopped being heard.
 *
 * <p>No {@link Derived} method is overridden here, deliberately. Those are computed from the
 * declarations, so they must recompute over the SUBCLASS's declarations rather than the
 * base's: forwarding {@code isSubclassOf} would answer using the base's {@code baseType} and
 * ignore the hierarchy the wrapper exists to impose. {@code DomainContractTest} enforces
 * both halves, so the next method added to {@link DomainModel} cannot land on the wrong side
 * of this by accident.
 */
public abstract class DelegatingDomainModel implements DomainModel {

    protected final DomainModel base;

    protected DelegatingDomainModel(DomainModel base) {
        this.base = Objects.requireNonNull(base, "A wrapping domain needs something to wrap");
    }

    @Override public wikidata.explore.model.WikipediaCategoryRule wikipediaCategoryRule(
            String type, String field) {
        return base.wikipediaCategoryRule(type, field);
    }

    @Override public wikidata.explore.model.EntityKindRule entityKindRule(String className) {
        return base.entityKindRule(className);
    }

    @Override public List<String> types() { return base.types(); }

    @Override public List<String> servedTypes() { return base.servedTypes(); }

    @Override public String baseType(String type) { return base.baseType(type); }

    @Override public Set<String> directClasses(Viewable instance) {
        return base.directClasses(instance);
    }

    @Override public Collection<? extends Viewable> instances() { return base.instances(); }

    @Override public List<String> selectionNames() { return base.selectionNames(); }

    @Override public List<Viewable> selectionMembers(String selectionName) {
        return base.selectionMembers(selectionName);
    }

    @Override public boolean exposesEntityUniverse() { return base.exposesEntityUniverse(); }

    @Override public boolean entityOrigin(String type, objectview.field.FieldPath path) {
        return base.entityOrigin(type, path);
    }

    @Override public FieldSchema fieldSchema(String type) { return base.fieldSchema(type); }

    @Override public Set<String> structuralFields(String type) {
        return base.structuralFields(type);
    }

    @Override public FieldTypeSource fieldTypes(String type) { return base.fieldTypes(type); }

    @Override public Viewable representativeSample(String type) {
        return base.representativeSample(type);
    }

    @Override public Collection<? extends Viewable> memberRoots() { return base.memberRoots(); }

    @Override public List<? extends objectview.group.ViewableGroup<?>> groupRoots() {
        return base.groupRoots();
    }

    @Override public List<objectview.viewconfig.DomainGroupRoot> groupRootBindings() {
        return base.groupRootBindings();
    }

    @Override public Class<? extends Viewable> universe() { return base.universe(); }
}
