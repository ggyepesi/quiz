package domain.transform.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Applications contribute actions/member views; ObjectView owns the group tree. */
class GroupRenderingOwnershipGuardTest {

    @Test void applicationsNeverConstructASecondGroupTree() throws Exception {
        Path sources = Path.of("src/main/java");
        List<String> constructors = new ArrayList<>();
        try (var files = Files.walk(sources)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String text = Files.readString(file);
                if (text.contains("new GroupTreeView(")) {
                    constructors.add(sources.relativize(file).toString());
                }
            }
        }

        assertEquals(List.of(), constructors,
                "applications render ViewableGroups through GroupMembersView; only "
                        + "ObjectView constructs the shared group tree");
    }
}
