package misc;
// Methods and constants for parsing entities, like file, url, description, from raw wikipedia
// text files. 

import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TextParser {
    // Case-insensitive, see https://en.wikipedia.org/wiki/Emblem_of_the_Republic_of_China?action=raw for example.
    private final static Pattern redirectPattern = Pattern.compile("\\s*\\#REDIRECT\\s*\\[\\[.*", Pattern.CASE_INSENSITIVE);
    private final static Pattern redirectStartPattern = Pattern.compile("\\s*\\#REDIRECT\\s*\\[\\[\\s*", Pattern.CASE_INSENSITIVE);
    private final static Pattern redirectEndPattern = Pattern.compile("\\]\\]", Pattern.CASE_INSENSITIVE);
    // Confused pattern for Infobx start.
    private final static Pattern infoboxPattern =
        Pattern.compile(".*\\{\\{\\s*short description.*|\\s*\\{\\{\\s*Infobox.*|.*\\{\\{\\s*multiple image.*", Pattern.CASE_INSENSITIVE);
 

    private final static String blockStart = "{{";
    private final static String blockEnd = "}}";

    private static final Pattern imagePattern = Pattern.compile("\\s*\\|\\s*(image[0-9]|image|lesser)\\s*=.*", Pattern.CASE_INSENSITIVE);
    // Image OR lesser(see sweden) OR what else??? 
    private static final Pattern imageStartPattern = Pattern.compile("\\s*\\|\\s*(image[0-9]|image|lesser)\\s*=\\s*", Pattern.CASE_INSENSITIVE);
        
   // The country name in the list line appears after "|[[" or after "[File:" (flags) or after "{{center|[[", image appears after ...
    private final static Pattern fileNameStartPattern = Pattern.compile(
        "\\|\\{\\{center\\|\\[\\[|\\|\\{\\{center\\||\\[File:\\[\\[|\\[File:|\\|File:|File:|\\[\\[|\\{\\{center\\||\\[\\[\\{\\{|\\[\\[", Pattern.CASE_INSENSITIVE);
    private final static Pattern fileNameEndPattern = Pattern.compile("\\]\\]\\}\\}|\\]\\]|\\}\\}|#|\\||\\<");

    static final private String href = "<a href=\"";

    public static String getHrefUrl(String line) {
        int start = line.indexOf(href);
        if (start == -1) return null;
        start += href.length();
        int end = line.indexOf("\"", start);
        if (end == -1) {
            System.out.println("Expected \" in href " + line.substring(start));
            return null;
        }
        return line.substring(start, end);
    }

    public static String parseCategory(String line) {
        // Category parsed this way might not be a coat of arms category - further parsing might be necessary online.
        // See Russia for example.
        final String categoryPrefix = "[[Category:";
        int start = line.indexOf(categoryPrefix);
        if (start == -1) return null;
        int end = line.indexOf("]]");
        if (end == -1) return null;
        return line.substring(start + categoryPrefix.length(), end);
    }

    // Returns the number of unpaired (nested) curly bracket pairs in line.
    // Ignore closing unexpected right curlybrackets (see "Bosnia and Herzegovina topics" in https://en.wikipedia.org/wiki/Bosnia_and_Herzegovina).
    public static int updateNumberOfOpenBlocks(String line, int numberOfOpenBlocks) {
        for (int i = 0; i < line.length() - 1; ++i) {
            if (line.substring(i, i + 2).equals(blockStart)) {
                ++i;
                ++numberOfOpenBlocks;
            } else if (line.substring(i, i + 2).equals(blockEnd)) {
                ++i;
                if (numberOfOpenBlocks == 0) {
                    System.out.println("Malformed curlybracket-pair limited blocks " + line);
                    break;
                } else {
                    --numberOfOpenBlocks;
                }
            }
        }
        return numberOfOpenBlocks;
    }

    public static String ParseFilename(Pattern pattern, Pattern startPattern, Pattern fStartPattern, Pattern fEndPattern, String line) {
        boolean matches = true;
        if (pattern != null) {
            Matcher matcher = pattern.matcher(line);
            matches = matcher.matches();
        }
        if (matches) {
            Debug("pattern matches");
            Matcher matcher = startPattern.matcher(line);
            if (!matcher.find()) return null;
            line = line.substring(matcher.end());
            Debug("after startPattern " + line);
            Matcher endMatcher = fEndPattern.matcher(line);
            int endStart = endMatcher.find() ? endMatcher.end() : 0;
            if (fStartPattern != null) {
                Debug("matching fstartPattern " + fStartPattern);
                matcher = fStartPattern.matcher(line);
                if (matcher.find() && matcher.start() < endStart) {
                    Debug("fStartPattern found at " + matcher.start() + ", " + matcher.end() + ": " + line.substring(matcher.start(), matcher.end()));
                    line = line.substring(matcher.end());
                }
            }
            Debug("finding " + fEndPattern.toString() + " in " + line);
            matcher = fEndPattern.matcher(line);
            if (matcher.find()) {
                Debug("end pattern found at " + matcher.start() + ", " + matcher.end());
                return line.substring(0, matcher.start()).trim(); 
            } else {
                return line;
            }
        }
        Debug("Doesn't match " + pattern);
        return null;
    }

    // Parses filename if line matches pattern.
    public static String ParseFilename(Pattern pattern, Pattern startPattern, String line) {
        return ParseFilename(pattern, startPattern, fileNameStartPattern, fileNameEndPattern, line);
    }
    
    // Returns the image url if the line matches imagePattern.
    public static String ParseImageUrl(String line) throws Exception {
        String filename = ParseFilename(imagePattern, imageStartPattern, line);
        // Vatican has a single line infobox.
        if (filename == null) {
            filename = ParseFilename(null, imageStartPattern, line);
        }
        return filename == null ? null : AssembleWikiFileUrl(filename); 
    }

    // Returns the redirect url if the line matches redirectPattern.
    public static URL ParseRedirectUrl(String line) throws Exception {
        String redirect = ParseFilename(redirectPattern, redirectStartPattern, null, redirectEndPattern, line);
        return redirect == null ? null : new URL(AssembleRawWikiUrl(redirect));
    }

    public static boolean isInfoboxStart(String line) {
        return infoboxPattern.matcher(line).matches();
    }

    public static ImageAndDescription getImageAndDescription(Country country, boolean isCoatOfArms) {
        if (isCoatOfArms) {
            if (country.getCoatsOfArms().isEmpty()) {
                country.getCoatsOfArms().add(new ImageAndDescription());
            }
            return country.getCoatsOfArms().get(country.getCoatsOfArms().size() - 1);
         } else {
            return country.getFlag();
         }
    }

    public static String AssembleRawWikiUrl(String wikiItem) {
        return "https://en.wikipedia.org/wiki/" + wikiItem.replaceAll(" ", "_") + "?action=raw";
    }

    public static String AssembleWikiFileUrl(String wikiFile) throws Exception {
        // The image filename already contains the "File:" for a few countries like Libya.
        return (wikiFile.startsWith("File:") ? "https://en.wikipedia.org/wiki/" : "https://en.wikipedia.org/wiki/File:") +
                    wikiFile.replaceAll(" ", "_");
    }

    private static boolean debug = false;
    private static void Debug(String s) {
         if (debug) System.out.println(s);
    }

    private static void DebugP(String s) {
         if (debug) System.out.println("*****" + s + "****\n");
    }

    // Main for testing the parsers.
    public static void main(String[] args) throws Exception {
        Debug(fileNameStartPattern.toString());
        Debug(fileNameEndPattern.toString());

        // DebugP(ParseImageUrl("  | Image = [[File:Flag of the Taliban.svg|255x170px|border]]"));
        DebugP(ParseImageUrl("|image            = Coat_of_Arms_of_the_Russian_Federation.svg"));
        DebugP(ParseImageUrl(
            "|image            = File:Coat of arms of Cyprus (2006).svg|Coat of arms of the Republic of Cyprus (2006–Present) (''alternative version based on the blazon, not used officially by the Republic of Cyprus'')"));
        DebugP(ParseImageUrl("| image            = [[File:Coat_of_arms_of_Guyana.svg|200px]]"));

        Debug(isInfoboxStart("{{Infobox") + "");
        DebugP(ParseImageUrl("| image            = [[File:Coat_of_arms_of_Guyana.svg|200px]]"));
        DebugP(ParseImageUrl("|image=Coat of arms of Vatican City State - 2023 version.svg|"));
        DebugP(ParseImageUrl("|image=Flag of the Taliban.svg"));
     
        DebugP(ParseImageUrl("{{Infobox coat of arms|name=Coat of arms of Vatican City|image=Coat of arms of Vatican City State - 2023 version.svg|image_width=200|year_adopted=7 June 1929|shield=The Fundamental Law of Vatican City State describes the shield as ''chiavi decussate sormontate del Triregno in campo rosso'' (keys in saltire surmounted by the [[papal tiara]] on a red field) and depicts the keys as two, one silver ([[argent]]) in [[bend (heraldry)|bend]] and one gold ([[or (heraldry)|or]]) in [[bend sinister (heraldry)|bend sinister]], interlaced at their intersection with a red ([[gules]]) cord. The tiara is represented as white with golden crowns.<ref name=official>{{cite web|url=http://www.uniroma2.it/didattica/Ecclesiastico/deposito/Leggi_Vaticane.pdf|title=Appendix B (\"All. B. Stemma Ufficiale dello Stato della Città del Vaticano\") of the Fundamental Law of Vatican City State, 7 June 1929|website=uniroma2.it|access-date=7 March 2019|archive-url=https://web.archive.org/web/20131217230421/http://www.uniroma2.it/didattica/Ecclesiastico/deposito/Leggi_Vaticane.pdf|archive-date=17 December 2013|url-status=dead}}</ref>|caption=2023 version of the Vatican City coats of arms}}"));
    
        DebugP(ParseRedirectUrl("#REDIRECT [[National emblem of Azerbaijan#abc]]").toString());

        debug = true;
        System.out.println(ParseImageUrl("|lesser          = "));
    }
}
