package quiz.curation;

import objectview.Viewable;

import java.util.Collection;
import java.util.List;

/** Immutable hand-off between Validation, Resolve Identities, the main instance view,
 * and the shared curation workspace. The scope is explicit; each selected field becomes
 * its own task because its editor, tools and eligible members can differ. */
public record CurationPlan(
        String label, List<Viewable> instances, List<CurationTask> tasks) {

    public CurationPlan(String label,
                        Collection<? extends Viewable> instances,
                        Collection<CurationTask> tasks) {
        this(label,
                instances == null ? List.of() : instances.stream()
                        .filter(java.util.Objects::nonNull)
                        .map(Viewable.class::cast).toList(),
                tasks == null ? List.of() : List.copyOf(tasks));
    }

    public CurationPlan {
        label = label == null || label.isBlank() ? "Curation" : label;
        instances = instances == null ? List.of() : List.copyOf(instances);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        if (tasks.isEmpty()) throw new IllegalArgumentException("A curation plan needs a task");
    }
}
