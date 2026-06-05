package nobel;

import java.io.*;

import aux.UrlOpener;

public class NoImageExtractor {
    public static String download(String name) throws Exception {
        String url = "https://en.wikipedia.org/wiki/" + name.replaceAll(" ", "_");
        BufferedReader reader = new BufferedReader(new InputStreamReader(UrlOpener.open(url)));
        StringBuilder html = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            html.append(line);
        }
        reader.close();
        return html.toString();
    }

    public static String extractImage(String html) {
        String marker = "property=\"og:image\" content=\"";
        int start = html.indexOf(marker);
        if (start == -1)
            return null;

        start += marker.length();
        int end = html.indexOf("\"", start);
        return html.substring(start, end);
    }

    public static String extractImageForName(String name) throws Exception {
        return extractImage(download(name));
    }

    public static void main(String[] args) throws Exception {
        for (String name : NoImagePortrait.names) {
            if (extractImageForName(name) == null) {
                System.out.println("No image for " + name);
            }
        }
    }
}
