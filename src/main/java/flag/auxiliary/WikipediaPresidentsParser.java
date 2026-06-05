package flag.auxiliary;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class WikipediaPresidentsParser {

    private static final String WIKI_URL =
            "https://en.wikipedia.org/wiki/List_of_presidents_of_the_United_States";

    public static List<USPresident> fetchPresidents() throws IOException {
        List<USPresident> presidents = new ArrayList<>();

        Document document = Jsoup.connect(WIKI_URL)
                .userAgent("Mozilla/5.0")
                .get();

        // Main presidents table
        Element table = document.selectFirst("table.wikitable");
        Elements rows = table.select("tbody > tr");

        // Skip header row
        for (int i = 1; i < rows.size(); i++) {
            Element row = rows.get(i);
            Elements cols = row.select("th, td");

            if (cols.size() < 7) continue;

            String name = cols.get(2).text();
            String term = cols.get(1).text();
            String party = cols.get(5).text();

            presidents.add(new USPresident(name, term, party));
        }

        return presidents;
    }

    public static void main(String[] args) {
        try {
            List<USPresident> presidents = WikipediaPresidentsParser.fetchPresidents();

            presidents.forEach(System.out::println);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

class USPresident {
    private final String name;
    private final String term;
    private final String party;

    public USPresident(String name, String term, String party) {
        this.name = name;
        this.term = term;
        this.party = party;
    }

    @Override
    public String toString() {
        return name + " | " + term + " | " + party;
    }
}
