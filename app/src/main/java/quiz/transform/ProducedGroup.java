package quiz.transform;

import objectview.Viewable;

import java.util.Collection;

/** A group whose explicit members can be reproduced from an immutable rule. */
public interface ProducedGroup {
    void reproduce(Collection<? extends Viewable> parentMembers);

    /** Reproduce a rule which also needs other loaded classes or named selections. */
    default void reproduce(Collection<? extends Viewable> parentMembers,
                           domain.DomainModel domain) {
        reproduce(parentMembers);
    }
    String ruleDescription();

    /** Why the last reproduction could not produce what the rule says, or "" when it
     *  did. A rule that silently produces less than it states reads as a real answer. */
    default String problem() { return ""; }
}
