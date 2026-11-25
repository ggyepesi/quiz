package domain.persistence;

import dataset.DomainStorage;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** The saved files a project session read, and the check-and-write boundary for its saves. */
public final class ProjectSaveGuard {
    private static final Map<Path, Semaphore> LOCAL_WRITERS = new ConcurrentHashMap<>();
    @FunctionalInterface
    public interface Write<T> { T run() throws Exception; }

    public static final class Conflict extends IllegalStateException {
        private Conflict(String message) { super(message); }
    }

    private final DomainStorage storage;
    private final String name;
    private final List<File> additionalFiles;
    /** What was read: size and modification time decide whether the content digest
     *  must be recomputed, so a check over an unchanged 250 MB snapshot reads no bytes. */
    private record Version(long size, FileTime modified, Object fileKey, String digest) { }

    /** How long a save waits for another writer's lock before refusing. */
    private static final long LOCK_WAIT_MILLIS = 5_000;

    private volatile Map<Path, Version> baseline = Map.of();

    private ProjectSaveGuard(DomainStorage storage, String name, List<File> additionalFiles) {
        this.storage = storage;
        this.name = name;
        this.additionalFiles = additionalFiles;
    }

    /** Capture before reading any model or snapshot data. Check again before installing it. */
    public static ProjectSaveGuard capture(
            DomainStorage storage, String name, File... additionalFiles) throws IOException {
        var guard = new ProjectSaveGuard(storage, name, List.of(additionalFiles));
        guard.baseline = guard.readVersions();
        return guard;
    }

    public static ProjectSaveGuard newProject(DomainStorage storage, String name) {
        return new ProjectSaveGuard(storage, name, List.of());
    }

    /** A save may only write the project key this session opened or created. */
    public void requireDestination(DomainStorage destination, String project) throws IOException {
        File expected = storage.modelFile(name).getCanonicalFile();
        File target = destination.modelFile(project).getCanonicalFile();
        if (!expected.equals(target)) {
            throw new Conflict("This session did not open the project at " + target
                    + ". No files were saved. Open the intended project before saving.");
        }
    }

    public void check() throws IOException {
        Map<Path, Version> expected = baseline;
        Map<Path, Version> current = readVersions();
        var changed = new LinkedHashSet<>(expected.keySet());
        changed.addAll(current.keySet());
        changed.removeIf(path -> java.util.Objects.equals(
                digestOf(expected.get(path)), digestOf(current.get(path))));
        if (!changed.isEmpty()) {
            throw new Conflict("The saved files for \"" + name + "\" changed since this copy "
                    + "was loaded (or the project was created elsewhere). No files were saved, "
                    + "and this session's changes are still open. Reopening the project loads "
                    + "the current files and discards this session's unsaved changes.\n"
                    + "Changed:\n"
                    + String.join("\n", changed.stream().map(Path::toString).toList()));
        }
    }

    /** Keep the check and every write under one lock. Refresh only on complete success. */
    public <T> T write(Write<T> action) throws Exception {
        return exclusive(storage, () -> {
            check();
            T result = action.run();
            baseline = readVersions();
            return result;
        });
    }

    /**
     * Saves in this store also update the shared dataset registry. Keep one lock for
     * that store, including when two processes save different projects. The lock file
     * must stay in place so a waiter and a new writer cannot lock different files.
     */
    public static <T> T exclusive(DomainStorage storage, Write<T> action) throws Exception {
        Path folder = storage.registryFile().getCanonicalFile().toPath().getParent();
        Files.createDirectories(folder);
        Path path = folder.resolve(".project-save.lock");
        // Avoid opening a second channel to a lock held in this JVM. On some
        // systems closing that channel can release the first channel's lock.
        Semaphore local = LOCAL_WRITERS.computeIfAbsent(path, ignored -> new Semaphore(1));
        long deadline = System.currentTimeMillis() + LOCK_WAIT_MILLIS;
        // Another save usually finishes within a second; wait briefly before refusing.
        if (!local.tryAcquire(LOCK_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            throw busy(folder);
        }
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            java.nio.channels.FileLock lock = null;
            while (true) {
                try {
                    lock = channel.tryLock();
                } catch (OverlappingFileLockException busy) {
                    lock = null;
                }
                if (lock != null || System.currentTimeMillis() >= deadline) break;
                Thread.sleep(100);
            }
            if (lock == null) throw busy(folder);
            try (var held = lock) {
                return action.run();
            }
        } finally {
            local.release();
        }
    }

    private static Conflict busy(Path folder) {
        return new Conflict("Another save has been writing to " + folder + " for "
                + LOCK_WAIT_MILLIS / 1000 + " seconds. No files were saved. "
                + "Try again when it finishes.");
    }

    private Map<Path, Version> readVersions() throws IOException {
        List<File> files = new ArrayList<>(storage.savedProjectFiles(name));
        files.addAll(additionalFiles);
        Map<Path, Version> known = baseline;
        Map<Path, Version> versions = new LinkedHashMap<>();
        for (File file : files) {
            Path path = file.getCanonicalFile().toPath();
            if (versions.containsKey(path)) continue;
            try {
                var attributes = Files.readAttributes(path,
                        java.nio.file.attribute.BasicFileAttributes.class);
                long size = attributes.size();
                FileTime modified = attributes.lastModifiedTime();
                Object fileKey = attributes.fileKey();
                Version previous = known.get(path);
                versions.put(path, previous != null && fileKey != null && previous.size() == size
                        && previous.modified().equals(modified)
                        && java.util.Objects.equals(previous.fileKey(), fileKey)
                        ? previous : new Version(size, modified, fileKey, digest(path)));
            } catch (NoSuchFileException missing) {
                // Absence matters too: a new file is a change to the baseline.
            }
        }
        return versions;
    }

    private static String digestOf(Version version) {
        return version == null ? null : version.digest();
    }

    private static String digest(Path path) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (var in = new DigestInputStream(Files.newInputStream(path), sha)) {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return java.util.HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
