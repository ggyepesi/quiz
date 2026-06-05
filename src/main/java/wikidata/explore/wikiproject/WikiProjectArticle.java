package wikidata.explore.wikiproject;

public class WikiProjectArticle {
    private final String title;
    private final String assessmentPageTitle;
    private final int pageId;
    private final String category;
    private String qid;

    public WikiProjectArticle(String title,
                              String assessmentPageTitle,
                              int pageId,
                              String category) {
        this.title = title == null ? "" : title;
        this.assessmentPageTitle =
                assessmentPageTitle == null ? "" : assessmentPageTitle;
        this.pageId = pageId;
        this.category = category == null ? "" : category;
    }

    public String title() { return title; }
    public String assessmentPageTitle() { return assessmentPageTitle; }
    public int pageId() { return pageId; }
    public String category() { return category; }
    public String qid() { return qid; }
    public void qid(String qid) { this.qid = qid == null ? "" : qid; }

    public String wikipediaUrl() {
        return "https://en.wikipedia.org/wiki/" + title.replace(" ", "_");
    }

    public String wikidataUrl() {
        return qid == null || qid.isBlank()
                ? ""
                : "https://www.wikidata.org/wiki/" + qid;
    }

    @Override
    public String toString() {
        return title
                + (qid == null || qid.isBlank() ? "" : " (" + qid + ")")
                + " — "
                + category;
    }
}
