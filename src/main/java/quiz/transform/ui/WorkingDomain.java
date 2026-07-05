package quiz.transform.ui;

import quiz.Quizable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link DomainModel} that layers PROJECT-derived classes over a base domain.
 * Derived types join {@link #types()} and their fields join the pool, so an
 * operation can consume a class produced by an earlier PROJECT — the composable
 * transform graph. Derived instances are added to {@link #instances()} for the view.
 */
public final class WorkingDomain implements DomainModel {

    private final DomainModel base;
    private final Map<String, DerivedClass> derived = new LinkedHashMap<>();

    public WorkingDomain(DomainModel base) {
        this.base = base;
    }

    public void add(DerivedClass d) {
        if (d != null && d.type() != null && !d.type().isBlank()) {
            derived.put(d.type(), d);
        }
    }

    public boolean isDerived(String type) {
        return derived.containsKey(type);
    }

    @Override public List<String> types() {
        List<String> t = new ArrayList<>(base.types());
        t.addAll(derived.keySet());
        return t;
    }

    @Override public List<DomainField> fields(String type) {
        DerivedClass d = derived.get(type);
        return d != null ? d.fields() : base.fields(type);
    }

    @Override public Collection<? extends Quizable> instances() {
        List<Quizable> all = new ArrayList<>(base.instances());
        for (DerivedClass d : derived.values()) {
            all.addAll(d.instances());
        }
        return all;
    }

    @Override public Class<? extends Quizable> universe() {
        // Broad enough to keep BOTH base instances and PROJECT-derived
        // DynamicQuizables (which the base universe, e.g. a snapshot's WDO, excludes).
        return Quizable.class;
    }
}
