package quiz;

import quiz.annotations.QuizableReference;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class QuizableGroup extends QuizableAdapter {
    private final String name;

    @QuizableReference
    private QuizableGroup parent;

    @QuizableReference
    private final Map<String, QuizableGroup> children = new TreeMap<>();

    @QuizableReference
    private final Map<String, Quizable> members = new TreeMap<>();

    public QuizableGroup(String name) {
        this.name = name;
    }

    @Override
    public String getIdentifier() { return name; }

    @Override
    public String getDisplayName() { return name; }

    @Override
    public QuizableGroup createNew() {
        return new QuizableGroup("");
    }

    public QuizableGroup getOrCreateChild(String name) {
        QuizableGroup child =
                children.computeIfAbsent(
                        name,
                        QuizableGroup::new
                );

        child.parent = this;

        return child;
    }

    public void addChild(QuizableGroup child) {
        if (child == null || child.getIdentifier() == null) {
            return;
        }

        children.putIfAbsent(child.getIdentifier(), child);
        child.parent = this;
    }

    public void addMember(Quizable member) {
        if (member == null || member.getIdentifier() == null) {
            return;
        }

        boolean addedHere =
                members.putIfAbsent(
                        member.getIdentifier(),
                        member
                ) == null;

        if (addedHere && parent != null) {
            parent.addMember(member);
        }
    }

    public void addMembers(Collection<? extends Quizable> members) {
        if (members == null) {
            return;
        }

        for (Quizable member : members) {
            addMember(member);
        }
    }

    public boolean contains(String memberName) {
        return members.containsKey(memberName);
    }

    public QuizableGroup getChild(String name) {
        return children.get(name);
    }

    public Collection<QuizableGroup> getChildren() {
        return children.values();
    }

    public Map<String, QuizableGroup> getChildrenMap() {
        return children;
    }

    public Collection<Quizable> getMembers() {
        return members.values();
    }

    public Map<String, Quizable> getMemberMap() {
        return Collections.unmodifiableMap(members);
    }

    public QuizableGroup getParent() {
        return parent;
    }

    public String getFullName() {
        return parent == null
                ? getDisplayName()
                : parent.getFullName() + "/" + getDisplayName();
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}