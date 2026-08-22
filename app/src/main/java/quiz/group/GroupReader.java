package quiz.group;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Parses group hierarchy from lines of form ==Name==
public class GroupReader {
    // Pattern for groupname (=+groupname=+)
    private static final Pattern groupPattern = Pattern.compile("(?<prefix>\\={2,})(?<group>[^\\=]+)(?<suffix>\\={2,})(?<leftover>[^=]*)");

    private final ViewableGroup root;
    private ViewableGroup group = null;
    // Current path of groups from root to group. The depth (number of = signs - 2) is the index in this list.
    private List<ViewableGroup> ancestors = new ArrayList<>();

    public GroupReader(ViewableGroup root) {
        this.root = root;
        ancestors.add(root);
    }

    public GroupReader(ViewableGroup parent, String name) {
        this(parent.getOrCreateChild(name));
    }

    public void clear() {
        ancestors.clear();
        ancestors.add(root);
        group = root;
    }

    public boolean parseGroup(String line) {
        Matcher matcher = groupPattern.matcher(line);
        if (!matcher.matches()) return false;
        String prefix = matcher.group("prefix");
        if (prefix.length() != matcher.group("suffix").length()) {
            System.out.println("Length mismatch " + prefix + ", " + matcher.group("suffix"));
            System.out.println("Length mismatch " + line);
            return true;
        }
        int depth = prefix.length() - 2;
        if (ancestors.size() < depth + 1) {
            System.out.println("Bad group depth at " + line);
            return false;
        }
        String groupName = matcher.group("group");
        //System.out.println(depth + ", " + line);
        group = ancestors.get(depth).getOrCreateChild(groupName);
        //System.out.println(groupName + ", " + group.getFullName() + ", " + depth);
        ancestors = ancestors.subList(0, depth + 1);
        ancestors.add(group);
        return true;
    }

    // Go up on ancestors until name is found as a child. Return if name has been found.
    public boolean addGroup(String name) {
        int i = ancestors.size() - 1;
        for (; i >= 0; --i) {
            ViewableGroup child = ancestors.get(i).getChild(name);
            if (child != null) {
                group = child;
                ancestors = ancestors.subList(0, i + 1);
                ancestors.add(group);
                return true;
            }
        }
        return false;
    }

    public ViewableGroup getRoot() {
        return root;
    }

    public ViewableGroup getGroup() {
        return group;
    }
}

