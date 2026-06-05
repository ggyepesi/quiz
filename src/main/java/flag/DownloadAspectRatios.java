package flag;

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aux.UrlLineProcessor;
import aux.UrlReader;

public class DownloadAspectRatios {
    static final String wiki = "https://en.wikipedia.org/wiki/";

    // See AspectRatioReader below for the missing aspect ratios.
    public static void main(String[] args) throws Exception {
    }

    public static void readAscpectRatios() throws Exception {
        String url = wiki + "List_of_national_flags_of_sovereign_states?action=raw";
        new UrlReader<String>(new AspectRatioReader()).read(url);
    }
}

// No aspect ratio for Somaliland and South Ossetia; link for Nepal,
// the rest (Austria (state), Costa Rica (state), Paraguay (reverse), Peru (state), Venezuela (state))
// inherit from the previous country
class AspectRatioReader implements UrlLineProcessor<String> {
    private static final Pattern flagPattern = Pattern.compile("\\|\\[\\[File:Flag of (?<country>[^\\.]+)\\.svg");
    private static final Pattern aspectRatioPattern =
        //Pattern.compile("\\|\\{\\{resratio\\|(?<ratio1>[0-9]+)(\\:|\\|)(?<ratio2>[0-9]+)\\}\\}", Pattern.CASE_INSENSITIVE);
        // "r=" for Finland
        Pattern.compile("((\\|)|\\|r\\=)(?<ratio1>[0-9]+)(\\:|\\|)(?<ratio2>[0-9]+)", Pattern.CASE_INSENSITIVE);
        //private static final Pattern scopePattern = Pattern.compile("|{{resratio|2:3}}");

    private String country = null;

    @Override
    public URL processLine(String line) throws Exception {
        Matcher matcher = flagPattern.matcher(line);
        if (matcher.find()) {
            String name =  matcher.group("country");
            if (country != null) {
                System.out.println("  No aspect ratio for " + country);
            }
            country = name;
            return null;
        }
        matcher = aspectRatioPattern.matcher(line);
        if (matcher.find()) {
            // r1 = matcher.group("ratio1");
            // String r2 = matcher.group("ratio2");
            if (country == null) {
                System.out.println("AspectRatio:  no country for " + line);
            }
            country = null;
        }

        return null;  // redirect url
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public String done() throws Exception {
        return "done";
    }
}