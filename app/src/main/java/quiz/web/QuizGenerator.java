package quiz.web;

import objectview.Viewable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Builds a multiple-choice {@link Quiz} from a dataset: each question shows
 * the {@code promptFields} of one Viewable (e.g. its logo, or name + group)
 * and asks for the combination of its {@code answerFields}, with distractors
 * drawn from other items.
 */
public final class QuizGenerator {

    private static final Random R = new Random();
    private static final int OPTIONS = 4;
    private static final String JOIN = " · ";

    private QuizGenerator() {}

    public static Quiz generate(
            ViewableStore store,
            String type,
            String group,
            List<String> promptFields,
            List<String> answerFields,
            int n) throws Exception {

        // A bare collection/ref path (e.g. "stars") defaults to showing its
        // members' names; once a child path under it is also selected (e.g.
        // "stars.Name"), the child specifies what to show and the bare parent is
        // redundant — it duplicated the names (the "appears twice, under stars
        // AND under name" bug). Drop any path that has a strict descendant in
        // the same list.
        promptFields = withoutCoveredParents(promptFields);
        answerFields = withoutCoveredParents(answerFields);

        Collection<Viewable> all = store.members(type, group);
        if (all == null || promptFields.isEmpty() || answerFields.isEmpty()) {
            return new Quiz(type, join(promptFields), join(answerFields), List.of());
        }

        // exclude = this entity's own answer value(s); a distractor must not be
        // one of them. For a collection answer field (e.g. sharesBorderWith) the
        // question asks about ONE member, and the other members are excluded
        // from the distractor pool so they aren't offered as wrong answers.
        record Entry(List<ViewableView.Field> prompts, String answer, Set<String> exclude) {}

        // A single answer field can be image-valued -> render options as
        // pictures; and if it's a collection, we quiz one member at a time.
        boolean singleAnswer = answerFields.size() == 1;
        String soleField = singleAnswer ? answerFields.get(0) : null;

        List<Entry> entries = new ArrayList<>();
        LinkedHashSet<String> answerPool = new LinkedHashSet<>();
        // answer identity -> its image URL list (single image => 1 element;
        // a collection => the whole set, rendered as a carousel).
        java.util.Map<String, List<String>> answerImages = new java.util.HashMap<>();

        // All selected paths (prompt + answer) — used to decide whether a
        // collection's member names are being revealed.
        Set<String> selected = new java.util.HashSet<>(promptFields);
        selected.addAll(answerFields);

        for (Viewable q : all) {
            List<ViewableView.Field> prompts = new ArrayList<>();
            for (String pf : promptFields) {
                ViewableView.Field fv = imageField(type, q, pf, selected);
                if (fv != null) {
                    prompts.add(fv);
                }
            }
            if (prompts.isEmpty()) {
                continue;
            }

            String answer;
            Set<String> exclude;

            // An image-valued answer field renders options as pictures; the
            // whole value (a single chart, or a set of charts) is one option.
            List<String> imgs = singleAnswer
                    ? imageUrls(imageField(type, q, soleField, selected))
                    : List.of();

            if (singleAnswer && !imgs.isEmpty()) {
                answer = ViewableJson.stringValue(q, soleField); // hidden identity
                if (answer == null || answer.isBlank()) {
                    continue;
                }
                exclude = Set.of(answer);
                answerPool.add(answer);
                answerImages.putIfAbsent(answer, imgs);
            } else if (singleAnswer) {
                // A (text) collection: quiz ONE member, excluding the rest from
                // the distractor pool.
                LinkedHashSet<String> members =
                        new LinkedHashSet<>(ViewableJson.stringValues(q, soleField));
                if (members.isEmpty()) {
                    continue;
                }
                List<String> ml = new ArrayList<>(members);
                answer = ml.get(R.nextInt(ml.size()));
                exclude = members;
                answerPool.addAll(members);
            } else {
                List<String> answerParts = new ArrayList<>();
                for (String af : answerFields) {
                    String s = ViewableJson.stringValue(q, af);
                    if (s != null) {
                        answerParts.add(s);
                    }
                }
                if (answerParts.isEmpty()) {
                    continue;
                }
                answer = String.join(JOIN, answerParts);
                exclude = Set.of(answer);
                answerPool.add(answer);
            }

            entries.add(new Entry(prompts, answer, exclude));
        }

        List<String> pool = new ArrayList<>(answerPool);
        Collections.shuffle(entries, R);

        int count = Math.min(n, entries.size());
        List<Quiz.Question> questions = new ArrayList<>();

        boolean hasImages = !answerImages.isEmpty();

        for (int i = 0; i < count; i++) {
            Entry e = entries.get(i);
            List<String> opts = options(e.answer(), e.exclude(), pool);
            List<List<String>> optImages = null;
            if (hasImages) {
                optImages = new ArrayList<>(opts.size());
                for (String o : opts) {
                    optImages.add(answerImages.get(o)); // null for a few is fine
                }
            }
            questions.add(new Quiz.Question(e.prompts(), opts, e.answer(), optImages));
        }

        return new Quiz(type, join(promptFields), join(answerFields), questions);
    }

    // The (possibly blurred) image field for a prompt/answer path, or the plain
    // field for a non-image. A nested collection image strip is blurred per
    // member unless the sibling display field is also selected (so it doesn't reveal
    // the answer); a top-level image uses the name-blur endpoint as before.
    static ViewableView.Field imageField(
            String type, Viewable q, String field, Set<String> selected) {
        ViewableView.Field fv = ViewableJson.fieldOf(q, field);
        if (fv == null) {
            return null;
        }
        boolean image = "image".equals(fv.kind()) || "images".equals(fv.kind());
        if (!image) {
            return fv;
        }
        if (field.indexOf('.') > 0) {
            if (!selected.contains(siblingNamePath(field))) {
                ViewableView.Field blurred = ViewableJson.blurredImageStrip(q, field);
                if (blurred != null) {
                    return blurred;
                }
            }
            return fv; // nested, name revealed -> direct images
        }
        return blurredIfNeeded(type, q, field, fv);
    }

    // The image URLs of an image field: [url] for a single, all values for a
    // strip, empty for a non-image.
    private static List<String> imageUrls(ViewableView.Field fv) {
        if (fv == null) {
            return List.of();
        }
        if ("image".equals(fv.kind()) && fv.url() != null) {
            return List.of(fv.url());
        }
        if ("images".equals(fv.kind()) && fv.values() != null) {
            return fv.values();
        }
        return List.of();
    }

    private static List<String> options(
            String correct, Set<String> exclude, List<String> pool) {
        LinkedHashSet<String> opts = new LinkedHashSet<>();
        opts.add(correct);

        List<String> shuffled = new ArrayList<>(pool);
        Collections.shuffle(shuffled, R);
        for (String s : shuffled) {
            if (opts.size() >= OPTIONS) {
                break;
            }
            if (exclude.contains(s)) {
                continue; // don't offer another correct value as a distractor
            }
            opts.add(s);
        }

        List<String> list = new ArrayList<>(opts);
        Collections.shuffle(list, R);
        return list;
    }

    private static String join(List<String> fields) {
        return String.join(", ", fields);
    }

    // Drops a path that is a strict prefix of another selected path: "stars" is
    // dropped when "stars.name" (or "stars.apparentMagnitude") is also present,
    // since the child paths now define what to show for that field. Preserves
    // order and keeps standalone paths untouched.
    static List<String> withoutCoveredParents(List<String> fields) {
        if (fields == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String f : fields) {
            boolean covered = false;
            for (String other : fields) {
                if (!other.equals(f) && other.startsWith(f + ".")) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                out.add(f);
            }
        }
        return out;
    }

    // For a nested field path, the sibling display path under the same parent.
    private static String siblingNamePath(String path) {
        int i = path.lastIndexOf('.');
        return (i < 0 ? "" : path.substring(0, i + 1))
                + objectview.field.ViewableContractFieldSet.DISPLAY_KEY;
    }

    // For datasets whose image embeds the answer (e.g. a constellation chart
    // labelled with its name), point the prompt at the name-blurring endpoint.
    static ViewableView.Field blurredIfNeeded(
            String type, Viewable q, String field, ViewableView.Field fv) {
        // Route through the blur endpoint when this entity actually has
        // something to hide: a hand mask, or it's a runtime-OCR type. Only for
        // a top-level image of this entity — the blur OCRs *this* entity's name,
        // which is meaningless for a nested (dotted-path) image of some other
        // object, so those are left as their direct image URL.
        if ("image".equals(fv.kind())
                && field.indexOf('.') < 0
                && quiz.ocr.QuizImageBlurrer.blurs(type, q.getDisplayName())) {
            return ViewableView.Field.image(
                    fv.name(), BlurredImageService.blurUrl(type, q.getIdentifier(), field));
        }
        return fv;
    }
}
