package batch;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/** One atomically replaced JSON checkpoint file per run key. */
public final class JsonFileBatchCheckpointStore implements BatchCheckpointStore {
    private final Path directory;
    private final ObjectMapper mapper;

    public JsonFileBatchCheckpointStore(Path directory) {
        this(directory, new ObjectMapper());
    }

    public JsonFileBatchCheckpointStore(Path directory, ObjectMapper mapper) {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        if (mapper == null) {
            throw new IllegalArgumentException("mapper must not be null");
        }
        this.directory = directory.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override
    public synchronized Optional<BatchCheckpoint> load(String runKey) throws IOException {
        Path file = fileFor(runKey);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        BatchCheckpoint checkpoint = mapper.readValue(file.toFile(), BatchCheckpoint.class);
        if (!runKey.equals(checkpoint.runKey())) {
            throw new IOException("Checkpoint run key does not match requested run: " + runKey);
        }
        return Optional.of(checkpoint);
    }

    @Override
    public synchronized void save(BatchCheckpoint checkpoint) throws IOException {
        if (checkpoint == null) {
            throw new IllegalArgumentException("checkpoint must not be null");
        }
        Files.createDirectories(directory);
        Path target = fileFor(checkpoint.runKey());
        Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), checkpoint);
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @Override
    public synchronized void delete(String runKey) throws IOException {
        Files.deleteIfExists(fileFor(runKey));
    }

    private Path fileFor(String runKey) {
        if (runKey == null || runKey.isBlank()) {
            throw new IllegalArgumentException("runKey must not be blank");
        }
        return directory.resolve(sha256(runKey) + ".batch.json");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
