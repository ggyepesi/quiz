package wikidata.explore.model;

/**
 * How a child-object edge (e.g. Constellation -> stars) treats the referenced
 * class's instance membership (the class's P31 = Qxxx "instance of").
 *
 * <p>Membership is normally a property of the CLASS (Star = instance of Q523),
 * so it applies wherever the class is produced. But a relationship sometimes
 * wants to relax it: a constellation's "stars" are reached via P59, and we want
 * the bright NAMED ones (incl. variable/double stars typed as a subclass, not
 * Q523 exactly), so the edge drops the Q523 constraint and leans on
 * P59 + magnitude + label instead — while the root Star class keeps Q523.
 */
public enum EdgeMembershipMode {

    /** Use the referenced class's own membership (P31 = its instance QID). */
    INHERIT,

    /** Drop the class membership for this edge (no P31 constraint on values). */
    NONE
}
