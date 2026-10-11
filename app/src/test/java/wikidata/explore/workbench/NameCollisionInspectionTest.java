package wikidata.explore.workbench;

import objectview.Viewable;
import objectview.ViewableAdapter;
import org.junit.jupiter.api.Test;
import wikidata.explore.extract.WikidataDynamicObject;
import wikidata.explore.generation.NameCollisions;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test void collisionGroupContainsTheActualInstancesRatherThanCaptionShells() {
        StubViewable first = new StubViewable(
                "Q97183299", "Constellation", "Gladii Electorales Saxonici");
        StubViewable second = new StubViewable(
                "Q4292387", "Constellation", "Gladii Electorales Saxonici");

        NameCollision collision = new NameCollision(
                "Constellation", "Gladii Electorales Saxonici", List.of(first, second));

        assertEquals("Gladii Electorales Saxonici — Constellation",
                collision.getDisplayName());
        assertEquals(List.of(first, second), List.copyOf(collision.getMembers()));
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
