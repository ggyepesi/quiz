package misc;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import aux.BlockKeeper;

public class Codes {
    public static void main(String[] args) throws Exception {
        ObjectInputStream in = new ObjectInputStream(new FileInputStream("resources/countries/codes.ser"));
        Object[] objects = (Object[]) in.readObject();
        printSovereignties(objects);

        in.close();
    }

    // | UN member state
    // | UN observer state
    // | [[#AUS1|Australia]]
    // | [[Antarctic Treaty System|Antarctic Treaty]]
    // | {{nowrap|[[Member states of the United Nations|UN member state]]}}
    // strip <!--DO NOT CHANGE--> (Province of China)<!-- -->, see BlockKeeper
    private static final Pattern sovereigntyPattern = Pattern.compile(".*\\[\\[[^|]*\\|(?<sovereignty>[^]]+)\\]\\]");

    static String parseSovereignty(String s) throws Exception {
        BlockKeeper bk = new BlockKeeper();
        s = bk.update(s); // for removing <!-- ... -->
        if (s.startsWith("| ")) s = s.substring(2);
        Matcher matcher = sovereigntyPattern.matcher(s);
        if (matcher.find()) {
            return matcher.group("sovereignty");
        } else {
            return s;
        }

    }

    static void printSovereignties(Object[] countryInfos) throws Exception {
        BlockKeeper bk = new BlockKeeper();
        Map<String, Set<String>> countriesPerSovereignty = new TreeMap<>();
        for (Object o : countryInfos) {
            CountryInfo ci = (CountryInfo)o;
            String sovereignty = parseSovereignty(ci.getSovereignty());
            Set<String> countriesOf = countriesPerSovereignty.get(sovereignty);
            if (countriesOf == null) {
                countriesOf = new TreeSet<>();
                countriesPerSovereignty.put(sovereignty, countriesOf);
            }
            countriesOf.add(bk.update(ci.getName()));
    }

        for (Map.Entry<String, Set<String>> entry : countriesPerSovereignty.entrySet()) {
            System.out.println(entry.getKey() + ", " + entry.getValue().size());
            for (String country : entry.getValue()) {
                System.out.println("    " + country);
            }
        }
    }
}
