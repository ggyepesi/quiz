package presidents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The scraped name is stored as the table gives it.
 *
 * <p>It used to be inverted to "Adams John" so the picker sorted by surname, but this one
 * string is also the instance's identifier, its display name and the term identity
 * resolution searches Wikidata with — where a surname-first string finds nothing at all.
 * Sort order is a name model's job; it must not be paid for by corrupting the value.</p>
 */
class USPresidentsNameTest {

    @Test void theNameIsStoredAsTheTableGivesIt() {
        assertEquals("John Adams", nameOf("John Adams (1735–1826)"));
        assertEquals("John Quincy Adams", nameOf("John Quincy Adams (1767–1848)"));
        assertEquals("George H. W. Bush", nameOf("George H. W. Bush (1924–2018)"));
    }

    /** A living president has no death year, and a particle is part of the family name —
     *  "Van Buren" is exactly what a last-token rule got wrong. */
    @Test void livingPresidentsAndParticlesSurviveIntact() {
        assertEquals("Joe Biden", nameOf("Joe Biden (b. 1942)"));
        assertEquals("Martin Van Buren", nameOf("Martin Van Buren (1782–1862)"));
    }

    /** The identifier is the same canonical string, since identity links are keyed by it. */
    @Test void theIdentifierIsTheCanonicalNameToo() {
        Person person = new USPresidents().parseNameAndBirthDeathDates("Abraham Lincoln (1809–1865)");

        assertEquals("Abraham Lincoln", person.getIdentifier());
    }

    private static String nameOf(String cell) {
        return new USPresidents().parseNameAndBirthDeathDates(cell).getDisplayName();
    }
}
