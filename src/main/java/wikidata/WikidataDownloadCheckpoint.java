package wikidata;

import java.util.*;

public class WikidataDownloadCheckpoint {
    public Map<String, Root> roots = new TreeMap<>();
    public Set<String> completed = new TreeSet<>();

    public static class Root {
        public String qid;
        public String name;
        public Map<String, Set<String>> groups = new TreeMap<>();
    }

    public boolean isCompleted(String rootQid, String ruleName) {
        return completed.contains(key(rootQid, ruleName));
    }

    public void ensureRoot(WikidataEntity root) {
        if (root == null || root.getQid() == null) {
            return;
        }

        roots.computeIfAbsent(root.getQid(), qid -> {
            Root r = new Root();
            r.qid = root.getQid();
            r.name = root.getName();
            return r;
        });
    }

    public void addResult(WikidataEntity root,
                          String ruleName,
                          Collection<String> qids) {
        ensureRoot(root);

        Root r = roots.get(root.getQid());
        r.groups.computeIfAbsent(ruleName, k -> new TreeSet<>()).addAll(qids);

        completed.add(key(root.getQid(), ruleName));
    }

    private static String key(String rootQid, String ruleName) {
        return rootQid + "|" + ruleName;
    }
}