package misc;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.net.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aux.BlockKeeper;
import aux.Triplet;
import aux.UrlLineProcessor;
import aux.UrlReader;

// Downloads information about coat-of-arms from the list of coat-arms wiki page and writes them as an array of
// Country objects to the specified file.
public class DownloadFlagsAndCoatsOfArms {
    static final boolean debug = false;
    static final boolean isCoatOfArms = true;

    private static final String[] defaultArgs = new String[] {
        "https://en.wikipedia.org/wiki/Armorial_of_sovereign_states",
        "resources/countries/coat_of_arms/coat_of_arms.ser",
        "https://en.wikipedia.org/wiki/Gallery_of_sovereign_state_flags",
        "resources/countries/coat_of_arms/flags.ser"};

    public static void main(String[] args) throws Exception {
        if (args.length == 0) args = defaultArgs;
        UrlReader<Collection<Country>> urlReader = new UrlReader<Collection<Country>>(new CoatOfArmsListProcessor());
        Collection<Country> countries = urlReader.read(args[isCoatOfArms ? 0 : 2] + "?action=raw");
        for (Country country : countries) {
            String categories = "";
            for (String category : TextParser.getImageAndDescription(country, isCoatOfArms).getCategories()) {
                categories += " [" + category + "]";
            }
            System.out.println(country.getCountryInfo().getName() + categories + ", " +
                                TextParser.getImageAndDescription(country, isCoatOfArms).getDescription());
        }
        System.out.println("There are " + countries.size() + " countries listed above.");
        if (debug) return;
        ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream(args[isCoatOfArms ? 1 : 3]));
        stream.writeObject(countries.toArray());
        stream.close();
    }

    public static void main1(String[] args) throws Exception {
        processLines(
            "|File:Royal coat of arms of Denmark.svg|{{center|Greater royal coat of arms of Denmark}}",
            "|File:National Coat of arms of Denmark.svg|{{center|[[Coat of arms of Denmark]]}}",
            "|File:Great Seal of the United States (obverse).svg|{{center|[[Great Seal of the United States|Great Seal of the United States]] (obverse)}}",
            "|File:Great Seal of the United States (reverse).svg|{{center|Great Seal of the United States (reverse)}}"
            );
    }

    public static void processLines(String ... lines) throws Exception {
        CoatOfArmsListProcessor processor = new CoatOfArmsListProcessor();
        for (String line : lines) {
            processor.processLine(line);
        }
        Collection<Country> countries = processor.done();
        for (Country country : countries) {
            System.out.println(country.getCountryInfo().getName());
            for (ImageAndDescription imad : country.getCoatsOfArms()) {
                System.out.println("    image " + imad.getImageUrl());
            }
        }
    }
}

class CoatOfArmsListProcessor implements UrlLineProcessor<Collection<Country>> {
    // Assumes that the line comes from the raw file (https://en.wikipedia.org/w/index.php?title=Armorial_of_sovereign_states&action=raw).
    // Redirect is not expected - return null.
    // File:Flag of Barbados.svg|[[Flag of Barbados|Barbados]]
    // |File:Emblem of Algeria.svg|[[Emblem of Algeria]]
    // |File:Coat of arms of Albania.svg|[[Coat of arms of Albania]]
    // |File:Great Seal of the United States (reverse).svg|Great seal of the United States (reverse)
    // |File:Coat of arms of Sweden (shield and chain).svg|Lesser coat of arms of Sweden
    // So the pattern is
    private static final String[] coatOfArmsAndFlagTypes = new String[] {
        "flag of ", "armorial of ", "coat of arms of ", "diplomatic emblem of ",
        "emblem of ", "government seal of ", "great seal of ", "greater coat of arms of ",
        "imperial seal of ", "lesser coat of arms of ", "national emblem of ", "national seal of ",
        "royal arms of ", "greater royal coat of arms of ", "seal of ", "state emblem of ", "state seal of ", ""};
    private static final Pattern svgAndTitlePattern;
    // Cannot require closing ]] because Vatican City.
    private static final Pattern fileNamePattern = Pattern.compile("(\\[\\[(?<filename>[^(\\|\\])]+)[^]]*)|(?<all>[^(\\[\\])]*)");
    private static final Pattern namePattern;

    static {
        String file0 = "\\|File:\\s*";
        String file1 = "File:\\s*";
        String svgGroupFormat = "(?<svg%s>%s[^.]*\\.svg)\\|(?<title%s>.+)";
        String patternString = "(";
        String nameGroupFormat = "(?<name%s>[^.]*)";
        String namePatternString = "(";

        int l = coatOfArmsAndFlagTypes.length;
        for (int i = 0; i < l; ++i) {
            if (i > 0) {
                patternString += "|";
                namePatternString += "|";
            }
            patternString += "(" + file0 + String.format(svgGroupFormat, "" + i, coatOfArmsAndFlagTypes[i] + "the ", "" + i) + ")|" + 
                             "(" + file1 + String.format(svgGroupFormat, "" + (i + l), coatOfArmsAndFlagTypes[i], "" + (i + l)) + ")";
            namePatternString += "(" + coatOfArmsAndFlagTypes[i] + "the " + String.format(nameGroupFormat, "" + i, "" + i) + ")|" + 
                                 "(" + coatOfArmsAndFlagTypes[i] + String.format(nameGroupFormat, "" + (i + l), "" + (i + l)) + ")";
        }
        patternString += ")";
        namePatternString += ")";
        
        svgAndTitlePattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
        namePattern = Pattern.compile(namePatternString, Pattern.CASE_INSENSITIVE);
    }
 
    private final BlockKeeper blockKeeper = new BlockKeeper();
    private Map<String, Country> countriesByName = new TreeMap<String, Country>();
    private int entryCount = 0;
    private Map<String, Triplet<String, String, String>> storedNameFilenameAndSvgs = new TreeMap<>();
    private List<Triplet<String, String, String>> pendingNameFilenameAndSvgs = new ArrayList<>();

    public URL processLine(String line) throws Exception {
        line = blockKeeper.update(line);
        Triplet<String, String, String> nameFileNameAndSvg = parseNameFileNameAndSvg(line);
        if (nameFileNameAndSvg == null) return null;

        System.out.println("    nameFileNameAndSvg " + nameFileNameAndSvg);

        boolean debug = false;
        if (debug) {
            if (!nameFileNameAndSvg.getX().contains("Liech")) return null;
        }
        
        // if url is not valid AND name of the country is the same as the previous one then use the previous url.
        // See United States, the second is with lowercase s in Seal. United States got fixed in the meantime.
        Country country = readSvgAndFilename(nameFileNameAndSvg, true);
        if (country == null) return null;
     
        if (TextParser.getImageAndDescription(country, DownloadFlagsAndCoatsOfArms.isCoatOfArms).getImageUrl() == null) {
            System.out.println("No imageUrl for list line " + line);
            return null;
        }

        store(country, nameFileNameAndSvg);
        return null;
    }

    public boolean isDone() {
        return false;
    }

    private String findOrFail(String line, Pattern pattern, String ... groups) throws Exception {
        Matcher matcher = pattern.matcher(line);
        if (!matcher.find()) {
            throw new Exception("Pattern " + pattern + " has no match in " + line);
        }
        return findOrFail(matcher, groups);
    }

    private String findOrFail(Matcher matcher, String ... groups) throws Exception {
        for (String group : groups) {
            String value;
            if ((value = matcher.group(group)) != null) return value;
        }
        throw new Exception("Groups " + groups + " not found.");
    }

    // Returns <name, fileName, expected svgFileName>
    private Triplet<String, String, String> parseNameFileNameAndSvg(String line) throws Exception {
        Matcher matcher = svgAndTitlePattern.matcher(line);
        if (!matcher.find()) return null;
    
        String fileName = null;
        String svg = null;
        for (int i = 0; i < coatOfArmsAndFlagTypes.length * 2; ++i) {
            svg = matcher.group("svg" + i);
            if (svg == null) continue;

            String title = findOrFail(matcher, "title" + i);
            // Parse fileName from title.
            fileName = findOrFail(title, fileNamePattern, "filename", "all");
            // No coat-of-arms type.
            if (i == coatOfArmsAndFlagTypes.length - 1) {
                System.out.println("No coat-of-arms type in svg " + line);
            }
            break;
        }
        if (svg == null) throw new Exception("Group svg* is null " + line);

        for (int i = 0; i < coatOfArmsAndFlagTypes.length * 2; ++i) {
            matcher = namePattern.matcher(fileName);
            if (!matcher.find()) continue;
            String name = matcher.group("name" + i);
            if (name != null) return new Triplet<String, String, String>(name.trim(), fileName.trim(), svg.trim());
        }
        throw new Exception("Group name* is null " + line);
    }

    private Country readSvgAndFilename(Triplet<String, String, String> nameFilenameAndSvg, boolean rememberIfFails) throws Exception {
        String svg = nameFilenameAndSvg.getZ();
        String filename = nameFilenameAndSvg.getY();
        String url = TextParser.AssembleRawWikiUrl(filename);
        if (url.indexOf('#') != -1) {
            // # could be handled separately: chop it and drop the lines resulting page until the line pointed by the url after #.
            System.out.println("# in url " + filename);
            return null;
        }
        UrlReader<Country> urlReader = new UrlReader<Country>(new CoatOfArmsProcessor(nameFilenameAndSvg.getX(), filename, svg));
        try {
            return urlReader.read(url);
        } catch (FileNotFoundException e) {
            if (rememberIfFails) {
                pendingNameFilenameAndSvgs.add(nameFilenameAndSvg);
                return null;
            } else {
                throw e;
            }
        }
    }

    private void store(Country country, Triplet<String, String, String> nameFilenameAndSvg) {
        String name = country.getCountryInfo().getName();
        Country old = countriesByName.get(name);
        if (old != null) {
            ImageAndDescription coatOfArms = TextParser.getImageAndDescription(country, DownloadFlagsAndCoatsOfArms.isCoatOfArms);
            old.getCoatsOfArms().add(coatOfArms);
        } else {
            ++entryCount;
            countriesByName.put(name, country);
            if (nameFilenameAndSvg != null) {
                storedNameFilenameAndSvgs.put(name, nameFilenameAndSvg);
            }
        }
     
    }

    public Collection<Country> done() throws Exception {
        System.out.println("Resolve " + pendingNameFilenameAndSvgs.size() + " pending coats of arms");
        for (Triplet<String, String, String> pendingNameFilenameAndSvg : pendingNameFilenameAndSvgs) {
            Triplet<String, String, String> storedNameFilenameAndSvg = storedNameFilenameAndSvgs.get(pendingNameFilenameAndSvg.getX());
            if (storedNameFilenameAndSvg == null) {
                throw new FileNotFoundException("NostoredNameFilenameAndSvg for " +  pendingNameFilenameAndSvg.toString());
            }
            pendingNameFilenameAndSvg.setY(storedNameFilenameAndSvg.getY());
            System.out.println();
            store(readSvgAndFilename(pendingNameFilenameAndSvg, false), null);
        }
    
        System.out.println(entryCount);
        return countriesByName.values();
    }
}

// For the per-country coat-of-arms pages the raw version is used (https://en.wikipedia.org/w/index.php?title=coat_of_arms_name&action=raw,
// where coat_of_arms_name is the one parsed from the list-of-coat-of-arms wiki page).
// The name and image are specified as follows
// |name             = National Emblem of the People's Republic of China
// |image            = National Emblem of the People's Republic of China (2).svg
// The description is parsed from the "Infobox for coat of arms". If there is no such block (Government_Seal_of_Bangladesh for example)
// then skip the coat of arms.
// The structure of flags is the same, only the infobox start is different.
class CoatOfArmsProcessor implements UrlLineProcessor<Country> {
    private final BlockKeeper blockKeeper = new BlockKeeper();
    private Country country = new Country();
    private ImageAndDescription imageAndDescription;
 
    private String description = new String();
 
    private final String expectedImageUrl;
    // Set to true when expectedImageUrl is found. There might be multiple images for older versions of the coat-of-arms.
    private boolean imageFound = false;
    // Set to true at the beginning of infobox after expectedImageUrl and before the next imageUrl.
    private boolean inDescription = false;
    // Set to true when at the end of the infobox.
    private boolean done = false;
    boolean ii = false;
  
    public CoatOfArmsProcessor(String name, String filename, String expectedImageUrl) throws Exception {
        this.expectedImageUrl = TextParser.AssembleWikiFileUrl(expectedImageUrl);
        System.out.println("Process country : " + name + ", file " + filename + ", svg " + expectedImageUrl);
        imageAndDescription = TextParser.getImageAndDescription(country, DownloadFlagsAndCoatsOfArms.isCoatOfArms);
        country.getCountryInfo().setName(name);
    }

    public boolean isDone() {
      return done;
    }

    // inDescription is true if we are in infobox after expectedImageUrl and before the next image Url.
    public URL processLine(String line) throws Exception {
        line = blockKeeper.update(line);

        // If we are in the first infobox.
        boolean inInfobox = blockKeeper.inTheFirstAppearanceOf("infobox");
        if (inInfobox) {
            // See if line contains expectedImageUrl, set inDescription and imageFound to true if yes.
            String imageUrl = TextParser.ParseImageUrl(line);
            if (imageUrl != null) {
                if (imageFound) {
                    imageFound = false;
                    inDescription = false;
                } else if (imageUrl.equals(expectedImageUrl)) {
                    ii = true;
                    imageFound = true;
                    inDescription = true;
                }
            }
        }
 
        if (inDescription) {
            description = description + "\n" + line;
            if (!inInfobox) {
                inDescription = false;
            }
            return null;
        }
 
        URL redirectUrl = TextParser.ParseRedirectUrl(line);
        if (redirectUrl != null) {
            // Skip redirect url if it contains an internal reference.
            if (redirectUrl.toString().indexOf('#') != -1) {
                System.out.println("# in url " + line);
                done = true;
                country = null;
                return null;
            }
            // Clear this processor for processing redirect url.          
            done = false;
            imageFound = false;
            inDescription = false;
            description = "";
            String name = country.getCountryInfo().getName();
            country = new Country();
            imageAndDescription = TextParser.getImageAndDescription(country, DownloadFlagsAndCoatsOfArms.isCoatOfArms);
            country.getCountryInfo().setName(name);
            return redirectUrl;
        }

        String category = TextParser.parseCategory(line);
        if (category != null) {
            imageAndDescription.getCategories().add(category);
        }
        return null;
    }

    public Country done() throws Exception {
        if (country != null) {
            if (!ii) System.out.println("    EXPECTED imageUrl " + expectedImageUrl + " NOT found");
            imageAndDescription.setDescription(description);
            imageAndDescription.setImageUrl(new URL(expectedImageUrl));
        }
        return country;
    }
}
