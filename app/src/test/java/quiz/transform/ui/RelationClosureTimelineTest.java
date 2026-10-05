package quiz.transform.ui;

import aux.FlexibleDate;
import datasource.schema.FieldType;
import objectview.Viewable;
import org.junit.jupiter.api.Test;
import quiz.transform.DynamicViewable;
import quiz.transform.RelationClosureGroup;
import wikidata.explore.model.FieldCardinality;
import wikidata.explore.model.GeneratedClassModel;
import wikidata.explore.model.GeneratedFieldModel;
import wikidata.explore.model.GeneratedProjectModel;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelationClosureTimelineTest {
    @Test void configuredQualifierRolesChooseDatesWithoutDependingOnFieldNames() {
        GeneratedProjectModel project = new GeneratedProjectModel();
        GeneratedClassModel holding = new GeneratedClassModel("OfficeHolding");
        GeneratedFieldModel began = holding.addField(
                "began", FieldType.DATE, FieldCardinality.SINGLE);
        began.mapping().qualifierPid("P580");
        GeneratedFieldModel finished = holding.addField(
                "finished", FieldType.DATE, FieldCardinality.SINGLE);
        finished.mapping().qualifierPid("P582");
        project.rootClass(holding);

        RelationClosureTimeline.DateFields fields =
                RelationClosureTimeline.configuredFields(project, "OfficeHolding");

        assertEquals("began", fields.startField());
        assertEquals("finished", fields.endField());
    }

    @Test void aSharedPositionHasTheHoldingsOnBothSidesOfThePath() {
        DynamicViewable apostolic = value("P1", "Apostolic King", "Position");
        DynamicViewable emperor = value("P2", "Carolingian Emperor", "Position");
        DynamicViewable louis = value("H1", "Louis", "Person");
        DynamicViewable charles = value("H2", "Charles", "Person");
        DynamicViewable first = holding("O1", 800, 814);
        DynamicViewable sharedByLouis = holding("O2", 813, 840);
        DynamicViewable sharedByCharles = holding("O3", null, null);
        RelationClosureGroup.PathNode p1 = node(
                RelationClosureGroup.PathRole.ENTITY, apostolic);
        RelationClosureGroup.PathNode h1 = node(
                RelationClosureGroup.PathRole.MEMBER, louis);
        RelationClosureGroup.PathNode p2 = node(
                RelationClosureGroup.PathRole.ENTITY, emperor);
        RelationClosureGroup.PathNode h2 = node(
                RelationClosureGroup.PathRole.MEMBER, charles);
        RelationClosureGroup.RelationPath path = new RelationClosureGroup.RelationPath(
                List.of(p1, h1, p2, h2), List.of(
                new RelationClosureGroup.PathEdge(p1, h1, first),
                new RelationClosureGroup.PathEdge(h1, p2, sharedByLouis),
                new RelationClosureGroup.PathEdge(p2, h2, sharedByCharles)));

        RelationClosureTimeline.Model timeline = RelationClosureTimeline.of(path,
                new RelationClosureTimeline.DateFields("start", "end"));

        assertEquals(List.of("P1", "P2"), timeline.lanes().stream()
                .map(lane -> lane.position().getIdentifier()).toList());
        assertEquals(List.of(1, 2), timeline.lanes().stream()
                .map(lane -> lane.holdings().size()).toList());
        assertEquals(List.of("H1", "H2"), timeline.lanes().get(1).holdings().stream()
                .map(item -> item.member().getIdentifier()).toList(),
                "the intermediate Position is visibly shared by both holders");
        assertEquals(800.0, timeline.minimum());
        assertEquals(840.0, timeline.maximum());
        assertEquals(List.of("O3"), timeline.unplaced().stream()
                .map(item -> item.relation().getIdentifier()).toList());
    }

    @Test void missingEndpointsStayOpenAndTheTimelinePaintsWithoutInventingDates() {
        DynamicViewable position = value("P1", "Position", "Position");
        DynamicViewable holder = value("H1", "Holder", "Person");
        DynamicViewable relation = value("O1", "Holding", "OfficeHolding");
        relation.put("end", new FlexibleDate(900));
        RelationClosureGroup.PathNode p = node(
                RelationClosureGroup.PathRole.ENTITY, position);
        RelationClosureGroup.PathNode h = node(
                RelationClosureGroup.PathRole.MEMBER, holder);
        RelationClosureTimeline.Model model = RelationClosureTimeline.of(
                new RelationClosureGroup.RelationPath(List.of(p, h),
                        List.of(new RelationClosureGroup.PathEdge(p, h, relation))),
                new RelationClosureTimeline.DateFields("start", "end"));
        RelationClosureTimeline.Holding interval = model.holdings().getFirst();

        assertNull(interval.start());
        assertEquals(new FlexibleDate(900), interval.end());
        assertEquals("? → 900", interval.dateLabel());
        assertTrue(interval.placed());
        RelationClosureTimelinePanel view =
                new RelationClosureTimelinePanel(model, ignored -> { });
        view.setSize(view.getPreferredSize());
        view.paint(new BufferedImage(view.getWidth(), view.getHeight(),
                BufferedImage.TYPE_INT_ARGB).getGraphics());
    }

    private static RelationClosureGroup.PathNode node(
            RelationClosureGroup.PathRole role, Viewable value) {
        return new RelationClosureGroup.PathNode(role, value);
    }

    private static DynamicViewable holding(String id, Integer start, Integer end) {
        DynamicViewable value = value(id, id, "OfficeHolding");
        if (start != null) value.put("start", new FlexibleDate(start));
        if (end != null) value.put("end", new FlexibleDate(end));
        return value;
    }

    private static DynamicViewable value(String id, String label, String type) {
        DynamicViewable value = new DynamicViewable(id, label);
        value.type(type);
        return value;
    }
}
