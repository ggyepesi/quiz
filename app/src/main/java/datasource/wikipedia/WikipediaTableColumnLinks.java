package datasource.wikipedia;

import datasource.evidence.WikipediaArticle;

import java.util.List;

/** Literal projection of retained links from one configured article-table column. */
public final class WikipediaTableColumnLinks {
    private WikipediaTableColumnLinks() { }

    public record SelectedLink(
            String sectionPath,
            String table,
            String column,
            String cellText,
            WikipediaArticle.ArticleLink link) {
        public SelectedLink {
            sectionPath = clean(sectionPath);
            table = clean(table);
            column = clean(column);
            cellText = clean(cellText);
            link = java.util.Objects.requireNonNull(link);
        }
    }

    public record Selection(
            List<SelectedLink> links,
            int matchingSections,
            int matchingTables,
            int matchingRows) {
        public Selection {
            links = links == null ? List.of() : List.copyOf(links);
        }
    }

    /** Reads the retained hierarchy; it never parses flattened article text. */
    public static Selection select(
            WikipediaArticle article,
            WikipediaDatasourceProvider.ArticleTableColumnLinkSpec spec) {
        if (article == null || spec == null || !spec.configured()) {
            return new Selection(List.of(), 0, 0, 0);
        }
        WikipediaTableRows.Selection rows = WikipediaTableRows.select(
                article, spec.sectionPath(), spec.table(), spec.column());
        List<SelectedLink> links = rows.rows().stream().flatMap(row ->
                        row.displayLinks().stream().map(link -> new SelectedLink(
                                row.sectionPath(), row.table(), row.displayColumn(),
                                row.displayText(), link)))
                .toList();
        return new Selection(links, rows.matchingSections(), rows.matchingTables(),
                rows.rows().size());
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

}
