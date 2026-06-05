package misc;

import java.io.FileInputStream;
import java.io.ObjectInputStream;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Read coat-of-arms categories after 'with' and parse https://en.wikipedia.org/wiki/Flags_with_category
// For flags there are no categories given in their wiki pages so we get them this way.
public class CoatsOfArmsWith {
    static final Pattern categoryPattern = Pattern.compile("(with the |with )(?<category>[^|]*)");
    static final String flagsWiki = "https://commons.wikimedia.org/wiki/Flags_with_";

    public static void main(String[] args) throws Exception {
        ObjectInputStream in = new ObjectInputStream(new FileInputStream("resources/countries/coat_of_arms/coat_of_arms.ser"));
        Object[] objects = (Object[]) in.readObject();
        in.close();
        System.out.println(objects.length + " countries");
        Map<String, Set<String>> categories = new TreeMap<>();
        for (Object o : objects) {
            for (ImageAndDescription image : ((Country)o).getCoatsOfArms()) {
                for (String category : image.getCategories()) {
                    Matcher matcher = categoryPattern.matcher(category);
                    if (!matcher.find()) continue;
                    String element = matcher.group("category");
                    Set<String> countries = categories.get(element);
                    if (countries == null) {
                        countries = new TreeSet<>();
                        categories.put(element, countries);
                    }
                    countries.add(((Country)o).getCountryInfo().getName());
                }
            }
        }
        System.out.println(categoryPattern);

        for (Map.Entry<String, Set<String>> e : categories.entrySet()) {
            System.out.println(e.getKey() + ", " + e.getValue().size());
            for (String name : e.getValue()) {
                System.out.println("    " + name);
            }
        }
    }
 
}
