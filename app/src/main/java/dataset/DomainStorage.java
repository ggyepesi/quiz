package dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import quiz.DatasetRegistry;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Where a named domain's files live, and how the registry records them.
 *
 * <p>All of this was private to {@code ModelBuilderFrame}: the rule turning a domain's name
 * into a folder key, the four paths derived from that key, which registered datasets can be
 * opened at all, what renaming a domain does to the files on disk, and what deleting one
 * leaves behind. None of it is about Swing, and none of it could be tested without a frame —
 * so the rename collision, the re-keying of a registry entry and the fallback after a delete
 * were all reasoned about by reading them.
 *
 * <p>The root directory is a parameter rather than a constant, which is the only reason this
 * can be exercised at all. {@link #inDefaultLocation()} supplies the real one.
 */
public final class DomainStorage {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final File root;

    private DomainStorage(File root) {
        this.root = Objects.requireNonNull(root, "A domain store needs a directory");
    }

    public static DomainStorage inDefaultLocation() {
        return new DomainStorage(new File(aux.Constants.wikidataDataDirectory));
    }

    public static DomainStorage in(File root) {
        return new DomainStorage(root);
    }

    /**
     * The folder key for a domain name: lowercase, letters and digits only. Two names that
     * differ only in punctuation or case therefore share a folder, which is why renaming
     * checks whether the destination already exists rather than assuming a new name is free.
     */
    public static String key(String name) {
        if (name == null || name.isBlank()) return "generated";
        String key = name.toLowerCase().replaceAll("[^a-z0-9]+", "");
        return key.isBlank() ? "generated" : key;
    }

    public File directory(String name) {
        return new File(root, key(name));
    }

    public File modelFile(String name) {
        return file(name, ".model.json");
    }

    public File ruleTreeFile(String name) {
        return file(name, ".ruletree.json");
    }

    public File snapshotFile(String name) {
        return file(name, ".snapshot.json");
    }

    public File registryFile() {
        return new File(root, "datasets.json");
    }

    private File file(String name, String extension) {
        return new File(directory(name), key(name) + extension);
    }

    /**
     * The domains ModelBuilder can offer to open: every saved MODEL, whether or not it has
     * generated a snapshot yet. The dataset registry intentionally contains only complete
     * model/rule-tree/snapshot triples suitable for serving; using it as the editor's sole
     * catalogue made a newly configured domain disappear on restart until its first
     * generation. Model files are therefore the draft catalogue, while registry entries
     * retain their authored display names and order.
     *
     * <p>A dataset saved from a TransformApp working set has no model file, so it remains
     * excluded.
     */
    public List<String> modelBackedNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>(registry().datasets().stream()
                .filter(d -> d.isModelBacked())
                .map(DatasetRegistry.Dataset::name)
                .filter(n -> n != null && !n.isBlank())
                .toList());
        for (File model : modelFilesOnDisk()) {
            String name = modelName(model);
            if (!name.isBlank()) names.add(name);
        }
        return List.copyOf(names);
    }

    /** By name or by the folder key that name resolves to — a renamed entry answers to both. */
    public DatasetRegistry.Dataset find(String name) {
        if (name == null) return null;
        String key = key(name);
        for (DatasetRegistry.Dataset dataset : registry().datasets()) {
            if (name.equals(dataset.name()) || key.equals(dataset.key())) return dataset;
        }
        return null;
    }

    /** The model file the registry recorded, or the one the layout says it would be. */
    public File modelFileOf(String name) {
        DatasetRegistry.Dataset dataset = find(name);
        if (dataset != null && !dataset.modelPath().isBlank()) {
            return new File(dataset.modelPath());
        }
        // A draft has no registry entry. Locate it by the name stored in the model
        // rather than assuming the display name and folder key have never diverged.
        List<File> claiming = new ArrayList<>();
        for (File candidate : modelFilesOnDisk()) {
            if (name != null && name.equals(modelName(candidate))) claiming.add(candidate);
        }
        if (claiming.size() == 1) {
            return claiming.get(0);
        }
        // Several models claim one name — a stale copy left beside its successor is
        // the usual reason. Taking whichever sorted first would open one of them and
        // then SAVE over it, silently making the arbitrary choice authoritative. The
        // model living where the layout says it should is the one this name means;
        // failing that, name nothing that exists, so the caller reports a missing
        // file instead of editing the wrong domain.
        File conventional = modelFile(name);
        for (File candidate : claiming) {
            if (candidate.equals(conventional)) return candidate;
        }
        return conventional;
    }

    /** The model files claiming {@code name}, when more than one does — so a caller
     *  can say WHICH files collide rather than only that a name is unresolvable. */
    public List<File> modelFilesClaiming(String name) {
        List<File> claiming = new ArrayList<>();
        for (File candidate : modelFilesOnDisk()) {
            if (name != null && name.equals(modelName(candidate))) claiming.add(candidate);
        }
        return List.copyOf(claiming);
    }

    private List<File> modelFilesOnDisk() {
        File[] directories = root.listFiles(File::isDirectory);
        if (directories == null) return List.of();
        java.util.Arrays.sort(directories, java.util.Comparator.comparing(File::getName));
        List<File> models = new ArrayList<>();
        for (File directory : directories) {
            File[] files = directory.listFiles((dir, fileName) ->
                    fileName.endsWith(".model.json"));
            if (files == null) continue;
            java.util.Arrays.sort(files, java.util.Comparator.comparing(File::getName));
            for (File file : files) if (file.isFile()) models.add(file);
        }
        return models;
    }

    private static String modelName(File model) {
        if (model == null || !model.isFile()) return "";
        try {
            return JSON.readTree(model).path("name").asText("").trim();
        } catch (Exception unreadable) {
            // Do not offer a switch to a model that cannot even identify itself.
            return "";
        }
    }

    /** Something to open after a domain is deleted, or null when nothing is registered. */
    public String anyRegisteredNameOtherThan(String name) {
        for (DatasetRegistry.Dataset dataset : registry().datasets()) {
            String candidate = dataset.name();
            if (candidate != null && !candidate.isBlank() && !candidate.equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /** What happened, so the caller can say so rather than guess from an exception. */
    public sealed interface Rename {
        /** Files moved (or there were none) and the registry entry, if any, now points here. */
        record Done(String name) implements Rename { }
        /** A different domain already occupies the destination folder. */
        record FolderTaken(File existing) implements Rename { }
        record Failed(Exception cause) implements Rename { }
    }

    /**
     * Moves a domain's folder and re-keys the files inside it, then points its registry entry
     * at the new name, key and paths. Nothing is destructive beyond the move: a domain with no
     * folder yet renames fine, and one with no registry entry simply has nothing to re-key —
     * the next save registers it.
     */
    public Rename rename(String from, String to) {
        String oldKey = key(from);
        String newKey = key(to);
        File oldDirectory = new File(root, oldKey);
        File newDirectory = new File(root, newKey);
        if (!newKey.equals(oldKey) && newDirectory.exists()) {
            return new Rename.FolderTaken(newDirectory);
        }
        try {
            if (!newKey.equals(oldKey) && oldDirectory.isDirectory()) {
                Files.move(oldDirectory.toPath(), newDirectory.toPath());
                for (String extension : List.of(".model.json", ".ruletree.json", ".snapshot.json")) {
                    File was = new File(newDirectory, oldKey + extension);
                    if (was.isFile()) {
                        Files.move(was.toPath(), new File(newDirectory, newKey + extension).toPath());
                    }
                }
            }
            reKey(oldKey, to);
            return new Rename.Done(to);
        } catch (Exception failure) {
            return new Rename.Failed(failure);
        }
    }

    /** True when an entry was found and updated; false when there was none to update. */
    public boolean reKey(String oldKey, String newName) throws IOException {
        DatasetRegistry registry = registry();
        for (DatasetRegistry.Dataset dataset : registry.datasets()) {
            if (oldKey.equals(dataset.key())) {
                dataset.name(newName);
                dataset.key(key(newName));
                dataset.modelPath(modelFile(newName).getPath());
                dataset.ruletreePath(ruleTreeFile(newName).getPath());
                dataset.snapshotPath(snapshotFile(newName).getPath());
                registry.save(registryFile());
                return true;
            }
        }
        return false;
    }

    /** Removes the registry entry and the domain's folder. */
    public void delete(String name) throws IOException {
        String key = key(name);
        DatasetRegistry registry = registry();
        registry.datasets().removeIf(dataset -> key.equals(dataset.key()));
        registry.save(registryFile());
        deleteRecursively(directory(name));
    }

    private DatasetRegistry registry() {
        try {
            return DatasetRegistry.load(registryFile());
        } catch (RuntimeException unreadable) {
            // A missing or corrupt registry means "nothing is registered", never a failure to
            // list the domain the reader already has open.
            return new DatasetRegistry();
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        Files.deleteIfExists(file.toPath());
    }
}
