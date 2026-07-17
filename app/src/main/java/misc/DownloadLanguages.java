package misc;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.ObjectInputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aux.BlockKeeper;
import aux.UrlLineProcessor;
import aux.UrlReader;

// Read countryinfos, scan all the languages and build language hierarchy.
// Infobox familycolor plain, fami [[x languages| x]], for fam1 x should be familycolor
public class DownloadLanguages {
    // language, languages or nothing
    static final String[] languageSuffixes = new String[] {"", "language", "language"};
    static final String wikiUrlFormat = "https://en.wikipedia.org/wiki/%s%s?action=raw";
    static final Pattern languagePattern = Pattern.compile("(?<language>[^|]+)");

    public static void main(String[] args) throws Exception {
        ObjectInputStream in = new ObjectInputStream(new FileInputStream("resources/countries/countryinfo.ser"));
        Object[] objects = (Object[]) in.readObject();
        in.close();
        DownloadLanguages dl = new DownloadLanguages();
        for (Object object : objects) {
            CountryInfo ci = ((Country)object).getCountryInfo();
            dl.downloadLanguages("language", ci.getName(), ci.getLanguages());
            dl.downloadLanguages("official_languages", ci.getName(), ci.getOfficialLanguages());
            dl.downloadLanguages("national_languages", ci.getName(), ci.getNationalLanguages());
        }
    }
    
    private static Map<String, Language> languagesByName = new TreeMap<>();
    private BlockKeeper blockKeeper = new BlockKeeper();

    boolean debug = false;
    // language, languages or nothing
    void downloadLanguages(String type, String countryName, List<QualifiedValue> languages) throws Exception {
        if (debug) {
            if (countryName.indexOf("Argentina") == -1) return;
        }
        if (languages.isEmpty()) return;
        System.out.println("DOWNLOAD " + type);
        for (QualifiedValue qv : languages) {
            System.out.println("   qv " + qv);
            String languageName = blockKeeper.update(qv.getValue()); // for removing <...>
            Matcher matcher = languagePattern.matcher(languageName);
            if (!matcher.find()) {
                System.out.println("languagePattern doesn't match >" + languageName + "<");
                continue;
            }
            languageName = matcher.group("language");
            if (languageName.indexOf("#") != -1) {
                System.out.println("# in languageName, skip it");
                return;
            }
            Language language = languagesByName.get(languageName);
            if (language != null) {
                language.getCountries().add(countryName);
                return;
            }
            languagesByName.put(languageName, new Language());

            boolean done = false;
            for (int i = 0; i < languageSuffixes.length; ++i) {
                String url = "";
                try {
                    url = String.format(wikiUrlFormat, languageName, languageSuffixes[i]).replaceAll(" ", "_");
                    if (url.indexOf('%') != -1) {
                        System.out.println(countryName + ": " + languageName);
                    }
                    UrlReader<Language> reader = new UrlReader<>(new LanguageLineProcessor());
                    languagesByName.put(languageName, reader.read(url));
                    System.out.println("DONE " + url);
                    done = true;
                    break;
                } catch (FileNotFoundException f) {}
            }
            if (!done) {
                System.out.println("FAILED " + languageName);
            }
        }
    }
}

class LanguageLineProcessor implements UrlLineProcessor<Language>, ListParserListener {
    // Check if ValueParser cn be modified to handle this.
    // | fam2          = [[Italic languages|Italic]]
    // | ancestor      = [[Old Latin]]
    // | ancestor2     = [[Classical Latin]]
    // | familycolor   = Indo-European
    // | child1      = [[Proto-Albanian language|Proto-Albanian]]
    private static final Pattern languageAttributePattern = Pattern.compile("familycolor|fam.*|ancestor.*|child.*|dia.*");

    private BlockKeeper blockKeeper = new BlockKeeper();
    private ListParser listParser = new ListParser(blockKeeper, this);
    private Language language = new Language();

    private Map<String, List<QualifiedValue>> languageAttributes = new TreeMap<>();
    private boolean infobox = false;
    private boolean done = false;

    @Override
    public URL processLine(String line) throws Exception {
        line = blockKeeper.update(line);
        if (blockKeeper.inTheFirstAppearanceOf("infobox")) {
            infobox = true;
            listParser.parseLine(line);
        } else if (infobox) {
            done = true;
        }
        return null;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public Language done() throws Exception {
        listParser.parseDone();
        System.out.println("LIST done");
        for (Map.Entry<String, List<QualifiedValue>> e : languageAttributes.entrySet()) {
            System.out.println("    " + e.getKey() + ", " + e.getValue().size());
            for (QualifiedValue qv : e.getValue()) {
                System.out.println("      " + qv);
            }
        }
        return language;
    }

    @Override
    public boolean parseType(String type) {
        return languageAttributePattern.matcher(type).find();
    }

    @Override
    public void parseTypeDone(String type, List<QualifiedValue> items) {
        List<QualifiedValue> attributes = languageAttributes.get(type);
        if (attributes == null) {
            attributes = new ArrayList<QualifiedValue>();
            languageAttributes.put(type, attributes);
        }
        attributes.addAll(items);
    }
}
