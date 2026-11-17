package wikidata.explore.workbench;

import org.junit.jupiter.api.Test;
import wikidata.explore.model.EntityBound;
import wikidata.explore.model.GeneratedClassModel;
import objectview.view.SearchableView;
import wikidata.explore.query.result.MembershipTypeCandidate;
import wikidata.explore.query.result.MembershipTypePreview;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubclassDiscoveryPanelTest {

    @Test void inspectionIsSeparateFromExplicitlyAddingAMembershipTarget()
            throws Exception {
        GeneratedClassModel position = new GeneratedClassModel("Position");
        position.membership(EntityBound.relation("P31", List.of("Q4164871"), true));
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<String> added = new ArrayList<>();
        onEdt(() -> {
            panel.onAddMembershipTarget(added::add);
            panel.showClass(position);
        });

        panel.acceptSubtypes(List.of(candidate("Q189290", "historical position")));
        flushEdt();

        assertEquals(2, panel.resultCount(),
                "the broad type and its discovered subtype use the same candidate view");
        assertTrue(panel.statusText().contains("1 direct subtype(s) found"));
        assertTrue(added.isEmpty(), "finding subclasses must not edit the model");
        assertInstanceOf(SearchableView.class, panel.candidateView(),
                "membership candidates use ObjectView rather than a bespoke table");

        onEdt(() -> {
            panel.selectRows(1);
            panel.addSelectedRows();
        });
        assertEquals(List.of("Q189290"), added);

        flushEdt();
        render(panel, "target/ui-artifacts/subclass-explorer.png");
    }

    @Test void aClassWithNoMembershipRelationSaysWhyNothingCanBeAdded()
            throws Exception {
        // Add writes into a membership RELATION. Before a class has one the action
        // could only have answered a click with silence, which directive 10 forbids:
        // a command produces a visible result or explains why it had none.
        GeneratedClassModel unbounded = new GeneratedClassModel("Position");
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<String> added = new ArrayList<>();
        onEdt(() -> {
            panel.onAddMembershipTarget(added::add);
            panel.showClass(unbounded);
        });
        assertTrue(panel.statusText().contains("has no membership relation yet"),
                "selecting the class already says what it is missing: "
                        + panel.statusText());

        panel.acceptSubtypes(List.of(candidate("Q294414", "public office")));
        flushEdt();
        onEdt(() -> panel.selectRows(0));

        assertFalse(panel.addEnabled(),
                "a class with no membership relation has nothing to add a target to");
        assertTrue(panel.addRefusal().contains("no membership relation yet"),
                "the disabled action says what is missing: " + panel.addRefusal());
        assertTrue(added.isEmpty());
    }

    @Test void examplesProduceTheSameCandidatesAndOnlyUseMutatesConfiguration()
            throws Exception {
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<String> used = new ArrayList<>();
        onEdt(() -> {
            panel.showClass(episode);
            panel.onUseMembershipType(value ->
                    {
                        used.add(value.qid() + "|" + value.label());
                        return "";
                    });
            panel.setExamplesForTest(List.of(
                    new workbench.WorkbenchSelections.Entity(
                            "Q3088950", "Cleaning the Augean stables"),
                    new workbench.WorkbenchSelections.Entity(
                            "Q2937680", "Capture of Cerberus")));
        });
        assertEquals(2, panel.exampleCount());

        panel.acceptCommonTypes(List.of(new MembershipTypeCandidate(
                "Q63143903", "episode in Greek mythology",
                MembershipTypeCandidate.Origin.COMMON_TYPE, "",
                2, 2, 2, -1, List.of())));
        flushEdt();

        assertTrue(used.isEmpty(), "inspection and selecting examples change no model");
        onEdt(() -> {
            panel.selectRows(0);
            panel.useSelectedRow();
        });
        assertEquals(List.of("Q63143903|episode in Greek mythology"), used);
    }

    @Test void explicitAndSelectionMembershipCannotBeReplacedByUse() throws Exception {
        for (GeneratedClassModel configured : List.of(
                withMembership(EntityBound.explicit(List.of("Q3088950"))),
                withMembership(EntityBound.vocabulary("CuratedEpisodes")))) {
            SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
            onEdt(() -> panel.showClass(configured));
            panel.acceptCommonTypes(List.of(new MembershipTypeCandidate(
                    "Q63143903", "episode in Greek mythology",
                    MembershipTypeCandidate.Origin.COMMON_TYPE, "",
                    1, 1, 1, -1, List.of())));
            flushEdt();
            onEdt(() -> panel.selectRows(0));

            assertFalse(panel.useEnabled());
            assertTrue(panel.useRefusal().contains(configured.membership().kind()
                    == EntityBound.Kind.EXPLICIT ? "explicit seed" : "named selection"));
        }
    }

    @Test void aTypePreviewCannotReplaceAnotherKindOfRelation() throws Exception {
        GeneratedClassModel configured = withMembership(
                EntityBound.relation("P279", List.of("Q24336466"), false));
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        onEdt(() -> panel.showClass(configured));
        panel.acceptCommonTypes(List.of(candidate("Q63143903",
                "episode in Greek mythology")));
        flushEdt();
        onEdt(() -> panel.selectRows(0));

        assertFalse(panel.useEnabled());
        assertTrue(panel.useRefusal().contains("plain P31"));
        assertFalse(panel.addEnabled());
        assertTrue(panel.addRefusal().contains("plain P31"));
    }

    @Test void previewSubclassClosureIsTheReadOnlyClassSetting() throws Exception {
        GeneratedClassModel direct = withMembership(
                EntityBound.relation("P31", List.of("Q63143903"), false));
        GeneratedClassModel recursive = withMembership(
                EntityBound.relation("P31", List.of("Q63143903"), true));
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();

        onEdt(() -> panel.showClass(direct));
        assertFalse(panel.previewIncludesSubclasses());
        onEdt(() -> panel.showClass(recursive));
        assertTrue(panel.previewIncludesSubclasses());
        assertFalse(panel.previewSubclassSettingEditable());
    }

    @Test void moreThanTwentyExamplesAreRefusedRatherThanSilentlyDropped()
            throws Exception {
        SubclassDiscoveryPanel panel = new SubclassDiscoveryPanel();
        List<workbench.WorkbenchSelections.Entity> examples = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            examples.add(new workbench.WorkbenchSelections.Entity("Q" + i, "Example " + i));
        }
        onEdt(() -> panel.setExamplesForTest(examples));

        assertTrue(panel.statusText().contains("Choose at most 20"));
        assertFalse(panel.findCommonTypesEnabled());
    }

    @Test void membershipPreviewUsesObjectViewAndStatesItsBound() {
        MembershipTypePreview result = new MembershipTypePreview(
                "Q63143903", true, 1000, true,
                List.of(new MembershipTypeCandidate.Member(
                        "Q3088950", "Cleaning the Augean stables")));

        JComponent view = SubclassDiscoveryPanel.previewView(result);

        assertTrue(contains(view, SearchableView.class));
        assertTrue(SubclassDiscoveryPanel.previewSummary(result).contains("1000+"));
        assertTrue(SubclassDiscoveryPanel.previewSummary(result)
                .contains("including subclasses"));
    }

    private static MembershipTypeCandidate candidate(String qid, String label) {
        return new MembershipTypeCandidate(qid, label,
                MembershipTypeCandidate.Origin.DIRECT_SUBTYPE, "Q4164871",
                0, 0, 0, 1, List.of());
    }

    private static GeneratedClassModel withMembership(EntityBound membership) {
        GeneratedClassModel episode = new GeneratedClassModel("Episode");
        episode.membership(membership);
        return episode;
    }

    private static boolean contains(Container root, Class<?> type) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) return true;
            if (child instanceof Container nested && contains(nested, type)) return true;
        }
        return false;
    }

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private static void render(Component component, String path) throws IOException {
        component.setSize(940, 500);
        layout(component);
        File artifact = new File(path);
        assertTrue(artifact.getParentFile().mkdirs() || artifact.getParentFile().isDirectory());
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
    }

    private static void layout(Component component) {
        if (component instanceof Container container) {
            container.doLayout();
            for (Component child : container.getComponents()) layout(child);
        }
    }
}
