package domain.workbench.ui;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Fetch action presentation may be shared; fetching and review must not be copied into it. */
class FieldAcquisitionUiOwnershipTest {

    @Test void fieldActionsShareOneProcessRunnerAndReviewPath() throws Exception {
        String validation = Files.readString(Path.of(
                "src/main/java/domain/transform/ui/ValidationPanel.java"));
        assertEquals(1, occurrences(validation, "findDataRunner.run("));
        assertEquals(1, occurrences(validation, "runReview(new FindDataBatchProcess("));
        assertEquals(1, occurrences(validation, "new LoadFieldValuesProcess("));
        assertEquals(1, occurrences(validation,
                "FindDataBatchReviewPanel.showReview("));
    }

    @Test void theSharedActionListOnlyPresentsSuppliedActions() throws Exception {
        String list = Files.readString(Path.of(
                "src/main/java/domain/workbench/ui/WorkbenchActionList.java"));
        assertFalse(list.contains("domain.enrichment"));
        assertFalse(list.contains("FindData"));
        assertFalse(list.contains("ProcessRunner"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0;
             at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }
}
