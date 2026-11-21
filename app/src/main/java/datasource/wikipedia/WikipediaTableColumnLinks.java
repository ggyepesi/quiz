package datasource.wikipedia;

import datasource.evidence.WikipediaArticle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Mutable result = new Mutable();
        for (WikipediaArticle.Section section : article.sections()) {
            select(section, "", spec, result);
        }
        return new Selection(result.links, result.sections, result.tables, result.rows);
    }

    private static void select(
            WikipediaArticle.Section section,
            String parentPath,
            WikipediaDatasourceProvider.ArticleTableColumnLinkSpec spec,
            Mutable result) {
        String path = parentPath.isBlank()
                ? section.heading() : parentPath + " > " + section.heading();
        if (same(path, spec.sectionPath())) {
            result.sections++;
            for (WikipediaArticle.Block block : section.blocks()) {
                WikipediaArticle.ArticleTable table = block.table();
                if (table == null || !matchesTable(table, spec.table())) continue;
                List<List<PlacedCell>> grid = grid(table);
                Column column = column(grid, spec.column());
                if (column == null) continue;
                result.tables++;
                for (int index = column.headerRow() + 1; index < grid.size(); index++) {
                    List<WikipediaArticle.TableCell> cells = cellsAt(
                            grid.get(index), column.startColumn(), column.endColumn());
                    cells = cells.stream().filter(cell -> !cell.header()).toList();
                    if (cells.isEmpty()) continue;
                    result.rows++;
                    for (WikipediaArticle.TableCell cell : cells) {
                        for (WikipediaArticle.ArticleLink link : cell.links()) {
                            result.links.add(new SelectedLink(path, tableName(table),
                                    column.label(), cell.value(), link));
                        }
                    }
                }
            }
        }
        for (WikipediaArticle.Section child : section.sections()) {
            select(child, path, spec, result);
        }
    }

    private static boolean matchesTable(
            WikipediaArticle.ArticleTable table, String configured) {
        if (configured == null || configured.isBlank()) return true;
        return same(table.caption(), configured)
                || same(table.getDisplayName(), configured);
    }

    private static String tableName(WikipediaArticle.ArticleTable table) {
        return table.caption().isBlank() ? table.getDisplayName() : table.caption();
    }

    private static Column column(List<List<PlacedCell>> grid, String header) {
        for (int rowIndex = 0; rowIndex < grid.size(); rowIndex++) {
            for (PlacedCell placed : grid.get(rowIndex)) {
                WikipediaArticle.TableCell cell = placed.cell();
                if (cell.header() && same(cell.value(), header)) {
                    return new Column(placed.startColumn(), placed.endColumn(),
                            rowIndex, cell.value());
                }
            }
        }
        return null;
    }

    private static List<WikipediaArticle.TableCell> cellsAt(
            List<PlacedCell> row, int startColumn, int endColumn) {
        ArrayList<WikipediaArticle.TableCell> result = new ArrayList<>();
        for (PlacedCell placed : row) {
            if (placed.startColumn() < endColumn && placed.endColumn() > startColumn) {
                result.add(placed.cell());
            }
        }
        return List.copyOf(result);
    }

    /** Places cells in their logical columns, including authored row/column spans. */
    private static List<List<PlacedCell>> grid(WikipediaArticle.ArticleTable table) {
        ArrayList<List<PlacedCell>> rows = new ArrayList<>();
        Map<Integer, Integer> occupied = Map.of();
        for (WikipediaArticle.TableRow row : table.rows()) {
            ArrayList<PlacedCell> placed = new ArrayList<>();
            int column = 0;
            for (WikipediaArticle.TableCell cell : row.cells()) {
                int width = Math.max(1, cell.columnSpan());
                while (!free(occupied, column, width)) column++;
                placed.add(new PlacedCell(column, column + width, cell));
                column += width;
            }
            rows.add(List.copyOf(placed));

            LinkedHashMap<Integer, Integer> next = new LinkedHashMap<>();
            occupied.forEach((index, remaining) -> {
                if (remaining > 1) next.put(index, remaining - 1);
            });
            for (PlacedCell value : placed) {
                int remaining = Math.max(1, value.cell().rowSpan()) - 1;
                if (remaining == 0) continue;
                for (int index = value.startColumn(); index < value.endColumn(); index++) {
                    next.merge(index, remaining, Math::max);
                }
            }
            occupied = Map.copyOf(next);
        }
        return List.copyOf(rows);
    }

    private static boolean free(Map<Integer, Integer> occupied, int start, int width) {
        for (int index = start; index < start + width; index++) {
            if (occupied.getOrDefault(index, 0) > 0) return false;
        }
        return true;
    }

    private static boolean same(String left, String right) {
        return clean(left).equalsIgnoreCase(clean(right));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record Column(
            int startColumn, int endColumn, int headerRow, String label) { }
    private record PlacedCell(
            int startColumn, int endColumn, WikipediaArticle.TableCell cell) { }

    private static final class Mutable {
        private final List<SelectedLink> links = new ArrayList<>();
        private int sections;
        private int tables;
        private int rows;
    }
}
