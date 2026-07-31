package quiz.transform;

import objectview.Viewable;

import java.util.Collection;

/** A group whose explicit members can be reproduced from an immutable rule. */
public interface ProducedGroup {
    void reproduce(Collection<? extends Viewable> parentMembers);
    String ruleDescription();
}
