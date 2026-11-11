package quiz.enrichment;

import objectview.Viewable;
import wikidata.WikidataIds;
import wikidata.explore.extract.WikidataDynamicObject;
import work.QueryContext;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resolves retained Wikipedia article links only to loaded, typed domain instances. */
final class WikipediaLinkedReferenceResolver {
    record Link(String text, String targetTitle, int start, int end) {
        Link {
            text = clean(text);
            targetTitle = clean(targetTitle);
        }
    }

    record Match(Viewable candidate, Link link) { }

    record Resolution(List<Match> matches, int resolvedLinks) {
        Resolution {
            matches = matches == null ? List.of() : List.copyOf(matches);
        }
    }

    private final Map<String, List<Viewable>> byArticleTitle;
    private final Map<String, Viewable> byQid;

    WikipediaLinkedReferenceResolver(Collection<? extends Viewable> candidates) {
        LinkedHashMap<String, Viewable> qids = new LinkedHashMap<>();
        LinkedHashMap<String, java.util.ArrayList<Viewable>> titles = new LinkedHashMap<>();
        if (candidates != null) {
            for (Viewable candidate : candidates) {
                if (candidate == null || !WikidataIds.isQid(candidate.getIdentifier())) continue;
                qids.putIfAbsent(candidate.getIdentifier(), candidate);
                if (candidate instanceof WikidataDynamicObject wikidata
                        && !clean(wikidata.enwikiTitle()).isBlank()) {
                    titles.computeIfAbsent(key(wikidata.enwikiTitle()), ignored ->
                            new java.util.ArrayList<>()).add(candidate);
                }
            }
        }
        LinkedHashMap<String, List<Viewable>> immutable = new LinkedHashMap<>();
        titles.forEach((title, values) -> immutable.put(title, List.copyOf(values)));
        byArticleTitle = Map.copyOf(immutable);
        byQid = Map.copyOf(qids);
    }

    List<Match> resolve(List<Link> links, WikimediaEntityLookup lookup, QueryContext context)
            throws Exception {
        return resolve(links, lookup, context, (link, candidate) -> true);
    }

    List<Match> resolve(List<Link> links, WikimediaEntityLookup lookup, QueryContext context,
            java.util.function.BiPredicate<Link, Viewable> admissible)
            throws Exception {
        return resolveDetailed(links, lookup, context, admissible).matches();
    }

    Resolution resolveDetailed(
            List<Link> links, WikimediaEntityLookup lookup, QueryContext context)
            throws Exception {
        return resolveDetailed(links, lookup, context, (link, candidate) -> true);
    }

    private Resolution resolveDetailed(
            List<Link> links, WikimediaEntityLookup lookup, QueryContext context,
            java.util.function.BiPredicate<Link, Viewable> admissible)
            throws Exception {
        if (links == null || links.isEmpty() || byQid.isEmpty()) {
            return new Resolution(List.of(), 0);
        }
        java.util.function.BiPredicate<Link, Viewable> accepts = admissible == null
                ? (link, candidate) -> true : admissible;
        LinkedHashMap<String, Match> matches = new LinkedHashMap<>();
        LinkedHashMap<String, WikimediaEntityLookup.EntityRecord> lookedUp =
                new LinkedHashMap<>();
        int resolvedLinks = 0;
        for (Link link : links) {
            if (link == null || link.targetTitle().isBlank()) continue;
            List<Viewable> direct = byArticleTitle.getOrDefault(
                    key(link.targetTitle()), List.of()).stream()
                    .filter(candidate -> accepts.test(link, candidate)).toList();
            Viewable candidate = direct.size() == 1 ? direct.getFirst() : null;
            if (candidate == null && lookup != null) {
                WikimediaEntityLookup.EntityRecord entity = lookedUp.get(link.targetTitle());
                if (!lookedUp.containsKey(link.targetTitle())) {
                    entity = lookup.byWikipediaTitle(link.targetTitle()).execute(context);
                    lookedUp.put(link.targetTitle(), entity);
                }
                if (entity != null) {
                    candidate = byQid.get(entity.qid());
                    if (candidate != null && !accepts.test(link, candidate)) {
                        candidate = null;
                    }
                }
            }
            if (candidate != null) {
                resolvedLinks++;
                matches.putIfAbsent(candidate.getIdentifier(), new Match(candidate, link));
            }
        }
        return new Resolution(List.copyOf(matches.values()), resolvedLinks);
    }

    static List<Link> introductionLinks(
            List<datasource.evidence.ArticleIntroduction.Link> links) {
        return links == null ? List.of() : links.stream()
                .map(link -> new Link(link.text(), link.targetTitle(),
                        link.start(), link.end()))
                .toList();
    }

    static List<Link> fragmentLinks(
            List<datasource.evidence.WikipediaArticle.ArticleLink> links) {
        return links == null ? List.of() : links.stream()
                .map(link -> new Link(link.text(), link.targetTitle(),
                        link.start(), link.end()))
                .toList();
    }

    private static String key(String value) {
        return clean(value).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
