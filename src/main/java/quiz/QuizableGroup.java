package quiz;

import quiz.annotations.NotQuizableField;
import quiz.annotations.QuizableReference;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public class QuizableGroup extends QuizableAdapter {

    /**
     * What a node means in a faceted tree:
     * <ul>
     *   <li>{@code UNIVERSE} — the root: all members (a valid quiz scope);</li>
     *   <li>{@code FACET} — a dimension header (League, City): holds the whole
     *       universe transitively, so it's <i>not</i> a useful filter — the UI
     *       shows it as a non-selectable header;</li>
     *   <li>{@code BUCKET} — one facet value (NBA, Boston): the real subset.</li>
     * </ul>
     * Hand-built trees leave every node {@code UNIVERSE} (the default), so they
     * stay fully selectable exactly as before.
     */
    public enum Role { UNIVERSE, FACET, BUCKET }

    private final String name;
    private Role role = Role.UNIVERSE;

    /**
     * For a reference bucket: the entity this bucket <i>stands for</i> (e.g. the
     * {@code Language} or parent {@code Creature} its members share), so the UI
     * can show that entity's own card. Not part of the quizable field set.
     */
    @NotQuizableField
    private Quizable keyRef;

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

    public Role getRole() {
        return role;
    }

    public QuizableGroup role(Role role) {
        this.role = role == null ? Role.UNIVERSE : role;
        return this;
    }

    public Quizable getKeyRef() {
        return keyRef;
    }

    public QuizableGroup keyRef(Quizable keyRef) {
        this.keyRef = keyRef;
        return this;
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