package quiz.web;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Reference;
import objectview.viewconfig.ViewConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The web renders a field that inherits an ancestor's config as the desktop card does
 * (#368). Reported: History's position → replaces, a list of Position under a Position,
 * opened to nothing, because its members followed their own empty ticks. On the web a
 * member is a chip fetched under the config at its path; that config is now the
 * inherited one, so the opened member shows the ancestor's fields, and its own inherited
 * list starts folded again — one level per open.
 */
class WebFoldsInheritedLevelsTest {

    static final class Office extends ViewableAdapter {
        @DisplayField private final String name;
        private final String country;
        @Reference Office predecessor;
        @Reference final List<Office> replaces = new ArrayList<>();

        Office(String name, String country) {
            this.name = name;
            this.country = country;
        }
        @Override public String getIdentifier() { return name; }
        @Override public String getDisplayName() { return name; }
        /** A pooled entity of a served type, so its chip fetches it by id. */
        @Override public String typeName() { return "Position"; }
    }

    private final Office president = new Office("President", "Germany");
    private final Office emperor = new Office("Emperor", "German Empire");
    private final Office king = new Office("King of Prussia", "Prussia");

    WebFoldsInheritedLevelsTest() {
        president.replaces.add(emperor);
        emperor.replaces.add(king);
    }

    private static ViewConfig config() {
        ViewConfig config = ViewConfig.leaf();
        config.addField("name", ViewConfig.leaf());
        config.addField("country", ViewConfig.leaf());
        config.addField("replaces", ViewConfig.leaf());
        return config;
    }

    @Test void anOpenedInheritedMemberShowsTheAncestorsFieldsOneLevelAtATime() {
        ViewableView card = ViewableJson.of(president, config(),
                (key, initiallyOpen) -> key instanceof java.util.Collection<?> || initiallyOpen);

        ViewableView.Field replaces = field(card, "replaces");
        assertEquals(1, replaces.size());
        ViewableView.Ref member = replaces.refs().get(0);
        assertEquals("Emperor", member.name(), "the member's caption, ticked above");
        assertNotNull(member.via(), "fetched under the config at its path");

        ViewableView opened = ViewableJson.member(emperor, president, config(),
                member.via().path());
        assertEquals("German Empire", field(opened, "country").value(),
                "the opened member renders by the inherited config");
        assertEquals(Boolean.FALSE, field(opened, "replaces").open(),
                "and its own inherited list starts folded again");
    }

    private static ViewableView.Field field(ViewableView card, String name) {
        return card.fields().stream().filter(f -> f.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError(name + " in " + card));
    }
}
