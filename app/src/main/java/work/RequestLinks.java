package work;

/**
 * How a logged request becomes something a reader can open.
 *
 * <p>A workflow log shows the request behind each step, and a request is often browsable: a
 * SPARQL query has a query service, an action-API call is already a URL. Which, though, is a
 * property of the SOURCE that produced the step — and the log used to answer it by matching
 * substrings of the step's {@code queryType}, which meant a neutral package carried
 * {@code query.wikidata.org} and {@code dbpedia.org} literals and could only ever browse
 * those two. A third source got no link and nothing said why.
 *
 * <p>So the log renders whatever link it is handed and knows about no endpoint at all. The
 * application installs the rules for the sources it actually has, through
 * {@link LogNode#linksProvidedBy}.
 */
@FunctionalInterface
public interface RequestLinks {

    /** No source is installed, so no request is browsable. */
    RequestLinks NONE = (queryType, request) -> null;

    /**
     * What this request browses to, or null when nothing does.
     *
     * @param queryType how the step was labelled — advisory, and correctable
     * @param request   the request text as logged
     */
    Resolved forRequest(String queryType, String request);

    /**
     * A browsable request.
     *
     * @param link              {@code "label|url"} — what the reader clicks
     * @param correctedQueryType a better label for the step, or null to keep the one it has.
     *                           A source can only tell by looking at the request: a
     *                           sub-query logged under a default type may in fact be
     *                           something else, and the reader should not be told otherwise.
     */
    record Resolved(String link, String correctedQueryType) {
        public static Resolved of(String label, String url) {
            return new Resolved(label + "|" + url, null);
        }

        public Resolved labelledAs(String queryType) {
            return new Resolved(link, queryType);
        }
    }
}
