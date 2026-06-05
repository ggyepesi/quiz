package wikidata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import wikidata.query.WikidataQueryBuilder;
import wikidata.query.WikidataRootQuery;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class WikidataGroupedDownloader<R extends WikidataDownloadRule> {

    public record Downloaded(
            WikidataEntity root,
            Map<String, Set<WikidataEntity>> groups
    ) {
        public Set<WikidataEntity> group(String name) {
            return groups.getOrDefault(name, Set.of());
        }
    }

    private final WikidataSparqlClient client;
    private final WikidataEntityFilter entityFilter;

    private final ObjectMapper mapper =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public WikidataGroupedDownloader(WikidataSparqlClient client) {
        this(client, WikidataEntityFilter.usefulName());
    }

    public WikidataGroupedDownloader(WikidataSparqlClient client,
                                     WikidataEntityFilter entityFilter) {
        this.client = client;
        this.entityFilter = entityFilter == null
                ? WikidataEntityFilter.usefulName()
                : entityFilter;
    }

    public List<WikidataEntity> downloadRoots(WikidataRootQuery rootQuery)
            throws Exception {

        List<WikidataEntity> out = new ArrayList<>();

        for (WikidataBinding b : client.query(rootQuery.sparql())) {
            WikidataEntity e = b.entity(rootQuery.rootVar());

            if (entityFilter.accept(e)) {
                out.add(e);
            }
        }

        return out;
    }

    public Map<WikidataEntity, Downloaded> download(
            Collection<WikidataEntity> roots,
            List<R> rules
                                                   ) throws Exception {
        return download(roots, rules, null);
    }

    public Map<WikidataEntity, Downloaded> download(
            Collection<WikidataEntity> roots,
            List<R> rules,
            File checkpointFile
                                                   ) throws Exception {

        WikidataDownloadCheckpoint checkpoint = loadCheckpoint(checkpointFile);

        Map<WikidataEntity, MutableDownloaded> tmp = new LinkedHashMap<>();
        Set<String> allQids = Collections.synchronizedSet(new TreeSet<>());

        for (WikidataEntity root : roots) {
            if (root == null || root.getQid() == null || root.getQid().isBlank()) {
                continue;
            }

            checkpoint.ensureRoot(root);
            tmp.put(root, new MutableDownloaded(root));
            allQids.add(root.getQid());
        }

        restoreCheckpointIntoTmp(checkpoint, tmp, allQids);

        for (WikidataEntity root : roots) {
            if (root == null || root.getQid() == null || root.getQid().isBlank()) {
                continue;
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (R rule : rules) {
                if (checkpoint.isCompleted(root.getQid(), rule.name())) {
                    System.out.println("SKIP " + rule.name()
                                               + " root=" + root.getName());
                    continue;
                }

                String sparql = rule.queryFor(root.getQid());

                System.out.println("START " + rule.name()
                                           + " root=" + root.getName());

                CompletableFuture<Void> future =
                        client.queryAsync(sparql)
                              .orTimeout(90, TimeUnit.SECONDS)
                              .thenAccept(rows -> {
                                  System.out.println("DONE " + rule.name()
                                                             + " root=" + root.getName()
                                                             + " rows=" + rows.size());

                                  Set<String> qids = new TreeSet<>();

                                  for (WikidataBinding b : rows) {
                                      String itemQid = b.qid(rule.itemVar());

                                      if (itemQid != null && !itemQid.isBlank()) {
                                          qids.add(itemQid);
                                      }
                                  }

                                  MutableDownloaded data = tmp.get(root);

                                  synchronized (data) {
                                      data.add(rule.name(), qids);
                                  }

                                  allQids.addAll(qids);

                                  synchronized (checkpoint) {
                                      checkpoint.addResult(root, rule.name(), qids);
                                      saveCheckpointQuietly(checkpoint, checkpointFile);
                                  }
                              })
                              .exceptionally(ex -> {
                                  System.err.println("FAILED " + rule.name()
                                                             + " root=" + root.getName()
                                                             + ": " + ex);
                                  return null;
                              });

                futures.add(future);
            }

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                             .orTimeout(180, TimeUnit.SECONDS)
                             .join();

            Thread.sleep(300);
        }

        saveCheckpointQuietly(checkpoint, checkpointFile);

        System.out.println("Loading labels: " + allQids.size());

        Map<String, WikidataEntity> byQid = loadLabels(allQids);

        Map<WikidataEntity, Downloaded> out = new LinkedHashMap<>();

        for (MutableDownloaded m : tmp.values()) {
            Map<String, Set<WikidataEntity>> groups = new TreeMap<>();

            for (Map.Entry<String, Set<String>> e : m.groups.entrySet()) {
                groups.put(e.getKey(), entities(e.getValue(), byQid));
            }

            out.put(m.root, new Downloaded(m.root, groups));
        }

        return out;
    }

    private void restoreCheckpointIntoTmp(WikidataDownloadCheckpoint checkpoint,
                                          Map<WikidataEntity, MutableDownloaded> tmp,
                                          Set<String> allQids) {
        Map<String, MutableDownloaded> tmpByQid = new TreeMap<>();

        for (MutableDownloaded m : tmp.values()) {
            tmpByQid.put(m.root.getQid(), m);
        }

        for (WikidataDownloadCheckpoint.Root r : checkpoint.roots.values()) {
            MutableDownloaded m = tmpByQid.get(r.qid);

            if (m == null) {
                continue;
            }

            for (Map.Entry<String, Set<String>> e : r.groups.entrySet()) {
                m.add(e.getKey(), e.getValue());
                allQids.addAll(e.getValue());
            }
        }
    }

    private WikidataDownloadCheckpoint loadCheckpoint(File file) throws Exception {
        if (file == null || !file.exists()) {
            return new WikidataDownloadCheckpoint();
        }

        return mapper.readValue(file, WikidataDownloadCheckpoint.class);
    }

    private void saveCheckpointQuietly(WikidataDownloadCheckpoint checkpoint,
                                       File file) {
        if (file == null) {
            return;
        }

        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }

            mapper.writeValue(file, checkpoint);
        } catch (Exception e) {
            System.err.println("Could not save checkpoint: " + e);
        }
    }

    private Map<String, WikidataEntity> loadLabels(Collection<String> qids)
            throws Exception {

        Map<String, WikidataEntity> out = new TreeMap<>();

        List<String> list = qids.stream()
                                .filter(Objects::nonNull)
                                .filter(s -> !s.isBlank())
                                .distinct()
                                .toList();

        int batchSize = 100;

        for (int i = 0; i < list.size(); i += batchSize) {
            List<String> batch =
                    list.subList(i, Math.min(i + batchSize, list.size()));

            String sparql = new WikidataQueryBuilder()
                    .selectEntity("item")
                    .values("item", batch.toArray(String[]::new))
                    .build();

            for (WikidataBinding b : client.query(sparql)) {
                WikidataEntity e = b.entity("item");

                if (entityFilter.accept(e)) {
                    out.put(e.getQid(), e);
                }
            }
        }

        return out;
    }

    private Set<WikidataEntity> entities(Set<String> qids,
                                         Map<String, WikidataEntity> byQid) {
        Set<WikidataEntity> out =
                new TreeSet<>(Comparator.comparing(
                        WikidataEntity::getName,
                        Comparator.nullsLast(String::compareTo)));

        for (String qid : qids) {
            WikidataEntity e = byQid.get(qid);

            if (e != null) {
                out.add(e);
            }
        }

        return out;
    }

    private static class MutableDownloaded {
        final WikidataEntity root;
        final Map<String, Set<String>> groups = new TreeMap<>();

        MutableDownloaded(WikidataEntity root) {
            this.root = root;
        }

        void add(String group, Collection<String> qids) {
            groups.computeIfAbsent(group, k -> new TreeSet<>()).addAll(qids);
        }
    }
}