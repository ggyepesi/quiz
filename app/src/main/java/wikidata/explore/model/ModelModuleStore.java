package wikidata.explore.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** File format and exact-version repository for shared model modules. */
public final class ModelModuleStore implements ModelModuleResolver {
    private static final String DIGEST_PREFIX = "sha256:";
    private final Path root;
    private final ObjectMapper mapper = GeneratedProjectModelStore.modelMapper();

    public ModelModuleStore(Path root) {
        this.root = root == null ? Path.of("data/wikidata/modules") : root;
    }

    public static ModelModuleStore standard() {
        String configured = System.getProperty("quiz.model.modules", "").trim();
        if (!configured.isBlank()) return new ModelModuleStore(Path.of(configured));
        Path direct = Path.of("data/wikidata/modules");
        return new ModelModuleStore(Files.isDirectory(Path.of("data/wikidata"))
                ? direct : Path.of("../data/wikidata/modules"));
    }

    @Override public ModelModule resolve(String moduleId, String version) throws IOException {
        Path file = file(moduleId, version);
        if (!Files.isRegularFile(file)) {
            throw new IOException("Model module not found: " + clean(moduleId) + "@"
                    + clean(version) + " (expected " + file + ")");
        }
        return load(file.toFile());
    }

    public ModelModule load(File file) throws IOException {
        ModelModule module = mapper.readValue(file, ModelModule.class);
        try {
            module.normalizeDeclarations();
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid model module " + file + ": "
                    + invalid.getMessage(), invalid);
        }
        String actual = digest(module);
        if (module.contentDigest().isBlank() || !module.contentDigest().equals(actual)) {
            throw new IOException("Model module digest mismatch for " + module.coordinate()
                    + ": declared " + module.contentDigest() + ", actual " + actual);
        }
        return module;
    }

    /** Exact versions available to an import picker; invalid files fail visibly. */
    public List<ModelModuleImport> available() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<ModelModuleImport> result = new ArrayList<>();
        try (var modules = Files.walk(root)) {
            for (Path file : modules.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString()
                            .endsWith(".model-module.json")).toList()) {
                ModelModule module = load(file.toFile());
                result.add(new ModelModuleImport(module.moduleId(), module.version(),
                        module.contentDigest(), module.declarationIds()));
            }
        }
        result.sort(Comparator.comparing((ModelModuleImport pin) -> pin.moduleId())
                .thenComparing(pin -> pin.version()));
        return List.copyOf(result);
    }

    public ModelModuleImport save(ModelModule module) throws IOException {
        if (module == null) throw new IllegalArgumentException("module must not be null");
        module.normalizeDeclarations();
        validate(module);
        String digest = digest(module);
        module.contentDigest(digest);
        Path file = file(module.moduleId(), module.version());
        if (Files.isRegularFile(file)) {
            ModelModule existing = load(file.toFile());
            if (!existing.contentDigest().equals(digest)) {
                throw new IOException("Immutable model module version already exists with "
                        + "different content: " + module.coordinate());
            }
        } else {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), module);
        }
        return new ModelModuleImport(module.moduleId(), module.version(), digest,
                module.declarationIds());
    }

    public static String digest(ModelModule module) throws IOException {
        ObjectMapper mapper = GeneratedProjectModelStore.modelMapper();
        ObjectNode tree = mapper.valueToTree(module);
        tree.remove("contentDigest");
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(tree).getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        StringBuilder out = new StringBuilder(DIGEST_PREFIX);
        for (byte b : hash) out.append(String.format("%02x", b));
        return out.toString();
    }

    private void validate(ModelModule module) throws IOException {
        GeneratedProjectModel declarations = module.declarationsProject();
        module.imports().stream().map(ModelModuleImport::copy)
                .forEach(declarations::addImport);
        GeneratedProjectModel composed;
        try {
            composed = ModelImportResolver.resolve(declarations, this);
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot resolve dependencies of model module "
                    + module.coordinate() + ": " + invalid.getMessage(), invalid);
        }
        var validation = GeneratedProjectModelValidator.validate(composed);
        if (!validation.valid()) {
            throw new IOException("Cannot save invalid model module " + module.coordinate()
                    + ":" + System.lineSeparator() + validation.format());
        }
        if (module.declarationIds().size()
                != module.classes().size() + module.selections().size()) {
            throw new IOException("Model module declaration identities are not unique: "
                    + module.coordinate());
        }
        for (ModelModuleImport dependency : module.imports()) {
            if (dependency == null || !dependency.complete()) {
                throw new IOException("Incomplete module dependency in " + module.coordinate());
            }
        }
    }

    private Path file(String moduleId, String version) {
        return root.resolve(safe(moduleId)).resolve(safe(version) + ".model-module.json");
    }

    private static String safe(String value) {
        String clean = clean(value);
        if (!clean.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid model-module coordinate part: " + value);
        }
        return clean;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
