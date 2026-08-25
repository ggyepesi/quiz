package wikidata.explore.workbench;

import objectview.ViewableAdapter;
import objectview.annotations.DisplayField;
import objectview.annotations.Link;
import objectview.annotations.Minor;
import wikidata.explore.wikiproject.WikiProjectArticle;

/** One Wikipedia page, independent of how it relates to the browsed category. */
final class WikipediaPageView extends ViewableAdapter {
    @DisplayField private final String title;
    private final String qid;
    @Link(text = "Wikipedia") private final String wikipedia;
    @Minor private final int pageId;
    @Minor private final String relationship;

    WikipediaPageView(WikiProjectArticle page, String relationship) {
        this.title = page == null ? "" : page.title();
        this.qid = page == null || page.qid() == null ? "" : page.qid();
        this.wikipedia = page == null ? "" : page.wikipediaUrl();
        this.pageId = page == null ? 0 : page.pageId();
        this.relationship = relationship == null ? "" : relationship;
    }

    String title() { return title; }
    String qid() { return qid; }
    String wikipedia() { return wikipedia; }

    @Override public String getIdentifier() {
        return pageId > 0 ? "enwiki:" + pageId : "enwiki-title:" + title;
    }

    @Override public String getDisplayName() {
        return title.regionMatches(true, 0, "Category:", 0, 9)
                ? title.substring(9) : title;
    }
}
