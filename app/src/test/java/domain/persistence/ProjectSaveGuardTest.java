package domain.persistence;

import dataset.DomainStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProjectSaveGuardTest {
    @TempDir Path root;

    @Test @Timeout(25)
    void aSaveInAnotherJvmKeepsTheRegistryLockedUntilItsWriteFinishes() throws Exception {
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                getClass().getName(), root.toString()).redirectErrorStream(true).start();
        try (var output = new BufferedReader(new InputStreamReader(child.getInputStream()));
             var input = child.getOutputStream()) {
            assertEquals("LOCKED", output.readLine());
            var storage = DomainStorage.in(root.toFile());
            var other = ProjectSaveGuard.newProject(storage, "Other project");
            var marker = root.resolve("other-write");
            var busy = assertThrows(ProjectSaveGuard.Conflict.class,
                    () -> other.write(() -> Files.createFile(marker)));
            assertTrue(busy.getMessage().contains("Another save"));
            assertFalse(Files.exists(marker));
            input.write(1);
            input.flush();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, child.exitValue());
            other.write(() -> Files.createFile(marker));
            assertTrue(Files.exists(marker));
        } finally {
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    /** Size and modification time only decide whether to re-read a file; the content
     *  decides. A touched file with the same bytes is no conflict, while a same-size
     *  rewrite is one. */
    @Test void contentNotTimestampsDecidesWhetherAFileChanged() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = storage.modelFile("History").toPath();
        Files.createDirectories(model.getParent());
        Files.writeString(model, "AAAA");
        var guard = ProjectSaveGuard.capture(storage, "History");

        Files.setLastModifiedTime(model, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(model).toMillis() + 5_000));
        guard.check();

        Files.writeString(model, "BBBB");
        Files.setLastModifiedTime(model, java.nio.file.attribute.FileTime.fromMillis(
                Files.getLastModifiedTime(model).toMillis() + 10_000));
        var conflict = assertThrows(ProjectSaveGuard.Conflict.class, guard::check);
        assertTrue(conflict.getMessage().contains("discards this session's unsaved changes"));
    }

    @Test void aNewProjectCannotOverwriteAFolderCreatedByAnotherSession() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var draft = ProjectSaveGuard.newProject(storage, "History");
        var model = storage.modelFile("History").toPath();
        Files.createDirectories(model.getParent());
        Files.writeString(model, "the other session");

        assertThrows(ProjectSaveGuard.Conflict.class,
                () -> draft.write(() -> Files.writeString(model, "the new draft")));
        assertEquals("the other session", Files.readString(model));
    }

    @Test void aStoppedBuildHandsBackOnlyItsOwnWrites() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = storage.modelFile("History").toPath();
        Files.createDirectories(model.getParent());
        Files.writeString(model, "original model");
        var session = ProjectSaveGuard.capture(storage, "History");
        var build = session.copy();
        var graph = model.resolveSibling("positiongraph.graph.snapshot.json");
        build.write(() -> Files.writeString(graph, "pending decisions"));
        assertThrows(ProjectSaveGuard.Conflict.class, session::check);

        session.acceptWritesFrom(build);
        session.check();
        Files.writeString(graph, "other editor's decisions");
        assertThrows(ProjectSaveGuard.Conflict.class,
                () -> session.write(() -> Files.writeString(graph, "my decisions")));
        assertEquals("other editor's decisions", Files.readString(graph));
    }

    @Test void aReplacementWithTheSameSizeAndTimestampIsStillChecked() throws Exception {
        var storage = DomainStorage.in(root.toFile());
        var model = storage.modelFile("History").toPath();
        Files.createDirectories(model.getParent());
        Files.writeString(model, "AAAA");
        var guard = ProjectSaveGuard.capture(storage, "History");
        var replacement = Files.writeString(model.resolveSibling("replacement"), "BBBB");
        Files.setLastModifiedTime(replacement, Files.getLastModifiedTime(model));
        Files.move(replacement, model, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        assertThrows(ProjectSaveGuard.Conflict.class, guard::check);
    }

    /** A real second process holds the same production lock until the test releases it. */
    public static void main(String[] args) throws Exception {
        var storage = DomainStorage.in(Path.of(args[0]).toFile());
        ProjectSaveGuard.exclusive(storage, () -> {
            System.out.println("LOCKED");
            System.out.flush();
            return System.in.read();
        });
    }
}
