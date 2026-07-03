package quiz.transform;

import org.junit.jupiter.api.Test;
import quiz.DynamicFields;
import quiz.Quizable;
import quiz.QuizableAdapter;
import quiz.QuizableGroup;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The declarative ViewSpec builds a filtering + grouping View over DynamicFields. */
class ViewSpecTest {

    public static class Rec extends QuizableAdapter implements DynamicFields {
        final Map<String, Object> map = new LinkedHashMap<>();
        public Rec() {}
        Rec(Object... kv) { for (int i = 0; i < kv.length; i += 2) map.put((String) kv[i], kv[i + 1]); }
        @Override public Map<String, Object> dynamicFieldValues() { return map; }
        @Override public String getIdentifier() { return String.valueOf(map.get("id")); }
        @Override public String getDisplayName() { return String.valueOf(map.getOrDefault("who", getIdentifier())); }
    }

    @Test void specBuildsFilterAndGroupingView() {
        List<Rec> recs = List.of(
                new Rec("id", "1", "won", true,  "cat", "A", "who", "Pacino"),
                new Rec("id", "2", "won", false, "cat", "A", "who", "Denzel"),
                new Rec("id", "3", "won", true,  "cat", "B", "who", "Hanks"));

        ViewSpec spec = new ViewSpec();
        spec.name = "Winners";
        spec.memberType = "Rec";
        spec.filters.add(new ViewSpec.Filter("won", true));
        spec.grouping.add(new ViewSpec.GroupBy("cat", "VALUE"));

        View view = spec.build(Rec.class);

        List<? extends Quizable> members = view.members(recs);
        assertEquals(2, members.size(), "won==true only");

        QuizableGroup root = view.render(recs);
        assertEquals(1, root.getChild("cat").getChild("A").getMembers().size(),
                "cat A has one winner (Pacino), not the loser");
        assertEquals("Hanks", root.getChild("cat").getChild("B")
                .getMembers().iterator().next().getDisplayName());
    }
}
