package flag;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aux.Constants;

// Reads text files copied from wikipedia pages and parses flag and armorial filenames and states.
// The output is copied to curatedflagsandarmorials.txt
public class ReadFlagsAndArmorials {
    static boolean debug = true;

    public static void main(String[] args) throws Exception {
        BufferedWriter writer;
        if (debug) {
            writer = new BufferedWriter(new FileWriter(Constants.flagDataDirectory + "flags/curatedtest.txt"));
            //read("flags/testflags.txt", writer, "test", Constants.flagsFromFile, false);
            read("flagsofterritories.txt", writer, "Territory flags", Constants.flagsFromFile, true);
        } else {
            writer = new BufferedWriter(new FileWriter(Constants.flagDataDirectory + "flags/curatedtest.txt"));

//            writer = new BufferedWriter(new FileWriter(Constants.dataDirectory + "flags/curatedflagsandarms.txt"));
            read("coatofarms.txt", writer, "Country armorials", Constants.coatsFromFile, true);
            read("countryflags.txt", writer, "Country flags", Constants.flagsFromFile, false);
            read("usflagsandseals.txt", writer, "US flags and seals", Constants.flagsFromFile, false);
            read("flagsofterritories.txt", writer, "Territory flags", Constants.flagsFromFile, false);
            // Alderney, Herm, Sark are fixed manually, the originals cannot be parsed. 
            read("coatofarmsofterritories.txt", writer, "Territory armorials", Constants.coatsFromFile, true);
        }
        writer.close();
    }
    
    public static void read(String filename, BufferedWriter writer, String group, Pattern pattern, boolean fromEOL) throws Exception {
        System.out.println("Reading " + filename);
        writer.append("\n==").append(group).append("==\n");
        System.out.println("==" + group + "==");
        BufferedReader reader = Constants.getBufferedReaderForResource(Constants.flagDataDirectory + filename);

        // state -> {prefix -> filename}
        Map<String, Map<String, String>> imageKeys = new TreeMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            Matcher matcher = pattern.matcher(line);
            // Hack for coatofarmsofterritories.txt.
            if (!matcher.find()) {
                if (line.startsWith("Group")) {
                    String[] tags = line.split("\t");
                    if (tags.length == 2) {
                        System.out.println("==" + tags[1] + "==");
                        writer.append("\n===" + tags[1] + "===\n");
                    }
                }
                // System.out.println("Couldn't parse " + line);
                continue;
            }
            String file = matcher.group("file").replace("_", " ");
            System.out.println("File " + file);
            PrefixAndState prefixAndState = parse(file, line, fromEOL);
            if (prefixAndState == null) {
                System.out.println("Couldn't parse " + line);
                continue;
            }
            String state = prefixAndState.getState();
            String prefix = prefixAndState.getPrefix();
            String fullState = prefixAndState.getFullState();
            String prev =
                imageKeys.computeIfAbsent(state, m -> new TreeMap<String, String>()).put(prefix, file);
            if (prev != null) {
                System.out.println("DUPLICATE " + prefixAndState.getImageKey());
                System.out.println("  CURR " + line);
                System.out.println("  PREV " + prev);
            }
            
            System.out.println(file + "\timageKey [" + prefix + " " + state + "]\t" + fullState);
            writer.append(file)
                    .append("\t").append(prefixAndState.getPrefix())
                    .append("\t").append(prefixAndState.getState())
                    .append("\t").append(prefixAndState.getFullState())
                    .append("\n");
        }
        System.out.println("Done " + filename);
        reader.close();
    }

    private static PrefixAndState parse(String filename, String line, boolean fromEOL) {
        if (fromEOL) {
            return parseUsingPattern(line);
        }
        // Create map for irregular seals like above for states instead of checking them individually.
        PrefixAndState pas = PrefixAndState.findPrefix(filename);
        if (pas == null) {
            if (filename.equals("File:Montana-StateSeal")) {
                return PrefixAndState.assemble("State Seal of ", "Montana");
            } else if (filename.equals("File:Arizona state seal")) {
                return PrefixAndState.assemble("State Seal of ", "Arizona");
            } else if (line.equals("File:Bandera de Bolivia (Estado)")) {
                return PrefixAndState.assemble("Flag of ", "Bolivia (Estado)");
            } else if (filename.equals("File:Wiphala")) {
                return PrefixAndState.assemble("Flag of ", "Wiphala");
            } else {    // parse state from the end of line using prefixFromEOL pattern
                return parseUsingPattern(line);
            }
        } else {
            return pas;
        }
    }

    private static  PrefixAndState parseUsingPattern(String line) {
        PrefixAndState pas = parseUsingPattern(line, Constants.prefixFromEOL1);
        return pas == null ? parseUsingPattern(line, Constants.prefixFromEOL2) : pas;
    }

    private static  PrefixAndState parseUsingPattern(String line, Pattern pattern) {
        Matcher matcher = pattern.matcher(line);
        return matcher.find() ? PrefixAndState.findPrefix(matcher.group("file")) : null;
    }
}