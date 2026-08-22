package wikidata.explore.model;

/**
 * Additive interpretation of an English Wikipedia category membership for one field.
 * The single {@code <value>} placeholder names the part resolved through the matching
 * Wikipedia article to a Wikidata entity. Category sources supplement the primary field
 * source; they never replace it.
 *
 * <p>English is not a setting. The article reader, the sitelink it follows and the
 * evidence it records are all enwiki by construction, so a language field here would be
 * a choice with no effect — a knob that lies. Models saved while one existed still load;
 * the property is simply no longer read.
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class WikipediaCategoryRule {
    private String pattern = "";
    private CategoryCandidatePolicy policy = CategoryCandidatePolicy.REVIEW;

    public String pattern() { return pattern == null ? "" : pattern; }
    public void pattern(String value) { pattern = value == null ? "" : value.trim(); }
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
        copy.pattern(pattern()); copy.policy(policy());
        return copy;
    }
}
