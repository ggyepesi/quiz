package wikidata.explore.extract;

import batch.WorkDescriptor;
import batch.WorkUnit;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * THE member-batched unit of work: one SPARQL query bounded by a batch of member QIDs.
 *
 * <p>Every Stage-2 query has this shape — a field's values, a residual scalar pass — and
 * they differ only in the query text and in what is done with the rows. So the unit is
 * one class parameterised by a query builder, and the difference lives in the caller's
 * {@link batch.ResultCommitter}. A second member-batching class would be a second place
 * for splitting, keying and restore to drift.
 *
 * <p>{@link #execute()} returns the RAW rows and touches no shared state. Mapping rows
 * into the registry is publication, and publication happens once, after the whole batch
 * has succeeded — otherwise a retried batch applies its early rows twice.
 */
public final class MemberBatchQueryUnit implements WorkUnit<List<WikidataBinding>> {

    /** Descriptor parameter holding the member QIDs — they ARE the partition. */
    public static final String P_MEMBERS = "members";

    private final String type;
    private final String label;
    private final List<String> memberQids;
    private final Function<List<String>, String> queryFor;
    private final WikidataSparqlClient client;
    private final Map<String, String> extraParameters;

    public MemberBatchQueryUnit(
            String type,
            String label,
            List<String> memberQids,
            Function<List<String>, String> queryFor,
            WikidataSparqlClient client,
            Map<String, String> extraParameters) {
        this.type = type;
        this.label = label;
        this.memberQids = List.copyOf(memberQids);
        this.queryFor = queryFor;
        this.client = client;
        this.extraParameters = extraParameters == null
                ? Map.of() : Map.copyOf(extraParameters);
    }

    /** The member QIDs this batch covers — a committer that needs them (or a caller
     *  rebuilding the unit on resume) reads them from the descriptor. */
    public List<String> memberQids() {
        return memberQids;
    }

    @Override
    public WorkDescriptor descriptor() {
        String members = String.join(",", memberQids);
        Map<String, String> parameters = new LinkedHashMap<>(extraParameters);
        parameters.put(P_MEMBERS, members);
        return new WorkDescriptor(
                type,
                // Distinct per (label, exact member set): the executor rejects duplicate
                // keys outright, so a collision fails the run rather than dropping work.
                label + "#" + members.hashCode() + "@" + memberQids.size(),
                label + " (" + memberQids.size() + " members)",
                parameters);
    }

    @Override
    public List<WikidataBinding> execute() throws Exception {
        return client.query(queryFor.apply(memberQids));
    }

    @Override
    public List<? extends WorkUnit<List<WikidataBinding>>> split() {
        if (memberQids.size() < 2) {
            return List.of();
        }
        int middle = memberQids.size() / 2;
        return List.of(
                withMembers(memberQids.subList(0, middle)),
                withMembers(memberQids.subList(middle, memberQids.size())));
    }

    private MemberBatchQueryUnit withMembers(List<String> members) {
        return new MemberBatchQueryUnit(
                type, label, members, queryFor, client, extraParameters);
    }
}
