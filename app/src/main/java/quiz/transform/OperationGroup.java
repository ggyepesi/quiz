package quiz.transform;

import objectview.Viewable;
import quiz.transform.pipeline.ui.FilterCondition;
import quiz.transform.pipeline.ui.FilterPredicates;

import java.util.Collection;

/** Named filter-result group with an immutable operation rule. */
public final class OperationGroup extends EditableGroup implements ProducedGroup {

    private final String memberType;
    private final FilterCondition condition;

    public OperationGroup(String name, String memberType, FilterCondition condition) {
        super(name);
        this.memberType = memberType;
        this.condition = java.util.Objects.requireNonNull(condition, "condition");
    }

    public String memberType() { return memberType; }
    public FilterCondition condition() { return condition; }

    @Override public String getDisplayName() {
        return name() + "  [filter: " + condition + "]";
    }

    @Override public String ruleDescription() { return "Filter: " + condition; }

    @Override protected java.util.Map<String, Object> ruleFields() {
        java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("producer", "filter");
        fields.put("memberType", memberType);
        fields.put("filterField", condition.field().field());
        fields.put("filterOperator", condition.operator().name());
        fields.put("filterValue", condition.value());
        fields.put("filterValue2", condition.value2());
        return fields;
    }

    @Override public void reproduce(Collection<? extends Viewable> parentMembers) {
        replaceMembers(parentMembers == null ? java.util.List.of() : parentMembers.stream()
                .filter(member -> FilterPredicates.matches(member, condition))
                .toList());
    }
}
