package misc;
// Class for downloading different codes like country codes, currency codes, etc..

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aux.BlockKeeper;
import aux.UrlLineProcessor;
import aux.UrlReader;

// Downloads ISO 4217 for currencies and ISO 3166 for country-codes.
public class DownloadCodes {
    /*
    | id=ALAN data-sort-value=ALAN|{{flagicon|Åland Islands}}&nbsp;[[Åland Islands]]
    |data-sort-value=Aland|[[Åland]]
    | [[#FINL|Finland]]
    | [[ISO 3166-1 alpha-2#AX|{{mono|AX}}]]
    | [[ISO 3166-1 alpha-3#ALA|{{mono|ALA}}]]
    | [[ISO 3166-1 numeric#248|{{mono|248}}]]
    | [[ISO 3166-2:AX]]
    | [[.ax]]

    // Handle "see"!!!!
    | id=CAPE data-sort-value=CAPE colspan=8|{{flagicon|Cape Verde}}&nbsp;[[Cape Verde]] – See [[#CABO|Cabo Verde]].
    // tld can be a list
    | [[.gb]] [[.uk]] {{efn|Al
    */
    private static final String idPrefixString = "\\s*\\|\\s*id=.*";

    private static final String id1String = idPrefixString + "\\[\\[.*\\|(?<value1>[^]^|]+)\\]\\]";
    private static final String id2String = idPrefixString + "\\[\\[(?<value2>[^]^|]+)\\]\\]";
    private static final Pattern idPattern = Pattern.compile(id1String + "|" + id2String);

    private static final Pattern seePattern = Pattern.compile(idPrefixString + ".*See\\s*\\[\\[(?<value>[^]]+)\\]\\]\\.");

    private static final String isoFormat = "\\|\\s*\\[\\[ISO %s#(?<value%s>.+)\\|\\{\\{.*\\]\\]";
    private static final Pattern isoPattern = Pattern.compile(
        String.format(isoFormat, "3166\\-1 alpha\\-2", "1") + "|" +
        String.format(isoFormat, "3166\\-1 alpha\\-3", "2") + "|" +
        String.format(isoFormat, "3166\\-1 numeric", "3"));

    // tld can be a list of tld-s, with this pattern we get them as .gb]] [[.uk etc.
    private static final Pattern internetTLDPattern = Pattern.compile("\\s*\\|\\s*\\[\\[(?<value>[^-]+)\\]\\]");
    
    private static boolean debug = false;
    public static void main(String[] args) throws Exception {
        DownloadCodes downloader = new DownloadCodes();
        if (debug) { test(downloader.new ISO4217LineProcessor()); return; }

        Map<String, CountryInfo> countryInfos = downloader.readCountryCodes();
        Map<String, CurrencyInfo> currencyInfos = downloader.readCurrencies();

        ObjectOutputStream stream = new ObjectOutputStream(new FileOutputStream("resources/countries/codes.ser"));
        stream.writeObject(countryInfos.values().toArray());
        stream.close();
    
        stream = new ObjectOutputStream(new FileOutputStream("resources/countries/currencyinfo.ser"));
        stream.writeObject(currencyInfos.values().toArray());
        stream.close();
    }

    Map<String, CurrencyInfo> readCurrencies() throws Exception {
        final String url = "https://en.wikipedia.org/wiki/ISO_4217";
        UrlReader<Map<String, CurrencyInfo>> urlReader = new UrlReader<>(new ISO4217LineProcessor());
        return urlReader.read(url + "?action=raw");
    }
 
    Map<String, CountryInfo> readCountryCodes() throws Exception {
        final String url = "https://en.wikipedia.org/wiki/List_of_ISO_3166_country_codes";
        UrlReader<Map<String, CountryInfo>> urlReader = new UrlReader<>(new ISO3166LineProcessor());
        return urlReader.read(url + "?action=raw");
    }

    class ISO4217LineProcessor implements UrlLineProcessor<Map<String, CurrencyInfo>> {
        // currencyPattern covers the first 4 fields:
        //      - code
        //      - numeric code (usually the country code if only one country uses the currency)
        //      - number of decimal digits whatever it means
        //      - name of the currency
        //      - list of countries, territories using the currency
        // Currency codes starting wiht X are test and for special currencies like precious metals.
        // Bitcoin still doesn't have a code.
         //| EUR || 978 ||2||[[Euro]] ||{{flag|Åland Islands}} (AX),
        private static final String lineStart = "\\s*\\|\\s*";
        private static final String orSeparator = "\\s*\\|\\|\\s*";
        private static final Pattern currencyPattern =
            Pattern.compile(lineStart + "(?<code>[^ |]+)" + orSeparator + "(?<numericCode>[^ |]*)" + orSeparator +
                            "(?<numDecimalDigits>(\\d|\\.))" + orSeparator +
                             "(" + QualifiedValuesParser.getQualifiedValueString() + ")" + orSeparator);
        private static final String listElementString = "\\{\\{flag\\|(?<%s>[^}]+)\\}\\}";
        private static final Pattern listElementPattern = Pattern.compile(
            String.format(listElementString, "country1") + "\\s*(?<code>[^)]+)\\)" + "|" +
            String.format(listElementString, "country2"));

        private Map<String, CurrencyInfo> currencyInfos = new TreeMap<>();
        private BlockKeeper bk = new BlockKeeper();
        private boolean done = false;
        
        @Override
        public URL processLine(String line) throws Exception {
            if (line.startsWith("=== Historical codes ")) {
                done = true;
                return null;
            }
            line = bk.update(line);
            Matcher matcher = currencyPattern.matcher(line);
            if (matcher.find()) {
                CurrencyInfo ci = new CurrencyInfo();
                ci.getCurrency().setCode(matcher.group("code"));
                String numericCodeString = matcher.group("numericCode");
                if (numericCodeString != null) {
                    ci.getCurrency().setNumericCode(Integer.parseInt(numericCodeString));
                } else {
                    System.out.println("NumericCode not found in " + line);
                }
                if (matcher.group("qualifiedValue") != null) {
                    ci.getCurrency().setCurrency(matcher.group("qualifiedValue"));
                    ci.getCurrency().setQualifier(matcher.group("qualifier"));
                } else {
                    ci.getCurrency().setCurrency(matcher.group("value"));
                }
                line = line.substring(matcher.end());
                matcher = listElementPattern.matcher(line);
                while (matcher.find()) {
                    ci.getCountries().add(matcher.group(matcher.group("country1") == null ? "country2" : "country1"));
                }
                currencyInfos.put(ci.getCurrency().getCode(), ci);
                System.out.println("Stored " + ci);
            }
            return null;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public Map<String, CurrencyInfo> done() throws Exception {
            return currencyInfos;
        }
    }
    
    class ISO3166LineProcessor implements UrlLineProcessor<Map<String, CountryInfo>> {
        enum State {
            ID,     // waiting for idPattern
            SOV,    // waiting for sovereignity
            ISO1,   // waiting for isoPattern value1
            ISO2,   // waiting for isoPattern value3
            ISO3,   // waiting for isoPattern value3
            ISO_SKIP,  // waiting for line to skip
            TLD,       // waiting for line to skip
            SKIP,      // skip one line
        }

        private Map<String, CountryInfo> countryInfos = new TreeMap<>();
        private BlockKeeper bk = new BlockKeeper();
        private State state = State.ID;
        private CountryInfo countryInfo = null;

        @Override
        public URL processLine(String line) throws Exception {
            line = bk.update(line);
            Matcher matcher;
            switch (state) {
                case ID:
                    matcher = seePattern.matcher(line);
                    if (matcher.find()) {
                        System.out.println("Found see in line " + line);
                        break;
                    }
                    matcher = idPattern.matcher(line);
                    if (matcher.find()) {
                        store();
                        countryInfo = new CountryInfo();
                        countryInfo.setName(value(matcher, "value1", "value2"));
                        state = State.SKIP;
                    }
                    break;
                case SKIP:
                    state = State.SOV;
                    break;
                case SOV:
                    countryInfo.setSovereignty(line);
                    System.out.println("SOV " + line);
                    state = State.ISO1;
                    break;
                case ISO1:
                    matcher = isoPattern.matcher(line);
                    if (matcher.find()) {
                        countryInfo.setIso1(matcher.group("value1"));
                        state = State.ISO2;
                    }
                    break;
                case ISO2:
                    matcher = isoPattern.matcher(line);
                    if (matcher.find()) {
                        countryInfo.setIso2(matcher.group("value2"));
                        state = State.ISO3;
                    }
                    break;
                case ISO3:
                    matcher = isoPattern.matcher(line);
                    if (matcher.find()) {
                        countryInfo.setIso3(Integer.parseInt(matcher.group("value3")));
                        state = State.TLD;
                    }
                    break;
                case ISO_SKIP:
                    state = State.TLD;
                    break;
                case TLD:
                    final Pattern emptyLinePattern = Pattern.compile("\\s*\\|\\s*");
                    // This is important because tld can be a note ({{efn...) like for Western Sahara)
                    matcher = emptyLinePattern.matcher(line);
                    if (matcher.matches()) {
                        state = State.ID;
                        break;
                    }
                    matcher = internetTLDPattern.matcher(line);
                    if (matcher.find()) {
                        countryInfo.setInternetTLD(matcher.group("value"));
                        state = State.ID;
                    }
                    break;
                default:
                    break;
            };
            return null;
        }

        private String value(Matcher matcher, String ... values) {
            for (String s : values) {
                String value = matcher.group(s);
                if (value != null) return value;
            }
            return null;
        }

        // Puts countryInfo to countryInfos, complain if there are multiple ones having the same id.
        private void store() {
            if (countryInfo == null) return;
            CountryInfo old = countryInfos.get(countryInfo.getName());
            if (old != null) {
                System.out.println("There are multiple infos for " + countryInfo.getName() + ", keep the first one.");
            } else {
                countryInfos.put(countryInfo.getName(), countryInfo);
                System.out.println("Stored " + countryInfo.getName());
            }
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Map<String, CountryInfo> done() throws Exception {
            store();
            System.out.println("Stored " + countryInfos.size() + " country infos.");
            return countryInfos;
        }
    }

    static void test(UrlLineProcessor<Map<String, CurrencyInfo>> processor) throws Exception {
        BufferedReader reader = new BufferedReader(new FileReader("src/s.txt"));
        String line;
        while ((line = reader.readLine()) != null) {
            processor.processLine(line);
        }
        Collection<CurrencyInfo> currencyInfos = processor.done().values();
        for (CurrencyInfo ci : currencyInfos) {
            System.out.println(ci);
        }
        reader.close();      
    }
}    
