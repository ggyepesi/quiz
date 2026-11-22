package domain.legacy;

import flag.SportTeams;
import flag.States;
import language.Languages;
import mythology.MythologyEntities;
import nobel.NobelPrizes;
import objectview.viewconfig.DomainViews;
import oscar.OscarNominations;
import presidents.USPresidents;

import java.util.List;

/**
 * Hand-written domains that can be opened as conversion inputs.
 *
 * <p>They are not quiz products until TransformApp saves a configured snapshot. Keeping
 * this inventory with the domain boundary prevents either QuizFactory or TransformApp from
 * owning the same fact on behalf of the other.
 */
public final class LegacyDomains {
    public record Entry(String icon, String name, DomainViews views) { }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("🐉", "Mythology", new MythologyEntities()),
            new Entry("🏳️", "States", new States()),
            new Entry("⚽", "Sport Teams", new SportTeams()),
            new Entry("🏅", "Nobel Prizes", new NobelPrizes()),
            new Entry("🇺🇸", "US Presidents", new USPresidents()),
            new Entry("🗣️", "Languages", new Languages()),
            new Entry("🎬", "Oscars", new OscarNominations()));

    private LegacyDomains() { }

    public static List<Entry> entries() { return ENTRIES; }
}
