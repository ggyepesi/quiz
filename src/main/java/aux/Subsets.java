package aux;

import java.util.ArrayList;
import java.util.List;

public class Subsets {
    public static String[] subsets(String set[], int n) {
        String subsets[] = new String[1 << n];
        int index = 0;
        for (int i = 0; i < (1<<n); i++) {
            List<String> subset = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                // (1<<j) is a number with jth bit 1
                // so when we 'and' them with the
                // subset number we get which numbers
                // are present in the subset and which
                // are not
                if ((i & (1 << j)) > 0) {
                    subset.add(set[j]);
                }
            }
            subsets[index] = String.join(" ", subset);
            ++index;
        }
        return subsets;
    }

    public static void main(String[] args)
    {
        String set[] = {"a", "b", "c", "d"};
        int n = set.length;
        String sis[] = subsets(set, n - 1);
        String last = set[n - 1];
        for (int i = sis.length - 1; i >= 0; --i) {
            if (!sis[i].isEmpty()) {
                System.out.println(sis[i] + " " + last);
            }
        }
    }
}
