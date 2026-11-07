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
    @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
    private final java.util.List<WikipediaCategoryMapping> mappings =
            new java.util.ArrayList<>();

    public String pattern() { return pattern == null ? "" : pattern; }
    public void pattern(String value) { pattern = value == null ? "" : value.trim(); }
    public CategoryCandidatePolicy policy() {
        return policy == null ? CategoryCandidatePolicy.REVIEW : policy;
    }
    public void policy(CategoryCandidatePolicy value) {
        policy = value == null ? CategoryCandidatePolicy.REVIEW : value;
    }

    public java.util.List<WikipediaCategoryMapping> mappings() { return mappings; }

    /** Exact mappings win over the title pattern because they are the more specific
     * authored statement.  A pattern match returns a Wikipedia title to resolve; an
     * exact match returns the already configured Wikidata identity. */
    public Match match(String category) {
        if (category == null) return null;
        for (WikipediaCategoryMapping mapping : mappings) {
            if (mapping != null && mapping.matches(category)) {
                return new Match(mapping.valueQid(), "", mapping.category());
            }
        }
        if (!patternConfigured()) return null;
        int placeholder = pattern().indexOf("<value>");
        String prefix = pattern().substring(0, placeholder);
        String suffix = pattern().substring(placeholder + "<value>".length());
        String clean = WikipediaCategoryMapping.cleanCategory(category);
        if (!clean.startsWith(prefix) || !clean.endsWith(suffix)
                || clean.length() < prefix.length() + suffix.length()) return null;
        String title = clean.substring(prefix.length(), clean.length() - suffix.length())
                .trim();
        return title.isBlank() ? null : new Match("", title, clean);
    }

    public boolean patternConfigured() {
        return pattern().split("<value>", -1).length - 1 == 1;
    }

    public boolean configured() {
        return patternConfigured() || mappings.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(WikipediaCategoryMapping::configured);
    }
    public WikipediaCategoryRule copy() {
        WikipediaCategoryRule copy = new WikipediaCategoryRule();
        copy.pattern(pattern()); copy.policy(policy());
        mappings.stream().filter(java.util.Objects::nonNull)
                .map(WikipediaCategoryMapping::copy).forEach(copy.mappings::add);
        return copy;
    }

    public record Match(String valueQid, String valueTitle, String category) {
        public Match {
            valueQid = valueQid == null ? "" : valueQid;
            valueTitle = valueTitle == null ? "" : valueTitle;
            category = category == null ? "" : category;
        }
    }
}
