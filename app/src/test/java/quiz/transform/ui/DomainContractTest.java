package quiz.transform.ui;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A wrapping domain must forward everything its base declares, and recompute everything
 * that is derived from those declarations.
 *
 * <p>{@link DomainModel} carries both kinds of method and used to distinguish them nowhere.
 * The obligations are opposite and both failures are silent: a {@link Declared} fact that a
 * wrapper does not forward makes the base's declaration disappear — which is how a model's
 * category recipe went unheard the moment a PROJECT-derived class was layered over the
 * domain (39f6f2a), and how its declared fallback source went the same way weeks later — and
 * a {@link Derived} method that a wrapper DOES forward is computed from the base's
 * declarations instead of the wrapper's, quietly ignoring the very thing the wrapper exists
 * to change.
 *
 * <p>So the classification is part of the contract, and this checks all three parts of it:
 * every method is classified, every declaration is forwarded by {@link DelegatingDomainModel},
 * and no derivation is.
 */
class DomainContractTest {

    @Test void everyMethodOnTheContractSaysWhichKindItIs() {
        Set<String> unclassified = new TreeSet<>();
        for (Method method : DomainModel.class.getDeclaredMethods()) {
            if (method.isSynthetic()) continue;
            boolean declared = method.isAnnotationPresent(Declared.class);
            boolean derived = method.isAnnotationPresent(Derived.class);
            if (declared == derived) unclassified.add(signature(method));
        }

        assertEquals(Set.of(), unclassified,
                "a method that is neither @Declared nor @Derived leaves the next wrapper "
                        + "guessing, which is exactly how the recurring bug got in");
    }

    @Test void aWrappingDomainForwardsEveryFactItsBaseDeclares() {
        Set<String> notForwarded = new TreeSet<>();
        for (Method method : annotated(Declared.class)) {
            if (!overriddenByDelegate(method)) notForwarded.add(signature(method));
        }

        assertEquals(Set.of(), notForwarded,
                "DelegatingDomainModel must forward these, or every wrapper answers for its "
                        + "base and the base's declaration is never heard");
    }

    @Test void aWrappingDomainRecomputesWhatIsDerivedRatherThanForwardingIt() {
        Set<String> wronglyForwarded = new TreeSet<>();
        for (Method method : annotated(Derived.class)) {
            if (overriddenByDelegate(method)) wronglyForwarded.add(signature(method));
        }

        assertEquals(Set.of(), wronglyForwarded,
                "forwarding a derivation computes it from the base's declarations, ignoring "
                        + "whatever the wrapper itself declares");
    }

    /** The classification has to match reality, not just be self-consistent: a method no
     *  backing ever overrides is a derivation whatever it is labelled. */
    @Test void nothingLabelledDerivedIsOverriddenByABackingAnyway() {
        Set<String> derivedNames = new TreeSet<>();
        for (Method method : annotated(Derived.class)) derivedNames.add(method.getName());

        Set<String> overriddenAnyway = new TreeSet<>();
        for (Class<?> backing : List.of(SnapshotDomainNames.SNAPSHOT, SnapshotDomainNames.REFLECTION)) {
            for (Method method : backing.getDeclaredMethods()) {
                if (derivedNames.contains(method.getName())) overriddenAnyway.add(
                        backing.getSimpleName() + "." + method.getName());
            }
        }

        assertTrue(overriddenAnyway.isEmpty(), overriddenAnyway
                + " — a backing overrides it, so it states something and is @Declared");
    }

    private static List<Method> annotated(Class<? extends java.lang.annotation.Annotation> kind) {
        return Arrays.stream(DomainModel.class.getDeclaredMethods())
                .filter(m -> !m.isSynthetic())
                .filter(m -> m.isAnnotationPresent(kind))
                .toList();
    }

    private static boolean overriddenByDelegate(Method method) {
        try {
            Method found = DelegatingDomainModel.class
                    .getDeclaredMethod(method.getName(), method.getParameterTypes());
            return found != null;
        } catch (NoSuchMethodException absent) {
            return false;
        }
    }

    private static String signature(Method method) {
        StringBuilder out = new StringBuilder(method.getName()).append('(');
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (i > 0) out.append(", ");
            out.append(types[i].getSimpleName());
        }
        return out.append(')').toString();
    }

    /** Package-private backings referenced by class rather than by name. */
    private static final class SnapshotDomainNames {
        static final Class<?> SNAPSHOT = quiz.transform.app.SnapshotDomain.class;
        static final Class<?> REFLECTION = ReflectionDomain.class;
    }
}
