package wikidata.explore.transform;

import quiz.Quizable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Debug helper: dump the identifiers of every mapped {@link Quizable} of a given
 * type to a file, sorted, one {@code qid\tname} per line — duplicates preserved,
 * so {@code sort | uniq -c} reveals same-qid dupes and {@code comm} reveals the
 * set difference between two runs (e.g. fresh generation vs. loaded snapshot).
 * Type is matched by the generated class's simple name (e.g. OscarNominations).
 */
public final class QuizableTypeDump {

    private QuizableTypeDump() {}

    public static void dump(Collection<? extends Quizable> instances,
                            String type, File out) throws IOException {
        List<String> lines = new ArrayList<>();
        for (Quizable q : instances) {
            if (q != null && q.getClass().getSimpleName().equals(type)) {
                lines.add(q.getIdentifier() + "\t" + q.getDisplayName());
            }
        }
        lines.sort(String::compareTo);
        Files.write(out.toPath(), lines);
        System.out.println("[dump] " + lines.size() + " " + type
                + " -> " + out.getAbsolutePath());
    }
}
