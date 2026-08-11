package wikidata.explore.extract;

import batch.WorkDescriptor;
import batch.WorkUnit;
import wikidata.WikidataBinding;
import wikidata.WikidataSparqlClient;
import wikidata.explore.model.RuleDirection;
import wikidata.explore.query.template.rule.RuleNodeQueryBuilder;
import wikidata.explore.rule.RuleIncludedField;
import wikidata.WikidataIds;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One member-QID batch of a single field's values, as a splittable unit of work.
 *
 * <p>{@link #execute()} returns the {@code (member -> values)} pairs it read and touches
 * nothing else. That separation is the point: the previous loop merged straight into the
 * shared registry as rows arrived, so a batch that failed halfway and was retried merged
 * its early rows twice. The executor now publishes through a committer only once the
 * whole batch has succeeded.
 *
 * <p>Splitting halves the member list, which is exactly the narrowing a too-heavy query
 * needs — the VALUES clause shrinks and the query the endpoint refused becomes one it
 * will answer.
 */
public final class MemberFieldWorkUnit implements WorkUnit<Map<String, List<String>>> {

    /** {@link WorkDescriptor#type()} for units this class restores. */
    public static final String TYPE = "wikidata.memberField";

    private static final String P_FIELD = "field";
    private static final String P_PID = "pid";
    private static final String P_DIRECTION = "direction";
    private static final String P_MEMBERS = "members";

    private final RuleIncludedField field;
    private final List<String> memberQids;
    private final WikidataSparqlClient client;

    public MemberFieldWorkUnit(
            RuleIncludedField field, List<String> memberQids, WikidataSparqlClient client) {
        this.field = field;
        this.memberQids = List.copyOf(memberQids);
        this.client = client;
    }

    /** Rebuilds a unit from a checkpoint record. The member QIDs travel in the
     *  descriptor because they ARE the partition — nothing else identifies it. */
    public static MemberFieldWorkUnit restore(
            WorkDescriptor descriptor, WikidataSparqlClient client) {
        Map<String, String> p = descriptor.parameters();
        RuleIncludedField field = new RuleIncludedField();
        field.fieldName(p.get(P_FIELD));
        field.propertyPid(p.get(P_PID));
        field.direction(RuleDirection.valueOf(p.get(P_DIRECTION)));
        return new MemberFieldWorkUnit(
                field, List.of(p.getOrDefault(P_MEMBERS, "").split(",")), client);
    }

    @Override
    public WorkDescriptor descriptor() {
        String members = String.join(",", memberQids);
        return new WorkDescriptor(
                TYPE,
                field.fieldName() + "#" + members.hashCode() + "@" + memberQids.size(),
                "Member field \"" + field.fieldName() + "\" ("
                        + memberQids.size() + " members)",
                Map.of(P_FIELD, field.fieldName(),
                       P_PID, field.propertyPid(),
                       P_DIRECTION, field.direction().name(),
                       P_MEMBERS, members));
    }

    /** The field this batch fills — the committer needs it, and it is not derivable
     *  from the returned pairs. */
    public RuleIncludedField field() {
        return field;
    }

    @Override
    public Map<String, List<String>> execute() throws Exception {
        String sparql = RuleNodeQueryBuilder.memberFieldBatchQuery(field, memberQids);
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (WikidataBinding row : client.query(sparql)) {
            String memberQid = row.qid("value");
            String fieldValueQid = row.qid("fieldValue");
            if (memberQid == null || !WikidataIds.isQid(memberQid)) continue;
            if (fieldValueQid == null || !WikidataIds.isQid(fieldValueQid)) continue;
            values.computeIfAbsent(memberQid, k -> new ArrayList<>()).add(fieldValueQid);
        }
        return values;
    }

    @Override
    public List<? extends WorkUnit<Map<String, List<String>>>> split() {
        if (memberQids.size() < 2) {
            return List.of();
        }
        int middle = memberQids.size() / 2;
        return List.of(
                new MemberFieldWorkUnit(field, memberQids.subList(0, middle), client),
                new MemberFieldWorkUnit(
                        field, memberQids.subList(middle, memberQids.size()), client));
    }
}
