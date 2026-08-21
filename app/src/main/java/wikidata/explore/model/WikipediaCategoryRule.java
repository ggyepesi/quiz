package wikidata.explore.model;

/**
 * Additive interpretation of an English Wikipedia category membership for one field.
 * The single {@code <value>} placeholder names the part resolved through the matching
 * Wikipedia article to a Wikidata entity. Category sources supplement the primary field
 * source; they never replace it.
 */
public class WikipediaCategoryRule {
    private String pattern = "";
    private String language = "en";
    private CategoryCandidatePolicy policy = CategoryCandidatePolicy.REVIEW;

    public String pattern() { return pattern == null ? "" : pattern; }
    public void pattern(String value) { pattern = value == null ? "" : value.trim(); }
    public String language() { return language == null || language.isBlank() ? "en" : language; }
    public void language(String value) { language = value == null || value.isBlank() ? "en" : value.trim(); }
    public CategoryCandidatePolicy policy() {
        return policy == null ? CategoryCandidatePolicy.REVIEW : policy;
    }
    public void policy(CategoryCandidatePolicy value) {
        policy = value == null ? CategoryCandidatePolicy.REVIEW : value;
    }
    public boolean configured() {
        return !pattern().isBlank() && pattern().indexOf("<value>") >= 0;
    }
    public WikipediaCategoryRule copy() {
        WikipediaCategoryRule copy = new WikipediaCategoryRule();
        copy.pattern(pattern()); copy.language(language()); copy.policy(policy());
        return copy;
    }
}
