package nobel;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class NobelParser {

    public static class NobelPrize {
        String year;
        String category;
        String laureates;
        String motivation;

        @Override
        public String toString() {
            return year + " | " + category + " | " + laureates +
                    (motivation.isEmpty() ? "" : " — " + motivation);
        }
    }

    public static void main(String[] args) throws IOException {

        final String url = "https://en.wikipedia.org/wiki/List_of_Nobel_laureates";

        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0")
                .get();

        // The main table of Nobel winners is typically the first wikitable sortable
        Element table = doc.select("table.wikitable.sortable").first();
        List<NobelPrize> prizes = new ArrayList<>();

        if (table != null) {
            Elements rows = table.select("tr");

            for (Element row : rows) {
                Elements cells = row.select("td");
                if (cells.size() < 4) continue; // skip header or malformed rows

                NobelPrize np = new NobelPrize();

                // Typical columns:
                // 0 = Year
                // 1 = Category
                // 2 = Laureate(s)
                // 3 = Motivation (optional)
                np.year = cells.get(0).text();
                np.category = cells.get(1).text();
                np.laureates = cells.get(2).text();
                np.motivation = cells.size() > 3 ? cells.get(3).text() : "";

                prizes.add(np);
            }
        }

        // Print extracted winners
        prizes.forEach(System.out::println);
    }
}
