package quiz.web;

import objectview.Viewable;

import com.fasterxml.jackson.annotation.JsonInclude;
import objectview.group.ViewableGroup;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A node in a dataset's group hierarchy, for the quiz config UI. {@code
 * fullName} uniquely identifies the group (used to scope a quiz); {@code
 * count} is the number of members explicitly assigned to it. Child membership never
 * changes a parent's scope.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GroupNode(
        String name, String fullName, String role, int count,
        ViewableView.Ref ref, List<GroupNode> children) {

    public static GroupNode of(ViewableGroup<?> g) {
        List<GroupNode> kids = new ArrayList<>();
        for (ViewableGroup<?> c : g.getChildren()) {
            kids.add(of(c));
        }

        Set<String> ids = new HashSet<>();
        for (Viewable member : g.getMembers()) {
            if (member != null) ids.add(member.getIdentifier());
        }

        Viewable k = g.getKeyRef();
        ViewableView.Ref ref = k == null
                ? null
                : new ViewableView.Ref(
                        k.getIdentifier(), k.getDisplayName(), k.typeName(), null, null);

        return new GroupNode(
                g.getName(), g.getFullName(), g.getRole().name(),
                ids.size(), ref, kids.isEmpty() ? null : kids);
    }

}
