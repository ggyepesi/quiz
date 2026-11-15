package quiz.transform;

import objectview.Viewable;
import objectview.render.GroupTreeView;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FacetGroupTest {

    @Test void routeCoverageFacetsTheOriginalOwnersByExactConfiguredRoute() {
        wikidata.explore.extract.WikidataDynamicObject laocoon =
                routeOwner("Q101", "Laocoon", "direct", "Direct child", "Q201",
                        quiz.source.WikidataRelationRouteMatch.Status.RETAINED);
        wikidata.explore.extract.WikidataDynamicObject acoetes =
                routeOwner("Q102", "Acoetes", "qualified",
                        "Named child group", "Q202",
                        quiz.source.WikidataRelationRouteMatch.Status.NOT_RETAINED);

        FacetGroup group = new FacetGroup("Child route coverage", "Character",
                "wikidataRelationRouteMatches.route");
        group.reproduce(List.of(laocoon, acoetes));

        assertEquals(List.of("Direct child", "Named child group"),
                group.getChildren().stream().map(Viewable::getDisplayName).toList());
        assertEquals(List.of("Laocoon"), group.getChild("Direct child").getMembers()
                .stream().map(Viewable::getDisplayName).toList());
        assertEquals(List.of("Acoetes"), group.getChild("Named child group").getMembers()
                .stream().map(Viewable::getDisplayName).toList());

        FacetGroup statuses = new FacetGroup("Child route and status", "Character",
                "wikidataRelationRouteMatches.coverage");
        statuses.reproduce(List.of(laocoon, acoetes));
        assertEquals(List.of("Direct child — Retained",
                        "Named child group — Not retained"),
                statuses.getChildren().stream().map(Viewable::getDisplayName).toList(),
                "route and final status stay correlated on the exact match");
    }

    private static wikidata.explore.extract.WikidataDynamicObject routeOwner(
            String qid, String label, String routeId, String route, String terminal,
            quiz.source.WikidataRelationRouteMatch.Status status) {
        wikidata.explore.extract.WikidataDynamicObject owner =
                new wikidata.explore.extract.WikidataDynamicObject(qid, label);
        owner.type("Character");
        owner.addWikidataRelationRouteMatch(new quiz.source.WikidataRelationRouteMatch(
                "Character", "children", routeId, route,
                new quiz.source.WikidataSource(terminal),
                status));
        return owner;
    }

    private static DynamicViewable city(String name, String region) {
        DynamicViewable c = new DynamicViewable(name, name);
        c.type("City");
        c.put("region", region);
        return c;
    }

    @Test void partitionsByFieldAndRefreshesOnReproduce() {
        List<Viewable> members = new ArrayList<>(List.of(
                city("Paris", "Europe"),
                city("Berlin", "Europe"),
                city("Tokyo", "Asia"),
                city("Nowhere", null)));

        FacetGroup fg = new FacetGroup("By region", "City", "region");
        fg.reproduce(members);

        assertEquals("City", fg.memberType());
        assertEquals("region", fg.field());
        assertEquals(objectview.field.FieldPath.of("region"), fg.fieldPath());
        assertEquals(java.util.Optional.of(objectview.field.FieldPath.of("region")),
                FacetGroup.fieldPathOf(fg));
        assertEquals(3, fg.getMembers().size());
        assertEquals(List.of("Paris", "Berlin", "Tokyo"),
                fg.getMembers().stream().map(Viewable::getDisplayName).toList(),
                "the facet result contains only members assigned to a value bucket");
        assertNotNull(fg.getChild("Europe"));
        assertEquals(fg, fg.getChild("Europe").getParent());
        assertNotNull(fg.getChild("Asia"));
        assertEquals(2, fg.getChild("Europe").getMembers().size());
        assertNull(fg.getChild("region"),
                "the named facet group already represents the dimension");
        assertNull(fg.getChild("Africa"));

        // instance set changes online -> reproduce refreshes the buckets from the rule
        members.add(city("Cairo", "Africa"));
        fg.reproduce(members);
        assertEquals(4, fg.getMembers().size());
        assertNotNull(fg.getChild("Africa"));
        assertEquals(1, fg.getChild("Africa").getMembers().size());
    }

    @Test void aNamedNobelPrizeCategoryFacetDirectlyContainsItsCategoryGroups() {
        DynamicViewable physics = new DynamicViewable("1901 physics", "1901 physics");
        physics.type("NobelPrize");
        physics.put("category", "Physics");
        DynamicViewable medicine = new DynamicViewable("1901 medicine", "1901 medicine");
        medicine.type("NobelPrize");
        medicine.put("category", "Physiology or Medicine");

        FacetGroup group = new FacetGroup(
                "Prizes by category", "NobelPrize", "category");
        group.reproduce(List.of(physics, medicine));

        assertEquals(List.of("Physics", "Physiology or Medicine"),
                group.getChildren().stream().map(Viewable::getDisplayName).toList());
        assertNull(group.getChild("category"),
                "do not create the reported empty category (0) intermediate group");
    }

    @Test void rendersTheNamedFacetWithCategoryGroupsAtOneLevel() throws Exception {
        DynamicViewable physics = new DynamicViewable("1901 physics", "1901 physics");
        physics.type("NobelPrize");
        physics.put("category", "Physics");
        DynamicViewable medicine = new DynamicViewable("1901 medicine", "1901 medicine");
        medicine.type("NobelPrize");
        medicine.put("category", "Physiology or Medicine");
        EditableGroup root = new EditableGroup("Nobel prizes");
        FacetGroup facet = new FacetGroup(
                "Prizes by category", "NobelPrize", "category");
        facet.reproduce(List.of(physics, medicine));
        root.addGroup(facet);

        GroupTreeView tree = new GroupTreeView(root);
        SwingUtilities.invokeAndWait(() -> {
            tree.setSize(520, 300);
            layout(tree);
            tree.getTree().expandRow(1);
        });
        File artifact = new File("target/ui-artifacts/nobel-prize-category-facet.png");
        assertTrue(artifact.getParentFile().isDirectory() || artifact.getParentFile().mkdirs());
        BufferedImage image = new BufferedImage(520, 300, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        SwingUtilities.invokeAndWait(() -> tree.paint(graphics));
        graphics.dispose();
        ImageIO.write(image, "png", artifact);
        assertTrue(artifact.isFile());
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) layout(nested);
        }
    }
}
