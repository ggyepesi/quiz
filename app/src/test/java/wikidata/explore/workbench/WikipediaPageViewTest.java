package wikidata.explore.workbench;

import objectview.field.FieldSet;
import org.junit.jupiter.api.Test;
import wikidata.explore.wikiproject.WikiProjectArticle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikipediaPageViewTest {

    @Test void categoryNameIsTheDisplayNameAndAConfigurableCardField() {
        WikiProjectArticle page = new WikiProjectArticle(
                "Category:History of Hungary", "", 123, "");
        WikipediaPageView view = new WikipediaPageView(page, "subcategory");

        assertEquals("History of Hungary", view.getDisplayName());
        assertEquals("History of Hungary", view.title());
        assertTrue(FieldSet.of(view).fields().stream()
                .anyMatch(field -> field.name().equals("displayName")),
                "the category name must be available in search/sort/view configuration");
    }

    @Test void fallbackIdentityRetainsTheMediaWikiNamespace() {
        WikipediaPageView category = new WikipediaPageView(
                new WikiProjectArticle("Category:History", "", 0, ""), "parent");
        WikipediaPageView article = new WikipediaPageView(
                new WikiProjectArticle("History", "", 0, ""), "article");

        assertEquals("enwiki-title:Category:History", category.getIdentifier());
        assertEquals("enwiki-title:History", article.getIdentifier());
    }

    @Test void theNamespacedTitleIsIdentityAndNotShownTwice() {
        // pageTitle exists so a page without an id still has a distinct identity;
        // presented, it would repeat the display name with a prefix in front.
        WikiProjectArticle page = new WikiProjectArticle(
                "Category:History of Hungary", "", 123, "");
        WikipediaPageView view = new WikipediaPageView(page, "subcategory");

        assertTrue(FieldSet.of(view).fields().stream()
                .noneMatch(field -> field.name().equals("pageTitle")),
                "identity plumbing is not a card field");
    }

    @Test void aPageWithoutAnIdIsStillToldApartByItsNamespace() {
        WikipediaPageView category = new WikipediaPageView(
                new WikiProjectArticle("Category:Hungary", "", 0, ""), "subcategory");
        WikipediaPageView article = new WikipediaPageView(
                new WikiProjectArticle("Hungary", "", 0, ""), "article");

        assertEquals("History of Hungary", "History of Hungary");
        org.junit.jupiter.api.Assertions.assertNotEquals(
                category.getIdentifier(), article.getIdentifier(),
                "a category and an article of the same name are different pages");
    }
}
