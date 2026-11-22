package datasource.wikipedia;

import datasource.evidence.WikipediaArticle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Literal projection of data rows from one configured retained Wikipedia table. */
public final class WikipediaTableRows {
    private WikipediaTableRows() { }

    public record SelectedCell(
            String column,
            String text,
            List<WikipediaArticle.ArticleLink> links) {
        public SelectedCell {
            column = clean(column);
            text = clean(text);
            links = links == null ? List.of() : List.copyOf(links);
        }
    }

    public record SelectedRow(
            String sectionPath,
            String table,
            String displayColumn,
            String displayText,
            List<WikipediaArticle.ArticleLink> displayLinks,
            List<SelectedCell> cells,
            int start,
            int end) {
        public SelectedRow {
            sectionPath = clean(sectionPath);
            table = clean(table);
            displayColumn = clean(displayColumn);
            displayText = clean(displayText);
            displayLinks = displayLinks == null ? List.of() : List.copyOf(displayLinks);
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    public record Selection(
            List<SelectedRow> rows,
            int matchingSections,
            int matchingTables) {
        public Selection {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }
    }

    /** Reads the retained hierarchy; it never parses flattened article text. */
    public static Selection select(
            WikipediaArticle article,
            String sectionPath,
            String tableName,
            String displayColumn) {
        if (article == null || clean(sectionPath).isBlank()
                || clean(displayColumn).isBlank()) {
            return new Selection(List.of(), 0, 0);
        }
        Mutable result = new Mutable();
        for (WikipediaArticle.Section section : article.sections()) {
            select(section, "", sectionPath, tableName, displayColumn, result);
        }
        return new Selection(result.rows, result.sections, result.tables);
    }

    private static void select(
            WikipediaArticle.Section section,
            String parentPath,
            String configuredSection,
            String configuredTable,
            String displayColumn,
            Mutable result) {
        String path = parentPath.isBlank()
                ? section.heading() : parentPath + " > " + section.heading();
        if (same(path, configuredSection)) {
            result.sections++;
            for (WikipediaArticle.Block block : section.blocks()) {
                WikipediaArticle.ArticleTable table = block.table();
                if (table == null || !matchesTable(table, configuredTable)) continue;
                List<Row> grid = grid(table);
                Header header = header(grid, displayColumn);
                if (header == null) continue;
                result.tables++;
                for (int index = header.rowIndex() + 1; index < grid.size(); index++) {
                    Row row = grid.get(index);
                    List<SelectedCell> selectedCells = new ArrayList<>();
                    for (Column column : header.columns()) {
                        List<WikipediaArticle.TableCell> cells = cellsAt(
                                row.cells(), column.startColumn(), column.endColumn()).stream()
                                .filter(cell -> !cell.header()).toList();
                        if (cells.isEmpty()) continue;
                        String text = cells.stream().map(WikipediaArticle.TableCell::value)
                                .filter(value -> !clean(value).isBlank())
                                .collect(java.util.stream.Collectors.joining(" ")).trim();
                        List<WikipediaArticle.ArticleLink> links = cells.stream()
                                .flatMap(cell -> cell.links().stream()).distinct().toList();
                        selectedCells.add(new SelectedCell(column.label(), text, links));
                    }
                    SelectedCell display = selectedCells.stream()
                            .filter(cell -> same(cell.column(), displayColumn))
                            .findFirst().orElse(null);
                    if (display == null) continue;
                    result.rows.add(new SelectedRow(path, tableName(table), display.column(),
                            display.text(), display.links(), selectedCells,
                            row.source().start(), row.source().end()));
                }
            }
        }
        for (WikipediaArticle.Section child : section.sections()) {
            select(child, path, configuredSection, configuredTable, displayColumn, result);
        }
    }

    private static Header header(List<Row> rows, String displayColumn) {
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            List<Column> columns = rows.get(rowIndex).cells().stream()
                    .filter(placed -> placed.cell().header())
                    .map(placed -> new Column(placed.startColumn(), placed.endColumn(),
                            placed.cell().value()))
                    .toList();
            if (columns.stream().anyMatch(column -> same(column.label(), displayColumn))) {
                return new Header(rowIndex, columns);
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
    private static List<Row> grid(WikipediaArticle.ArticleTable table) {
        ArrayList<Row> rows = new ArrayList<>();
        Map<Integer, Integer> occupied = Map.of();
        for (WikipediaArticle.TableRow source : table.rows()) {
            ArrayList<PlacedCell> placed = new ArrayList<>();
            int column = 0;
            for (WikipediaArticle.TableCell cell : source.cells()) {
                int width = Math.max(1, cell.columnSpan());
                while (!free(occupied, column, width)) column++;
                placed.add(new PlacedCell(column, column + width, cell));
                column += width;
            }
            rows.add(new Row(source, List.copyOf(placed)));

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

    private static boolean matchesTable(
            WikipediaArticle.ArticleTable table, String configured) {
        return clean(configured).isBlank() || same(table.caption(), configured)
                || same(table.getDisplayName(), configured);
    }

    private static String tableName(WikipediaArticle.ArticleTable table) {
        return table.caption().isBlank() ? table.getDisplayName() : table.caption();
    }

    private static boolean same(String left, String right) {
        return clean(left).equalsIgnoreCase(clean(right));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private record Header(int rowIndex, List<Column> columns) { }
    private record Column(int startColumn, int endColumn, String label) { }
    private record Row(WikipediaArticle.TableRow source, List<PlacedCell> cells) { }
    private record PlacedCell(
            int startColumn, int endColumn, WikipediaArticle.TableCell cell) { }

    private static final class Mutable {
        private final List<SelectedRow> rows = new ArrayList<>();
        private int sections;
        private int tables;
    }
}
