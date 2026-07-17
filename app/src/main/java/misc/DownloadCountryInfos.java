package misc;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import aux.BlockKeeper;
import aux.UrlLineProcessor;
import aux.UrlReader;
 
// Try https://en.wikipedia.org//w/api.php?action=query&format=json&prop=revisions&titles=India&formatversion=2&rvprop=content&rvslots=*
// end see the resulting json.
// Class for downloading countries enlisted in https://en.wikipedia.org/wiki/List_of_sovereign_states.
// Countries are in lines of form "></span>'''{{flag|X}}''' X being the country name. The link is "https://en.wikipedia.org/wiki/X".
// Look for {{Infobox country.
public class DownloadCountryInfos {
    static boolean debug = false;

    public static void main(String[] args) throws Exception {
        if (debug) {
            BufferedReader reader = new BufferedReader(new FileReader("src/s.txt"));
            CountryLineProcessor clp = new CountryLineProcessor();
            String line;
            while ((line = reader.readLine()) != null) {
                clp.processLine(line);
            }
            reader.close();
        } else {
            final String url = "https://en.wikipedia.org/wiki/List_of_sovereign_states";
            UrlReader<Collection<Country>> urlReader = new UrlReader<Collection<Country>>(new CountryListLineProcessor());
            Collection<Country> countries = urlReader.read(url + "?action=raw");
            System.out.println("Store " + countries.size() + " countries.");
            ObjectOutputStream stream = new ObjectOutputStream(
                new FileOutputStream("/Users/gyorgygyepesi/vsprojects/quiz/resources/countries/countryinfo.ser"));
            stream.writeObject(countries.toArray());
            stream.close();
        }
    }
}

class CountryLineProcessor implements UrlLineProcessor<Country>, ListParserListener {
    private static final QualifiedValuesParser currencyParser =
        new QualifiedValuesParser("currency", false);
    private static final ValueParser callingCodeParser = new ValueParser("calling_code");
    private static final ValueParser languageTypeParser = new ValueParser("language_type");

    private final BlockKeeper blockKeeper = new BlockKeeper();
    private ListParser listParser;
    private Country country = new Country();
    private Map<String, List<QualifiedValue>> itemsByType = new TreeMap<>();
    private boolean done = false;

    public CountryLineProcessor() {
        reset();
    }

    private void reset() {
        country = new Country();
        itemsByType.put("capital", country.getCountryInfo().getCapitals());
        itemsByType.put("capital_exile", country.getCountryInfo().getCapitalsExile());
        itemsByType.put("official_languages", country.getCountryInfo().getOfficialLanguages());
        itemsByType.put("national_languages", country.getCountryInfo().getNationalLanguages());
        itemsByType.put("language", country.getCountryInfo().getLanguages());
        listParser = new ListParser(blockKeeper, this);    
    }

    @Override
    public boolean parseType(String type) {
        return itemsByType.containsKey(type);
    }

    @Override
    public void parseTypeDone(String type, List<QualifiedValue> items) {
        itemsByType.get(type).addAll(items);
    }    
 
    // GraphBuilders for coats of arms, flags, same official currency, language, etc.)
    @Override
    public URL processLine(String line) throws Exception {
        URL redirectUrl = TextParser.ParseRedirectUrl(line);
        if (redirectUrl != null) {
            // Skip redirect url if it contains an internal reference.
            if (redirectUrl.toString().indexOf('#') != -1) {
                System.out.println("# in redirect url " + line);
                done = true;
                country = null;
                return null;
            }
            reset();
            return redirectUrl;
        }

        // Bosnia_Herzegovina, India and Saint Kitts and Nevis have unbalanced blocks.
        line = blockKeeper.update(line);

        // If not in infobox then parse categories.
        if (!blockKeeper.inTheFirstAppearanceOf("infobox")) {
            String category = TextParser.parseCategory(line);
            if (category != null) {
                country.getCountryInfo().getCategories().add(category);
            }
            return null;
        }
        // Parse capital, currency, languges, etc..
        // Single values.
        String value;
        //country code might be missing as for Equatorial Guinea
        if ((value = callingCodeParser.parseLine(line))!= null) country.getCountryInfo().setCallingCode(value);
        if ((value = languageTypeParser.parseLine(line))!= null) System.out.println("TYPE " + value);

        for (QualifiedValue qualifiedCurrency : currencyParser.parseLine(line)) {
            country.getCountryInfo().getCurrencies().add(new Currency(qualifiedCurrency.getValue(), qualifiedCurrency.getQualifier()));
        }

        listParser.parseLine(line);
        return null;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public Country done() throws Exception {
        listParser.parseDone();
        for (Map.Entry<String, List<QualifiedValue>> e : itemsByType.entrySet()) {
            System.out.println("  " + e.getKey());
            for (QualifiedValue qv : e.getValue()) {
                System.out.println("    " + qv);
            }
        }
        System.out.println("   Processed " + blockKeeper.getLineNumber() + " lines");
        blockKeeper.printStack();
        return country;
    }
} 

class CountryListLineProcessor implements UrlLineProcessor<Collection<Country>> {
    private static final String countryPrefix = "|<span id=\""; // "></span>'''{{flag|";
    private static final String[] countrySuffixes = new String[] {"\""};//"|", "}}"};
    // For Georgia, Micronesia and Palestine.
    private static final Map<String, String> exceptionalCountryPages = new TreeMap<>();
    static {
        exceptionalCountryPages.put("Georgia", "Georgia country");
        exceptionalCountryPages.put("Micronesia", "Federated States of Micronesia");
        exceptionalCountryPages.put("Palestine", "State of Palestine");
    }

    private Map<String, Country> countries = new TreeMap<>();
    private boolean debug = false;

    @Override
    public URL processLine(String line) throws Exception {
        int start = line.indexOf(countryPrefix);
        if (start == -1) return null;

        line = line.substring(start + countryPrefix.length());
        int end = -1;
        for (int i = 0; i < countrySuffixes.length; ++i) {
            end = line.indexOf(countrySuffixes[i]);
            if (end != -1) {
                break;
            };
        }
        if (end == -1) {
            System.out.println("No country in " + line);
            return null;
        }
        String countryName = line.substring(0, end);

        if (debug) {
            String[] toDebug = new String[] {"Mali"}; //, "Belarus", "Austria", "Ecuador", "Africa", "Andorra"};
            boolean found = false;
            for (int i = 0; i < toDebug.length; ++i) {
                if (countryName.indexOf(toDebug[i]) != -1) {
                    found = true;
                    break;
                }
            }
            if (!found) return null;
        }

        System.out.println("Country " + countryName);

        String countryFileName = exceptionalCountryPages.get(countryName);
        if (countryFileName != null) countryName = countryFileName;

        String url = TextParser.AssembleRawWikiUrl(countryName);

        UrlReader<Country> urlReader = new UrlReader<Country>(new CountryLineProcessor());
        Country country = urlReader.read(url);
        if (country != null) {
            country.getCountryInfo().setName(countryName);
            countries.put(countryName, country);
            CountryInfo ci = country.getCountryInfo();
            System.out.println("Store " + ci.getName());
        } else {
            System.out.println("No country (redirect is an internal reference) for " + countryName);
        }
        return null;
    }

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public Collection<Country> done() throws Exception {
        return countries.values();
    }
}
