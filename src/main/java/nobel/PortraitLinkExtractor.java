package nobel;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aux.Constants;
import objectview.utils.UrlOpener;

public class PortraitLinkExtractor {
    static private final Pattern linkPattern = Pattern.compile("<a href=\"(/wiki/[^\"#:]+)\"[^>]*>([^<]+)</a>");
    static private final Pattern jpgPattern = Pattern.compile("https://upload\\.wikimedia\\.org/[^\"']+\\.jpg");

    public static void main(String[] args) throws Exception {
        ///extractLinks();
        readPortraitLinks();
    }

    public static Map<String, String>  readPortraitLinks() throws Exception{
        Map<String, String> linkToName = new TreeMap<>();
        BufferedReader reader = new BufferedReader(new FileReader(Constants.nobelDirectory + "nobelportraitlinks.txt"));
        String line;
        while ((line = reader.readLine()) != null) {
            int h = line.indexOf("https://");
            if (h == -1) {
                continue;
            }
            String name = line.substring(0, h).strip();
            String url = line.substring(h).strip();
            linkToName.put(name, url);
        }
        reader.close();
        System.out.println(linkToName.size() + " names");

        return linkToName;
    }

    // Extracts links to portraits
    public static void extractPortraitLinks() {
        try {
            String page = "https://en.wikipedia.org/wiki/List_of_Nobel_laureates";
            BufferedReader reader = new BufferedReader(new InputStreamReader(UrlOpener.open(page)));
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher matcher = linkPattern.matcher(line);
                while (matcher.find()) {
                    String link = matcher.group(1).strip();
                    String name = matcher.group(2).strip();
                    String fullLink = "https://en.wikipedia.org" + link;
                    // System.out.println(name + " -> " + fullLink);
                    String imageUrl = parseImageUrl(fullLink);
                    System.out.println(name + "\t" + (imageUrl == null ? fullLink : imageUrl));
                }
            }
            reader.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String parseImageUrl(String pageUrl) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(UrlOpener.open(pageUrl)));
        String line;
        String imageUrl = null;
        while ((line = reader.readLine()) != null) {
            Matcher matcher = jpgPattern.matcher(line);
            if (matcher.find()) {
                imageUrl = matcher.group();
            } 
        }
        reader.close();
        return imageUrl;
    }
}