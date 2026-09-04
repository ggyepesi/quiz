package wikidata.explore.model;

import datasource.schema.FieldType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every kind of class dependency is checked, including in combination.
 *
 * <p>This is the rule made self-enforcing. The defect it guards was not that a walker had
 * a bug — each of the three was correct about its own kind of edge — but that adding a
 * kind meant adding a walker, and a cycle mixing two kinds belonged to neither. So the
 * test is generated FROM the enum: it builds a two-class cycle for every acyclic kind and
 * for every ordered pair of them, and the helper that builds an edge switches
 * exhaustively over {@link ClassDependencies.Kind}. A new contributor stops the build
 * until someone says what an edge of it looks like — which is the moment to notice it
 * also has to say whether it may cycle.
 */
class ClassDependencyKindsAreCompleteTest {

    @Test void aCycleOfEveryAcyclicKindIsReported() {
        for (ClassDependencies.Kind kind : acyclicKinds()) {
            GeneratedProjectModel project = cycle(kind, kind);
            assertTrue(reportsCycle(project),
                    kind + " closes a cycle nothing reports: "
                            + GeneratedProjectModelValidator.validate(project).format());
        }
    }

    @Test void aCycleMixingTwoKindsIsReported() {
        for (ClassDependencies.Kind first : acyclicKinds()) {
            for (ClassDependencies.Kind second : acyclicKinds()) {
                if (first == second) continue;
                GeneratedProjectModel project = cycle(first, second);
                assertTrue(reportsCycle(project),
                        "a cycle that goes out by " + first + " and back by " + second
                                + " belongs to neither kind's own walker: "
                                + GeneratedProjectModelValidator.validate(project).format());
            }
        }
    }

    /** A reference cycle is a fact about the data, and must NOT be reported. */
    @Test void aReferenceCycleIsNotAProblem() {
        assertFalse(ClassDependencies.Kind.REFERENCES.acyclic());

        GeneratedProjectModel project = cycle(
                ClassDependencies.Kind.REFERENCES, ClassDependencies.Kind.REFERENCES);

        assertFalse(reportsCycle(project),
                "Person.spouse -> Person is how people work: "
                        + GeneratedProjectModelValidator.validate(project).format());
    }

    /** A production kind is one whose dependent has no members without its dependency. */
    @Test void everyProductionKindIsAlsoAcyclic() {
        for (ClassDependencies.Kind kind : ClassDependencies.Kind.values()) {
            if (kind.production()) {
                assertTrue(kind.acyclic(),
                        kind + " gives a class its members, so a cycle in it is a class "
                                + "whose members depend on themselves");
            }
        }
    }

    private static List<ClassDependencies.Kind> acyclicKinds() {
        List<ClassDependencies.Kind> kinds = new ArrayList<>();
        for (ClassDependencies.Kind kind : ClassDependencies.Kind.values()) {
            if (kind.acyclic()) kinds.add(kind);
        }
        assertFalse(kinds.isEmpty(), "some kind must not cycle, or nothing is checked");
        return kinds;
    }

    private static boolean reportsCycle(GeneratedProjectModel project) {
        return GeneratedProjectModelValidator.validate(project).errors().stream()
                .anyMatch(problem -> problem.message().contains("Class dependency cycle"));
    }

    /** Alpha depends on Beta by {@code out}, Beta on Alpha by {@code back}. */
    private static GeneratedProjectModel cycle(
            ClassDependencies.Kind out, ClassDependencies.Kind back) {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel alpha = new GeneratedClassModel("Alpha");
        GeneratedClassModel beta = new GeneratedClassModel("Beta");
        project.addClass(alpha);
        project.addClass(beta);
        dependOn(alpha, beta, out);
        dependOn(beta, alpha, back);
        return project;
    }

    /**
     * Makes {@code dependent} depend on {@code dependency}, that way.
     *
     * <p>Exhaustive on purpose: a new {@link ClassDependencies.Kind} fails to compile
     * here, which is the point of the test.
     */
    private static void dependOn(GeneratedClassModel dependent,
            GeneratedClassModel dependency, ClassDependencies.Kind kind) {
        switch (kind) {
            case OWNED -> {
                // The site lives on the OWNER, targeting the part — being owned is a
                // property of the class, where it is produced a property of the field.
                dependent.ownedClass(true);
                GeneratedFieldModel site = dependency.addField(
                        "part" + dependent.className(),
                        FieldType.ENTITY, FieldCardinality.SINGLE);
                site.entityClassName(dependent.className());
                site.mapping().productionKind(FieldProductionKind.OWNED_COMPONENT);
            }
            case AGGREGATED -> {
                dependent.classKind(ClassKind.AGGREGATE);
                AggregateClassSource groups =
                        new AggregateClassSource(dependency.className(), "members");
                groups.keys().add(new AggregateClassSource.Key("key", "key"));
                dependent.aggregateSource(groups);
            }
            case EXTENDS -> dependent.baseClassName(dependency.className());
            case REFERENCES -> {
                GeneratedFieldModel reference = dependent.addField(
                        "refers" + dependency.className(),
                        FieldType.ENTITY, FieldCardinality.SINGLE);
                reference.entityClassName(dependency.className());
            }
        }
    }
}
