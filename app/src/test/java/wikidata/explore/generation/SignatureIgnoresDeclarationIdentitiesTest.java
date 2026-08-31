package wikidata.explore.generation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A declaration identity is minted per declaration and is unique per constructed object.
 * The model signature answers "would this model generate what the snapshot holds", so an
 * identity must not enter it: hashing one made two structurally identical models sign
 * differently, and every run reported its own instances as stale.
 *
 * <p>{@link DomainSave} names the excluded fields explicitly, because {@code operationId}
 * and {@code providerId} also end in "Id" and decide which provider runs which operation
 * — a rule over the spelling would drop exactly what the fingerprint is for.
 *
 * <p>An explicit list needs something to keep it complete. The guard inspects actual
 * persisted fields rather than matching local variables which merely discuss identity.
 */
class SignatureIgnoresDeclarationIdentitiesTest {

    /**
     * How a declaration identity is spelled where it is DECLARED. A name followed by "("
     * is a method — {@code ensureDeclarationId(…)} names an identity without being one.
     */
    private static final Pattern IDENTITY_FIELD = Pattern.compile(
            "(?:[a-z][A-Za-z]*(?:DeclarationId|ClassId|SelectionId)"
                    + "|declarationId|classId)");

    /**
     * Only what is SERIALIZED into a model can reach the fingerprint. The compiled shape
     * has identities of its own — {@code CompiledProjectModel.rootClassId} — and never
     * goes to disk, so scanning every package would report fields that cannot be hashed.
     */
    private static final List<String> PERSISTED_MODEL_PACKAGES = List.of(
            "src/main/java/wikidata/explore/model",
            "src/main/java/datasource/api");

    @Test void everyDeclarationIdentityIsExcludedFromTheFingerprint() throws Exception {
        Set<String> found = new LinkedHashSet<>();
        for (Class<?> type : persistedModelClasses()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && IDENTITY_FIELD.matcher(field.getName()).matches()) {
                    found.add(field.getName());
                }
            }
        }

        // A count would drift; these two are the shapes the scan exists to see — a bare
        // identity on a model class, and one reached through a source binding.
        assertTrue(found.contains("declarationId") && found.contains("classDeclarationId"),
                "the scan stopped matching how identities are spelled: " + found);
        Set<String> excluded = DomainSave.declarationIdentityFields();
        Set<String> missing = new LinkedHashSet<>(found);
        missing.removeAll(excluded);

        assertEquals(Set.of(), missing,
                "these identities would be hashed into the model signature, so two "
                        + "identical models would sign differently: " + missing);
        Set<String> stale = new LinkedHashSet<>(excluded);
        stale.removeAll(found);
        assertEquals(Set.of(), stale,
                "these exclusions no longer name persisted declaration identities: "
                        + stale);
    }

    @Test void twoStructurallyIdenticalModelsSignTheSame() {
        assertEquals(DomainSave.signature(bare()), DomainSave.signature(bare()),
                "an identity is minted per object and says nothing about generation");
    }

    private static wikidata.explore.model.GeneratedProjectModel bare() {
        wikidata.explore.model.GeneratedProjectModel model =
                new wikidata.explore.model.GeneratedProjectModel();
        model.rootClass(new wikidata.explore.model.GeneratedClassModel("Movie"));
        return model;
    }

    private static List<Class<?>> persistedModelClasses() throws Exception {
        Path sourceRoot = Path.of("src/main/java");
        List<Class<?>> out = new java.util.ArrayList<>();
        for (String dir : PERSISTED_MODEL_PACKAGES) {
            try (Stream<Path> files = Files.walk(Path.of(dir))) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String className = sourceRoot.relativize(file).toString()
                            .replace(java.io.File.separatorChar, '.')
                            .replaceFirst("\\.java$", "");
                    out.add(Class.forName(className));
                }
            }
        }
        return out;
    }
}
