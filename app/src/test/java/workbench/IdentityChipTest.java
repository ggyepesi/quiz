package workbench;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class IdentityChipTest {

    @Test
    void qidUsesALightweightLabelInsteadOfAnHtmlDocument() {
        JLabel chip = assertInstanceOf(JLabel.class, IdentityChip.of("Q42"));
        assertEquals("Q42", chip.getText());
    }

    @Test
    void missingIdentityIsMarkedWithoutDatasourceKnowledgeInObjectView() {
        JLabel chip = assertInstanceOf(JLabel.class, IdentityChip.of(null));
        assertEquals("unidentified", chip.getText());
    }
}
