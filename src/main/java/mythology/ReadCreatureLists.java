package mythology;

import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

public class ReadCreatureLists {

    public static void main(String[] args) throws Exception {
        readTables("https://en.wikipedia.org/wiki/List_of_mortals_in_Greek_mythology");
    }

    public static void readTables(String url) throws Exception {
        Document document = Jsoup.connect(url).get();
        Elements divs = document.select("div");
        // mw-heading gives the title, the next div contains the list
        boolean listFollows = false;
        for (int i = 0; i < divs.size(); ++i) {
            Element div = divs.get(i);
            if (listFollows) {
                parseList(readList(div));
                listFollows = false;
            } else if (div.className().equals("mw-heading mw-heading2")) {
                System.out.println("Title " + div.select("h2").attr("id"));
                listFollows = true;
            } else {
                listFollows = false;
            }
        }
    }

    private static void parseList(List<String> list) {
        for (String creature : list) {
            System.out.println("  " + "https://en.wikipedia.org/wiki/" + creature.replaceAll(" ", "_"));
        }
    }


    private static List<String> readList(Element column) throws Exception {
        Elements listElement = column.select("ul li");
        if (listElement.isEmpty()) {
            List<String> list = new ArrayList<>(1);
            list.add(column.select("a").select("title").text());
            return list;
        }
        List<String> list = new ArrayList<>(listElement.size());
        for (Element l : listElement) {
            list.add(l.select("a").attr("title"));
            //list.add(l.text());
        }
        return list;
    }

}
