package flag.auxiliary;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class PresidentInfo {
    String name;
    String term;
    String vicePresident;

    @Override
    public String toString() {
        return name + " | " + term + " | " + vicePresident;
    }
}

public class WikiPresidentParser {

    private static final String URL = 
        "https://en.wikipedia.org/wiki/List_of_presidents_of_the_United_States";

    public static List<PresidentInfo> fetchPresidents() throws IOException {
        List<PresidentInfo> result = new ArrayList<>();

        Document doc = Jsoup.connect(URL).get();

        // The main table is the first .wikitable with "No." in header
        Element presidentsTable = doc.select("table.wikitable:has(th:matchesOwn(^No\\.))").first();
        if (presidentsTable == null) {
            throw new IOException("Could not find presidents table");
        }

        Elements rows = presidentsTable.select("tr");
        for (Element row : rows) {
            //Elements ths = row.select("th");
            Elements tds = row.select("td");
            if (tds.size() < 5) continue; // skip header or malformed rows

            PresidentInfo p = new PresidentInfo();

            // Based on current Wikipedia layout (Nov 2025):
            // td[0] = Portrait, td[1] = Name, td[2] = Term, td[3] = Party, td[4] = Election, td[5] = Vice President
            // Some tables have merged cells; check dynamically
            String name = tds.get(1).text();
            String term = tds.get(2).text();
            String vicePresident = tds.get(tds.size() - 1).text();

            // Clean up
            name = name.replaceAll("\\[.*?\\]", "").trim();
            term = term.replaceAll("\\[.*?\\]", "").trim();
            vicePresident = vicePresident.replaceAll("\\[.*?\\]", "").trim();

            p.name = name;
            p.term = term;
            p.vicePresident = vicePresident;

            result.add(p);
        }

        return result;
    }

    public static void main(String[] args) {
        try {
            List<PresidentInfo> presidents = fetchPresidents();
            for (int i = 0; i < Math.min(10, presidents.size()); i++) {
                System.out.println(presidents.get(i));
            }
            System.out.println("Total presidents parsed: " + presidents.size());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

