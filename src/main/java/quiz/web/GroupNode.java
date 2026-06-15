package quiz.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import quiz.Quizable;
import quiz.QuizableGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A node in a dataset's group hierarchy, for the quiz config UI. {@code
 * fullName} uniquely identifies the group (used to scope a quiz); {@code
 * count} is the number of distinct members reachable under it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupNode(String name, String fullName, int count, List<GroupNode> children) {

    public static GroupNode of(QuizableGroup g) {
        List<GroupNode> kids = new ArrayList<>();
        for (QuizableGroup c : g.getChildren()) {
            kids.add(of(c));
        }

        Set<String> ids = new HashSet<>();
        collectIds(g, ids);

        return new GroupNode(
                g.getName(), g.getFullName(), ids.size(), kids.isEmpty() ? null : kids);
    }

    private static void collectIds(QuizableGroup g, Set<String> ids) {
        for (Quizable m : g.getMembers()) {
            if (m != null) {
                ids.add(m.getIdentifier());
            }
        }
        for (QuizableGroup c : g.getChildren()) {
            collectIds(c, ids);
        }
    }
}
