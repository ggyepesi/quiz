package wikidata.explore.workbench;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Hidden;
import objectview.annotations.Link;
import objectview.annotations.Minor;
import wikidata.explore.wikiproject.WikiProjectArticle;

/** One Wikipedia page, independent of how it relates to the browsed category. */
final class WikipediaPageView extends ViewableAdapter {
    /** Original MediaWiki title, including its namespace, retained for identity.
     *  Hidden: it duplicates the display name for a reader, and the namespace is
     *  identity plumbing rather than something to configure or read. */
    @Hidden private final String pageTitle;
    /** The page title is both the card identity and an ordinary configurable field. */
    @DisplayField private final String displayName;
    private final String qid;
    @Link(text = "Wikipedia") private final String wikipedia;
    @Minor private final int pageId;
    @Minor private final String relationship;

    WikipediaPageView(WikiProjectArticle page, String relationship) {
        String title = page == null ? "" : page.title();
        this.pageTitle = title;
        this.displayName = title.regionMatches(true, 0, "Category:", 0, 9)
                ? title.substring(9) : title;
        this.qid = page == null || page.qid() == null ? "" : page.qid();
        this.wikipedia = page == null ? "" : page.wikipediaUrl();
        this.pageId = page == null ? 0 : page.pageId();
        this.relationship = relationship == null ? "" : relationship;
    }

    String title() { return displayName; }
    String qid() { return qid; }
    String wikipedia() { return wikipedia; }

    @Override public String getIdentifier() {
        return pageId > 0 ? "enwiki:" + pageId : "enwiki-title:" + pageTitle;
    }

    @Override public String getDisplayName() {
        return displayName;
    }
}
