package wikidata.explore;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class CommonsMedia {

    private CommonsMedia() {}

    public static boolean isImageFilename(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }

        String x = s.toLowerCase();

        return x.endsWith(".jpg")
                || x.endsWith(".jpeg")
                || x.endsWith(".png")
                || x.endsWith(".svg")
                || x.endsWith(".gif")
                || x.endsWith(".webp")
                || x.contains("/special:filepath/");
    }

    public static boolean isMediaProperty(String pid) {
        return "P18".equals(pid)      // image
                || "P41".equals(pid)  // flag image
                || "P94".equals(pid)  // coat of arms image
                || "P242".equals(pid) // locator map image
                || "P181".equals(pid) // taxon range map image
                || "P2716".equals(pid); // collage image
    }

    public static String filePathUrl(String filenameOrUrl) {
        if (filenameOrUrl == null || filenameOrUrl.isBlank()) {
            return filenameOrUrl;
        }

        String fileName = fileName(filenameOrUrl);

        return "https://commons.wikimedia.org/wiki/Special:FilePath/"
                + URLEncoder.encode(fileName, StandardCharsets.UTF_8)
                            .replace("+", "%20");
    }

    public static String fileName(String filenameOrUrl) {
        if (filenameOrUrl == null || filenameOrUrl.isBlank()) {
            return filenameOrUrl;
        }

        String s = filenameOrUrl;

        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < s.length()) {
            s = s.substring(slash + 1);
        }

        try {
            return java.net.URLDecoder.decode(
                    s,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    public static boolean isSvg(String s) {
        if (s == null) {
            return false;
        }

        String x = s.toLowerCase();

        int q = x.indexOf('?');
        if (q >= 0) {
            x = x.substring(0, q);
        }

        int hash = x.indexOf('#');
        if (hash >= 0) {
            x = x.substring(0, hash);
        }

        return x.endsWith(".svg");
    }
}
