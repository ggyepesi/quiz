package wikidata.explore.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a class is produced FROM, back to one that has a population of its own.
 *
 * <p>This is one question asked of {@link ClassDependencies}: follow the edges whose kind
 * is a PRODUCTION dependency — the ones that mean "this class has no members without
 * that one" — until a class is reached that has its own. Which constructs contribute
 * those edges is the graph's business, so a new one arrives here without this class
 * changing.
 *
 * <p>Links run from the class in hand outwards, ending at {@link #population()}: the
 * first class a sample can actually bound.
 */
public record ProductionChain(
        List<ClassDependencies.Edge> links, GeneratedClassModel population,
        String refusal) {

    public ProductionChain {
        links = links == null ? List.of() : List.copyOf(links);
        refusal = refusal == null ? "" : refusal.trim();
    }

    /** A class that has its own population: no links, and it is its own population. */
    public static ProductionChain population(GeneratedClassModel clazz) {
        return new ProductionChain(List.of(), clazz, "");
    }

    public static ProductionChain refused(String refusal) {
        return new ProductionChain(List.of(), null, refusal);
    }

    /** Derived: produced from another class rather than queried for. */
    public boolean derived() {
        return !links.isEmpty();
    }

    /** Whether a population was reached — false when the chain refuses. */
    public boolean resolved() {
        return refusal.isEmpty() && population != null;
    }

    public boolean has(ClassDependencies.Kind kind) {
        return links.stream().anyMatch(link -> link.kind() == kind);
    }

    /** The last link, whose dependency is the population itself. */
    public ClassDependencies.Edge nearestPopulation() {
        return links.isEmpty() ? null : links.getLast();
    }

    /**
     * The chain from {@code clazz} outwards.
     *
     * <p>Refuses rather than guesses in the two states where no single population speaks
     * for the class: a cycle, and a class produced from several other classes. Two
     * production SITES naming the same owner agree perfectly and are not that — it is
     * how many populations disagree that matters, not how many fields produce it.
     */
    public static ProductionChain of(
            GeneratedClassModel clazz, GeneratedProjectModel project) {
        if (clazz == null || project == null) {
            return refused("There is no class to trace.");
        }
        List<ClassDependencies.Edge> links = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>();
        GeneratedClassModel current = clazz;
        while (true) {
            if (!visiting.add(clean(current.className()))) {
                return refused("Production cycle: " + String.join(" \u2192 ", visiting)
                        + " \u2192 " + current.className() + ".");
            }
            List<ClassDependencies.Edge> produced = ClassDependencies.dependenciesOf(
                    current, project, ClassDependencies.Kind::production);
            if (produced.isEmpty()) {
                // Either it has its own population, or it is a derived class nothing
                // produces — which the pattern knows and the edge list cannot say.
                if (current != clazz && !links.isEmpty()) {
                    return new ProductionChain(links, current, "");
                }
                MembershipPattern pattern = MembershipPattern.of(current, project);
                return pattern == MembershipPattern.OWNED_COMPONENT
                        || pattern == MembershipPattern.AGGREGATED
                        ? refused("Nothing produces " + current.className()
                                + ", so there is no population behind it.")
                        : new ProductionChain(links, current, "");
            }
            List<GeneratedClassModel> populations = new ArrayList<>();
            for (ClassDependencies.Edge edge : produced) {
                if (populations.stream().noneMatch(known -> clean(known.className())
                        .equals(clean(edge.dependency().className())))) {
                    populations.add(edge.dependency());
                }
            }
            if (populations.size() > 1) {
                return refused(current.className() + " is produced from "
                        + populations.stream().map(GeneratedClassModel::className)
                                .collect(java.util.stream.Collectors.joining(" and "))
                        + ", and one population has to speak for it.");
            }
            links.add(produced.getFirst());
            current = populations.getFirst();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
