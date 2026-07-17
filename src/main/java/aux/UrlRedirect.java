package aux;

import objectview.utils.UrlOpener;

import java.io.*;
import java.util.regex.*;

public class UrlRedirect {
    public static void main(String[] args) throws Exception {
        String finalPage = followRedirect("Aage N. Bohr");
        System.out.println("Final page: " + finalPage);
    }

    public static String followRedirect(String pageTitle) throws Exception {
        String currentPage = pageTitle;
        while (true) {
            if (!currentPage.startsWith(Constants.wiki)) {
                currentPage = Constants.wiki + currentPage.replace(" ", "_");
             }
            currentPage = currentPage + "?action=raw";
            BufferedReader reader = new BufferedReader(new InputStreamReader(UrlOpener.open(currentPage)));
            String line;
            String redirectTarget = null;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                // Check for redirect line (case-insensitive)
                if (line.toUpperCase().startsWith("#REDIRECT")) {
                    // Extract [[Target Page]]
                    Pattern pattern = Pattern.compile("#REDIRECT\\s*\\[\\[(.+?)]]", Pattern.CASE_INSENSITIVE);
                    Matcher matcher = pattern.matcher(line);
                    if (matcher.find()) {
                        redirectTarget = matcher.group(1).trim();
                        break;
                    }
                }
            }
            reader.close();

            if (redirectTarget == null) {
                // No redirect, current page is final
                return currentPage;
            } else {
                System.out.println(currentPage + " is a redirect to " + redirectTarget);
                currentPage = redirectTarget; // Follow redirect
            }
        }
    }
}