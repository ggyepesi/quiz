package quiz.enrichment;

import objectview.Viewable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/** Immutable show-before-run description of identity resolution work. */
public record IdentityResolutionPlan(
        String scope,
        List<Entry> identified,
        List<Entry> unresolved) {

    public IdentityResolutionPlan {
        scope = scope == null ? "Scope" : scope;
        identified = List.copyOf(identified);
        unresolved = List.copyOf(unresolved);
    }

    public static IdentityResolutionPlan inspect(
            String scope,
            Collection<? extends Viewable> members,
            Function<Viewable, String> identity) {
        List<Entry> identified = new ArrayList<>();
        List<Entry> unresolved = new ArrayList<>();
        if (members != null) {
            for (Viewable member : members) {
                if (member == null) continue;
                String qid = identity == null ? null : identity.apply(member);
                Entry entry = new Entry(member, qid);
                (qid == null || qid.isBlank() ? unresolved : identified).add(entry);
            }
        }
        return new IdentityResolutionPlan(scope, identified, unresolved);
    }

    public record Entry(Viewable member, String currentQid) { }
}
