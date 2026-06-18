package aux;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;
import java.util.regex.Pattern;

public class Constants {
    public static final String wiki = "https://en.wikipedia.org/wiki/";

    public static final String flagDirectory = "flag/";
    public static final String nobelDirectory = "nobel/";
    public static final String nobelPortraitsDirectory = nobelDirectory + "portraits/";

    public static final String flagDataDirectory = "flag/txt/";
    public static final String flagSvgDirectory = "flag/svg/";
    public static final String groupDirectory = flagDataDirectory + "flaggroups/";
    public static final String logoDirectory = flagDirectory + "logos/";
    public static final String logoSvgDirectory = logoDirectory + "svg/";
    public static final String languageDirectory = "language/";
    public static final String mythologyDir = "mythology/";

    public static final String dataDirectory = "data/";
    public static final String languageDataDirectory = dataDirectory + "language/";
    public static final String oscarDataDirectory = dataDirectory + "oscar/";
    public static final String wikidataDataDirectory = dataDirectory + "wikidata/";
    public static final String constellationsDataDirectory = wikidataDataDirectory + "constellations/";

    public static float fontSizeMultiplier = 1.5F;

    // For https://en.wikipedia.org/wiki/Gallery_of_flags_of_dependent_territories?action=raw
    public static final Pattern flagAlias = Pattern.compile("\\{\\{getalias2\\|(.+)\\|flag alias", Pattern.CASE_INSENSITIVE);
    // [^\\.]+ doesn't work for "Georgia (U. S. state)")
    public static final Pattern flagsFromFile = Pattern.compile("\\|\\[\\[(?<file>[^\\[]+)\\.svg.*\\]\\]", Pattern.CASE_INSENSITIVE); 
    public static final Pattern coatsFromFile = Pattern.compile("\\|(?<file>[^\\.]+)\\.svg.*\\|", Pattern.CASE_INSENSITIVE);
    // Filename for coat-of-arms might not start with a coat-of-arms prefix; 
    public static final Pattern prefixFromEOL1 =
        Pattern.compile("\\|\\[\\[(?<file>[^\\|^\\]^\\#]+).*\\]\\]", Pattern.CASE_INSENSITIVE);
    public static final Pattern prefixFromEOL2 =
        Pattern.compile("\\|\\{\\{center\\|(?<file>[^\\|^\\}^\\#]+).*\\}\\}", Pattern.CASE_INSENSITIVE);
    // No coat of arms for Baker Island, Bouvet Island, Christmas Island etc.
    static  Set<String> notHavingCoatOfArms = Set.of(
        "Baker Island", "Bouvet Island", "Christmas Island", "Clipperton Island",
       "Australian Antarctic Territory", "Ashmore and Cartier Islands", "Cocos (Keeling) Islands",
       "Coral Sea Islands", "Saint Martin", "Svalbard", "Wake Island", "Wiphala", "Taliban");
    
    static final String[] flagAndCoatOfArmsPrefixes = 
        new String[] {"Armoiries ", "Proposed flag of ", "Flag of ", "Seal of ", "Royal coat of arms of ", "Coat of arms of ",
                    "Badge of ", "State arms of ", "National Emblem of ", "Emblem of ", "Royal Arms of ", "Arms of ",
                    "Bandera de ", "Bandera ", "Escudo de ", "War Ensign of ", "Naval sign of "};
    static final String[] prefixPrefixes = new String[] {"Greater ", "Lesser ", "Great ", ""};
    static final String[] prefixSuffixes = new String[] {"the state of ", "the state ", "the ", ""};
    
    static String[] flagVersions =
        new String[] {"\\(reverse\\)", "\\(Reverse\\)", "\\(state\\)", "\\(unofficial\\)", "\\(Unofficial\\)"};

    private static String svgDirectory = null;

    public static void setSvgDirectory(String svg) {
        svgDirectory = svg;
    }

    public static String getSvgDirectory() {
        return svgDirectory;
    }

    public static float getFontSizeMultiplier() {
        return fontSizeMultiplier;
    }

    public static void setFontSizeMultiplier(float multiplier) {
        fontSizeMultiplier = multiplier;
    }

    public static BufferedReader getBufferedReaderForResource(String resource) {
        return new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(Constants.class.getClassLoader().getResourceAsStream(resource))));
    }
}
