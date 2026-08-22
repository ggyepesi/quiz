package domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import domain.Declared;
import domain.DelegatingDomainModel;
import domain.Derived;
import domain.DomainModel;

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

    /**
     * The contract describes a domain; it is not part of an app and it is not a view. It sat in
     * a Swing package, so the snapshot store imported the transform workbench's user interface
     * to describe a saved file. Only the two model-declaration types are allowed through, and
     * they are named here so that allowance stays a decision rather than a drift.
     */
    @Test void theContractDependsOnTheFoundationAndOnTwoModelDeclarations() throws Exception {
        Set<String> offenders = new TreeSet<>();
        for (java.nio.file.Path file : contractSources()) {
            String source = java.nio.file.Files.readString(file);
            for (String reference : references(source)) {
                if (reference.startsWith("java.") || reference.startsWith("objectview.")) continue;
                if (ALLOWED_DECLARATIONS.contains(reference)) continue;
                offenders.add(file.getFileName() + " → " + reference);
            }
        }

        assertEquals(Set.of(), offenders,
                "a domain contract that names a UI type, an app type or a third source is "
                        + "no longer describing a domain");
    }

    private static final Set<String> ALLOWED_DECLARATIONS = Set.of(
            "wikidata.explore.model.WikipediaCategoryRule",
            "wikidata.explore.model.EntityKindRule");

    /** Imports AND fully-qualified uses: the two allowances above are written inline, so an
     *  import scan alone would have reported this package as depending on nothing. Comments
     *  are stripped first — prose naming a type is not a dependency on it, and the package
     *  documentation has to be able to say what belongs on the other side of this line. */
    private static Set<String> references(String source) {
        source = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
        Set<String> found = new TreeSet<>();
        java.util.regex.Matcher imports = java.util.regex.Pattern
                .compile("^import\\s+(?:static\\s+)?([\\w.]+);", java.util.regex.Pattern.MULTILINE)
                .matcher(source);
        while (imports.find()) found.add(imports.group(1));
        java.util.regex.Matcher qualified = java.util.regex.Pattern
                .compile("(?<![\\w.])((?:[a-z][\\w]*\\.){2,}[A-Z][\\w]*)").matcher(source);
        while (qualified.find()) found.add(qualified.group(1));
        return found;
    }

    private static List<java.nio.file.Path> contractSources() throws Exception {
        java.nio.file.Path source = java.nio.file.Path.of("src/main/java/domain");
        assertTrue(java.nio.file.Files.isDirectory(source),
                "run from the app module: " + source.toAbsolutePath());
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(source)) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
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
        static final Class<?> REFLECTION = quiz.transform.ui.ReflectionDomain.class;
    }
}
