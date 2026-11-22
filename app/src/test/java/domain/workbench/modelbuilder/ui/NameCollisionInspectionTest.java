package domain.workbench.modelbuilder.ui;

import objectview.Viewable;
import objectview.ViewableAdapter;
import objectview.render.RenderContext;
import objectview.render.RenderingMode;
import org.junit.jupiter.api.Test;
import domain.runtime.WikidataDynamicObject;
import domain.build.NameCollisions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class NameCollisionInspectionTest {

    @Test void participantResolutionUsesTypeAndIdentifierTogether() {
        WikidataDynamicObject constellation = dynamic(
                "Q1", "Constellation", "Gladii Electorales Saxonici");
        WikidataDynamicObject discovery = dynamic(
                "Q1", "Discovery@Constellation.discovery",
                "Gladii Electorales Saxonici");
        StubViewable typedConstellation = new StubViewable(
                "Q1", "Constellation", "Gladii Electorales Saxonici");

        Map<NameCollisions.InstanceIdentity, Viewable> participants =
                ModelBuilderFrame.collisionParticipants(
                        List.of(constellation, discovery), List.of(typedConstellation));

        assertSame(typedConstellation, participants.get(
                new NameCollisions.InstanceIdentity("Constellation", "Q1")),
                "the materialized instance replaces only its exact dynamic carrier");
        assertSame(discovery, participants.get(
                new NameCollisions.InstanceIdentity(
                        "Discovery@Constellation.discovery", "Q1")),
                "a same-QID instance of another logical type must not be substituted");
    }

    @Test void collisionRowContainsTheActualInstancesRatherThanCaptionShells() {
        StubViewable first = new StubViewable(
                "Q97183299", "Constellation", "Gladii Electorales Saxonici");
        StubViewable second = new StubViewable(
                "Q4292387", "Constellation", "Gladii Electorales Saxonici");

        NameCollision collision = new NameCollision(
                "Constellation", "Gladii Electorales Saxonici", List.of(first, second));

        assertEquals("Gladii Electorales Saxonici", collision.getDisplayName());
        assertEquals("Constellation", collision.className());
        assertEquals(2, collision.instances());
        assertEquals(List.of(first, second), collision.entities());
    }

    @Test void collisionNamesAreAlphabeticalSearchableRowsAndSelectionChangesMembers()
            throws Exception {
        StubViewable lycusMember = new StubViewable("Q2", "Character", "Lycus");
        StubViewable agenorMember = new StubViewable("Q1", "Character", "Agenor");
        NameCollision lycus = new NameCollision(
                "Character", "Lycus", List.of(lycusMember));
        NameCollision agenor = new NameCollision(
                "Character", "Agenor", List.of(agenorMember));
        AtomicReference<NameCollisionInspectionPanel> panel = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> panel.set(new NameCollisionInspectionPanel(
                List.of(lycus, agenor), new RenderContext())));
        try {
            assertEquals(List.of("Agenor", "Lycus"), panel.get().collisions().stream()
                    .map(NameCollision::name).toList());
            assertEquals(RenderingMode.TABLE, panel.get().collisionView().mode());
            assertNotNull(panel.get().collisionView().search(),
                    "collision names themselves must have ObjectView search controls");
            assertEquals(agenor, panel.get().selectedCollision());
            assertEquals(List.of(agenorMember), panel.get().membersView().cardList()
                    .getVirtualList().items());

            SwingUtilities.invokeAndWait(() -> panel.get().selectForInspection(lycus));
            assertEquals(lycus, panel.get().selectedCollision());
            assertEquals(List.of(lycusMember), panel.get().membersView().cardList()
                    .getVirtualList().items());
        } finally {
            SwingUtilities.invokeAndWait(panel.get()::close);
        }
    }

    private static WikidataDynamicObject dynamic(
            String id, String typeKey, String label) {
        WikidataDynamicObject value = new WikidataDynamicObject(id, label);
        String type = typeKey.contains("@")
                ? typeKey.substring(0, typeKey.indexOf('@')) : typeKey;
        value.type(type);
        value.typeKey(typeKey);
        return value;
    }

    private static final class StubViewable extends ViewableAdapter {
        private final String id;
        private final String type;
        private final String label;

        private StubViewable(String id, String type, String label) {
            this.id = id;
            this.type = type;
            this.label = label;
        }

        @Override public String getIdentifier() { return id; }
        @Override public String getDisplayName() { return label; }
        @Override public String typeName() { return type; }
        @Override public String identityTypeName() { return type; }
    }
}
