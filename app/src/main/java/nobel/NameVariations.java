package nobel;

import java.util.*;

import aux.Pair;
import aux.Subsets;

// Given a set of names (first name, middle names, family name along with titles like Sir, Jr.) generate all the versions
// by replacing first and middle names with their first letter followed by dot.
public class NameVariations {
    private static final Set<String> suffixes = Set.of("Jr.");
    
    public static String[] splitWithoutEmpty(String name) {
        return splitWithoutEmpty(name, "\\s+");
    }

    public static String[] splitWithoutEmpty(String name, String regex) {
        return  Arrays.stream(name.split(regex, -1)).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    public static List<String> generateNameVariations(String fullName) {
        String parts[] = splitWithoutEmpty(fullName);
        return generateNameVariations(parts, suffixes.contains(parts[parts.length - 1]) ? 2 : 1);
    }

    public static List<String> generateNameVariations(String[] parts, int familyNameCount) {
        int n = parts.length;
        if (n == 0) return new ArrayList<>();
        if (n == familyNameCount) {
            return List.of(String.join(" ", parts));
        }

        String[] given = Arrays.copyOfRange(parts, 0, n - familyNameCount);
        String familyPart = String.join(" ", Arrays.copyOfRange(parts, n - familyNameCount, parts.length));

        // positions that can be shortened to X.
        List<Integer> convertible = new ArrayList<>();
        for (int i = 0; i < given.length; i++) {
            if (!given[i].matches("^[A-Z]+\\.$")) {
                convertible.add(i);
            }
        }

        int m = convertible.size();
        int combinations = 1 << m;
        List<String> variations = new ArrayList<>(combinations);
        for (int mask = 0; mask < combinations; mask++) {
            String[] current = given.clone();
            for (int bit = 0; bit < n; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    int idx = convertible.get(bit);
                    current[idx] = current[idx].charAt(0) + ".";
                }
            }
            variations.add(String.join(" ", current) + " " + familyPart);
        }
        return variations;
    }

    public static Set<String> generateNameSubsets(String name) {
        String parts[] = splitWithoutEmpty(name);
        int n = parts.length;
        return generateNameSubsets(parts, suffixes.contains(parts[n - 1]) ? 2 : 1);
    }

    public static Set<String> generateNameSubsets(String[] parts, int familyNameCount) {
        int n = parts.length;
        String[] subsets = Subsets.subsets(parts, n - familyNameCount);
        String familyPart = "";
        for (int i = familyNameCount; i > 0; --i) {
            familyPart += i == familyNameCount ? parts[n - i] : (" " + parts[n - i]);
        }
        Set<String> subsetNames = new TreeSet<>();
        for (int i = subsets.length - 1; i >= 0; --i) {
            subsetNames.add(subsets[i] + (subsets[i].isEmpty() ? familyPart : (" " + familyPart)));
        }
        return subsetNames;
    }

    public static String findLinkByLinkNameSubset(Map<String, String> links, String name) {
        for (String linkName : links.keySet()) {
            if (generateNameSubsets(linkName).contains(name)) {
                return links.get(linkName);
            }
        }
        return null;
    }

    public static Pair<String, String> findLinkForSubsets(Map<String, String> links, String name) {
        Set<String> subsets = generateNameSubsets(name);
        for (String subset : subsets) {
            String link = links.get(subset);
            if (link != null)  {
                return new Pair<>(subset, link);
            } 
        }
        return null;
    }

    public static Pair<String, String> findLinkForVariations(Map<String, String> links, String name) {
        List<String> variations = generateNameVariations(name);
        for (String var : variations) {
            String link = links.get(var);
            if (link != null)  {
                return new Pair<>(var, link);
            } 
        }
        return null;
    }

    public static Pair<String, String>  findLinkForSubsetsOfVariations(Map<String, String> links, String name) {
        for (String variation : generateNameVariations(name)) {
            for (String subset : generateNameSubsets(variation)) {
                String link = links.get(subset);
                if (link != null) {
                    return new Pair<>(subset, link);
                }
            }
        }
        return null;
    }
}