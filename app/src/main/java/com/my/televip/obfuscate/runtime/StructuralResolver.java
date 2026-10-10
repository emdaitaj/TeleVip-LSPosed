package com.my.televip.obfuscate.runtime;

import com.my.televip.logging.Logger;
import com.my.televip.utils.JsonAssetReader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds the Telegram classes and members TeleVip hooks in a build nobody has written a mapping for.
 *
 * The names R8 gives them change with every release, but their shape barely does: what a class
 * extends, the types of its fields and the signatures of its methods, all written in terms of the
 * names obfuscation leaves alone (org.telegram.messenger.*, org.telegram.tgnet.** and everything
 * from the framework and the libraries). tools/telegram-mapping ships those shapes, taken from a
 * build we do have a mapping for; this matches them against whatever is installed.
 *
 * Nothing is guessed: a class is claimed only when one candidate fits clearly better than every
 * other, and a member only when two independent ways of locating it agree. Anything else is left
 * unresolved and logged, so a feature switches itself off rather than hooking the wrong thing.
 */
public final class StructuralResolver {

    private static final String ASSET = "assets/shapes.json";
    /** R8 names classes and members with a few lower case characters; kept names never look like that. */
    private static final Pattern OBFUSCATED = Pattern.compile("[a-z][a-z0-9]{0,3}");
    private static final double MIN_SCORE = 0.75;
    private static final double MIN_MARGIN = 0.05;
    /** How alike two methods' code lengths have to be before that counts as having found it again. */
    private static final double SIZE_MATCH = 0.8;
    private static final double SIZE_MARGIN = 0.1;
    /** How far the members of a class may slide between releases before we stop following them. */
    private static final int BAND = 96;
    /** How many of the members around one have to line up before its own pairing counts. */
    private static final int SUPPORT = 3;
    private static final byte NONE = 0, DIAG = 1, UP = 2, LEFT = 3;

    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, String> fields = new HashMap<>();
    private final Map<String, String> methods = new HashMap<>();
    private final Map<String, List<String>> params = new HashMap<>();
    private final Map<String, int[]> argOrder = new HashMap<>();
    private int wantedClasses;

    private StructuralResolver() {}

    public Map<String, String> classMap() {
        return Collections.unmodifiableMap(classes);
    }

    /** Keys are "ClassKey#member", the way the shipped mappings are written. */
    public Map<String, String> fieldMap() {
        return Collections.unmodifiableMap(fields);
    }

    public Map<String, String> methodMap() {
        return Collections.unmodifiableMap(methods);
    }

    /** Parameter types of the methods R8 rewrote, keyed like the members. */
    public Map<String, List<String>> paramMap() {
        return Collections.unmodifiableMap(params);
    }

    /** For a method whose parameters were only reordered, where each original argument now goes. */
    public Map<String, int[]> argOrderMap() {
        return Collections.unmodifiableMap(argOrder);
    }

    public boolean isComplete() {
        return classes.size() == wantedClasses;
    }

    public static boolean isStableName(String name) {
        if (name == null) return false;
        return !OBFUSCATED.matcher(name.substring(name.lastIndexOf('.') + 1)).matches();
    }

    /**
     * Runs the whole match. Costly enough that the caller keeps the result for the build it was made
     * for; returns null when there is nothing to work with.
     */
    public static StructuralResolver resolve(String apkPath) {
        String raw = JsonAssetReader.readRaw(ASSET, false);
        if (raw == null) {
            Logger.w("No shapes shipped with this build of TeleVip, cannot work the names out");
            return null;
        }
        try {
            long started = System.currentTimeMillis();
            JSONObject root = new JSONObject(raw);
            DexIndex index = DexIndex.read(apkPath);
            StructuralResolver resolver = new StructuralResolver();
            resolver.run(root, index);
            Logger.l("Structural resolver: " + resolver.classes.size() + "/" + resolver.wantedClasses
                    + " classes and " + (resolver.fields.size() + resolver.methods.size()) + " members out of " + index.size()
                    + " in " + (System.currentTimeMillis() - started) + " ms");
            return resolver;
        } catch (Throwable t) {
            Logger.e(t);
            return null;
        }
    }

    private void run(JSONObject root, DexIndex index) {
        JSONObject shapes = root.optJSONObject("shapes");
        if (shapes == null) return;
        wantedClasses = shapes.length();

        Map<String, String> chainOf = new HashMap<>(index.size());
        for (String name : index.classNames()) chainOf.put(name, chainKey(name, index));

        Map<String, Shape> shapeCache = new HashMap<>();
        List<String> unresolved = new ArrayList<>();
        for (Iterator<String> it = shapes.keys(); it.hasNext(); ) {
            String original = it.next();
            JSONObject wanted = shapes.optJSONObject(original);
            if (wanted == null) continue;
            String match = bestMatch(wanted, chainOf, index, shapeCache);
            if (match != null) {
                classes.put(original, match);
            } else {
                unresolved.add(original);
            }
        }

        // a class whose shape is shared with others may still be named in one that was matched
        JSONObject anchors = root.optJSONObject("anchors");
        if (anchors != null) {
            for (String original : unresolved) {
                String match = byAnchor(anchors.optJSONArray(original), index, shapeCache,
                        shapes.optJSONObject(original));
                if (match != null) classes.put(original, match);
            }
        }

        JSONObject memberShapes = root.optJSONObject("members");
        if (memberShapes != null) resolveMembers(memberShapes, index, shapeCache);

        for (String original : unresolved) {
            if (!classes.containsKey(original)) Logger.w("Structural resolver: no match for " + original);
        }
        // once per Telegram build, and the only way to check afterwards what was matched to what
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            Logger.l("resolved class " + entry.getKey() + " -> " + entry.getValue());
        }
        for (Map<String, String> found : Arrays.asList(fields, methods)) {
            for (Map.Entry<String, String> entry : found.entrySet()) {
                Logger.l("resolved member " + entry.getKey() + " -> " + entry.getValue());
            }
        }
    }

    // ------------------------------------------------------------ classes

    /**
     * The nearest ancestor whose name survives obfuscation, and how far up it is. Known from the dex
     * alone, and narrows the candidates for a target from twenty thousand classes to a few hundred.
     */
    private static String chainKey(String name, DexIndex index) {
        String current = name;
        int depth = 0;
        while (current != null && depth < 64) {
            String parent = index.superOf(current);
            if (parent == null) return depth == 0 ? "java.lang.Object/0" : current + "/" + depth;
            depth++;
            if (isStableName(parent)) return parent + "/" + depth;
            current = parent;
        }
        return "java.lang.Object/" + depth;
    }

    private String bestMatch(JSONObject wanted, Map<String, String> chainOf, DexIndex index,
                             Map<String, Shape> shapeCache) {
        JSONArray chain = wanted.optJSONArray("chain");
        String chainKey = chain == null ? null : chain.optString(0) + "/" + chain.optInt(1);
        JSONArray counts = wanted.optJSONArray("counts");
        int wantFields = counts == null ? -1 : counts.optInt(0);
        int wantMethods = counts == null ? -1 : counts.optInt(1);
        Shape target = Shape.fromJson(wanted);

        double bestScore = 0;
        double runnerUp = 0;
        String best = null;
        for (Map.Entry<String, String> entry : chainOf.entrySet()) {
            if (chainKey != null && !chainKey.equals(entry.getValue())) continue;
            if (!plausibleSize(index.sizeOf(entry.getKey()), wantFields, wantMethods)) continue;
            Shape candidate = shapeOf(entry.getKey(), index, shapeCache);
            if (candidate == null) continue;
            double score = target.similarity(candidate);
            if (score > bestScore) {
                runnerUp = bestScore;
                bestScore = score;
                best = entry.getKey();
            } else if (score > runnerUp) {
                runnerUp = score;
            }
        }
        return bestScore >= MIN_SCORE && bestScore - runnerUp >= MIN_MARGIN ? best : null;
    }

    /**
     * A class that lost most of its members, or grew several times over, is not the one we are
     * after. Checked from the dex header alone, before any member list is built.
     */
    private static boolean plausibleSize(int[] size, int wantFields, int wantMethods) {
        if (wantFields < 0 || size == null) return true;
        return within(size[0], wantFields) && within(size[1], wantMethods);
    }

    private static boolean within(int have, int want) {
        // a release adds and removes a few members; beyond this it is a different class
        return have >= want * 0.6 - 3 && have <= want * 1.8 + 5;
    }

    private String byAnchor(JSONArray anchors, DexIndex index, Map<String, Shape> shapeCache,
                            JSONObject wanted) {
        if (anchors == null) return null;
        for (int i = 0; i < anchors.length(); i++) {
            JSONObject anchor = anchors.optJSONObject(i);
            if (anchor == null) continue;
            String ownerOriginal = anchor.optString("class");
            String owner = anchor.optBoolean("stable") ? ownerOriginal : classes.get(ownerOriginal);
            Shape shape = owner == null ? null : shapeOf(owner, index, shapeCache);
            if (shape == null) continue;
            String found = shape.typeAtSlot(anchor.optString("canon"), anchor.optInt("member"),
                    anchor.optInt("count"), anchor.optInt("slot"));
            if (found != null && index.sizeOf(found) != null && looksRight(found, wanted, index, shapeCache)) {
                return found;
            }
        }
        return null;
    }

    /**
     * An anchor names a class by where it is mentioned in another one, so a member that moved can
     * send it to something else entirely. What it names still has to look like what we are after.
     */
    private boolean looksRight(String found, JSONObject wanted, DexIndex index, Map<String, Shape> shapeCache) {
        if (wanted == null) return true;
        JSONArray chain = wanted.optJSONArray("chain");
        if (chain != null && !(chain.optString(0) + "/" + chain.optInt(1)).equals(chainKey(found, index))) {
            return false;
        }
        JSONArray counts = wanted.optJSONArray("counts");
        if (counts != null && !plausibleSize(index.sizeOf(found), counts.optInt(0), counts.optInt(1))) {
            return false;
        }
        Shape candidate = shapeOf(found, index, shapeCache);
        return candidate != null && Shape.fromJson(wanted).similarity(candidate) >= MIN_SCORE;
    }

    private Shape shapeOf(String name, DexIndex index, Map<String, Shape> cache) {
        if (cache.containsKey(name)) return cache.get(name);
        Shape shape = Shape.of(name, index);
        cache.put(name, shape);
        return shape;
    }

    // ------------------------------------------------------------ members

    private void resolveMembers(JSONObject memberShapes, DexIndex index, Map<String, Shape> shapeCache) {
        for (Iterator<String> it = memberShapes.keys(); it.hasNext(); ) {
            String original = it.next();
            // a class obfuscation left alone is found under its own name
            String resolved = classes.containsKey(original) ? classes.get(original) : original;
            Shape shape = shapeOf(resolved, index, shapeCache);
            if (shape == null) continue;
            JSONObject wanted = memberShapes.optJSONObject(original);
            if (wanted == null) continue;
            String classKey = original.substring(original.lastIndexOf('.') + 1);
            JSONObject order = wanted.optJSONObject("order");
            JSONArray forms = wanted.optJSONArray("forms");
            put(classKey, wanted.optJSONObject("fields"), shape.fields, false,
                    shipped(order, forms, "fields"));
            put(classKey, wanted.optJSONObject("methods"), shape.methods, true,
                    shipped(order, forms, "methods"));
        }
    }

    /** The shape of every member the class had where the shapes were taken, in order. */
    private static List<String> shipped(JSONObject order, JSONArray forms, String kind) {
        List<String> out = new ArrayList<>();
        if (order == null || forms == null) return out;
        JSONArray indexes = order.optJSONArray(kind);
        if (indexes == null) return out;
        for (int i = 0; i < indexes.length(); i++) {
            int form = indexes.optInt(i, -1);
            if (form >= 0 && form < forms.length()) out.add(forms.optString(form));
        }
        return out;
    }

    /**
     * Where the class has changed since the shapes were taken, a member is claimed on two weaker
     * signals: the length of its code, and where the members either side of what was added or
     * removed put it. Both have to point at the same member where both have an opinion, and a
     * member with a shape nothing else in the class shares needs neither.
     */
    private static Member otherwise(List<Member> have, JSONArray description, boolean areMethods, int[] pairs) {
        String canon = description.optString(0);
        int sameCount = description.optInt(2);
        int absolute = description.optInt(3);
        Member sized = areMethods
                ? Member.byCodeSize(have, canon, description.optInt(8))
                : null;
        Member aligned = aligned(have, canon, absolute, pairs);
        if (sized != null && aligned != null) return sized == aligned ? sized : null;
        if (sameCount == 1) {
            Member only = Member.onlyWithShape(have, canon);
            if (only != null && (sized == null || sized == only)) return only;
        }
        return sized != null ? sized : aligned;
    }

    private static Member aligned(List<Member> have, String canon, int absolute, int[] pairs) {
        if (absolute < 0 || absolute >= pairs.length) return null;
        int at = pairs[absolute];
        if (at < 0 || at >= have.size() || !have.get(at).canon.equals(canon)) return null;
        // on its own a pairing means little; it counts when the members around it line up too
        int support = 0;
        for (int k = Math.max(0, absolute - 3); k < Math.min(pairs.length, absolute + 4); k++) {
            if (k != absolute && pairs[k] >= 0) support++;
        }
        return support >= SUPPORT ? have.get(at) : null;
    }

    private void put(String classKey, JSONObject wanted, List<Member> have, boolean areMethods,
                     List<String> was) {
        if (wanted == null) return;
        int[] pairs = null;
        for (Iterator<String> it = wanted.keys(); it.hasNext(); ) {
            String member = it.next();
            JSONArray description = wanted.optJSONArray(member);
            if (description == null || description.length() < 7) continue;
            Member found = Member.exactly(have, description.optString(0), description.optInt(1),
                    description.optInt(2), description.optInt(3), description.optInt(4),
                    description.optString(5), description.optString(6));
            if (found == null) {
                if (pairs == null) pairs = align(was, have);
                found = otherwise(have, description, areMethods, pairs);
            }
            if (found == null) {
                Logger.w("Structural resolver: could not place " + classKey + "#" + member);
                continue;
            }
            String key = classKey + "#" + member;
            (areMethods ? methods : fields).put(key, found.name);
            if (!areMethods) continue;
            // a hook has to be installed with the parameter list the method really has, which R8 may
            // have shortened or reordered; both are read back out of the descriptor it ended up with
            List<String> real = Shape.parameterNames(found.raw);
            if (real != null) params.put(key, real);
            int[] order = Shape.argumentOrder(description.optJSONArray(7), found.raw);
            if (order != null) argOrder.put(key, order);
        }
    }

    // ------------------------------------------------------------ shapes

    private static final class Member {
        final String name;
        final String canon;
        final String raw;
        final int codeSize;

        Member(String name, String canon, String raw) {
            this(name, canon, raw, 0);
        }

        Member(String name, String canon, String raw, int codeSize) {
            this.name = name;
            this.canon = canon;
            this.raw = raw;
            this.codeSize = codeSize;
        }

        /**
         * Three things have to agree before a member is claimed this way: its position among the
         * members of the same shape, its position among all of them, and the shapes of the two it
         * sits between. A member added in one place and removed in another shifts the first two
         * together, and only the neighbours catch that.
         */
        static Member exactly(List<Member> members, String canon, int sameIndex, int sameCount,
                              int absolute, int total, String before, String after) {
            if (members.size() != total || absolute < 0 || absolute >= members.size()) return null;
            List<String> same = sameShape(members, canon);
            if (same.size() != sameCount || sameIndex < 0 || sameIndex >= same.size()) return null;
            Member at = members.get(absolute);
            if (!at.canon.equals(canon) || !at.name.equals(same.get(sameIndex))) return null;
            String haveBefore = absolute > 0 ? members.get(absolute - 1).canon : "";
            String haveAfter = absolute + 1 < members.size() ? members.get(absolute + 1).canon : "";
            return haveBefore.equals(before) && haveAfter.equals(after) ? at : null;
        }

        static List<String> sameShape(List<Member> members, String canon) {
            List<String> same = new ArrayList<>();
            for (Member member : members) {
                if (member.canon.equals(canon)) same.add(member.name);
            }
            return same;
        }

        /** The one member of this shape, where the class has exactly one. */
        static Member onlyWithShape(List<Member> members, String canon) {
            Member only = null;
            for (Member member : members) {
                if (!member.canon.equals(canon)) continue;
                if (only != null) return null;
                only = member;
            }
            return only;
        }

        /** The method of this shape whose code is closest in length, where one clearly is. */
        static Member byCodeSize(List<Member> members, String canon, int wantedSize) {
            if (wantedSize <= 0) return null;
            Member best = null;
            double bestScore = 0, runnerUp = 0;
            for (Member member : members) {
                if (!member.canon.equals(canon) || member.codeSize <= 0) continue;
                double score = (double) Math.min(member.codeSize, wantedSize) / Math.max(member.codeSize, wantedSize);
                if (score > bestScore) {
                    runnerUp = bestScore;
                    bestScore = score;
                    best = member;
                } else if (score > runnerUp) {
                    runnerUp = score;
                }
            }
            return bestScore >= SIZE_MATCH && bestScore - runnerUp >= SIZE_MARGIN ? best : null;
        }
    }

    /**
     * Which member of the installed class each member of the shipped list became, for the ones a
     * longest common run of shapes pairs up. Members added or removed in between shift the rest
     * along; this is what follows them, and anything it cannot pair up is left out.
     */
    private static int[] align(List<String> was, List<Member> have) {
        int n = was.size(), m = have.size();
        int[] pairs = new int[n];
        Arrays.fill(pairs, -1);
        // lists this far apart are not the same class a release later, and lining them up would
        // cost a table as wide as the difference
        if (n == 0 || m == 0 || Math.abs(n - m) > Math.max(BAND, Math.min(n, m) / 2)) return pairs;
        int width = Math.max(BAND, Math.abs(n - m) + 16);
        int span = 2 * width + 1;
        // the move that got us to each cell, kept so the pairing can be read back off the end
        byte[] moves = new byte[(n + 1) * span];
        int[] previous = new int[span];
        int[] current = new int[span];
        for (int j = 0; j < span; j++) previous[j] = 0;
        for (int i = 1; i <= n; i++) {
            Arrays.fill(current, 0);
            int lo = Math.max(1, i - width), hi = Math.min(m, i + width);
            for (int j = lo; j <= hi; j++) {
                int best = 0;
                byte move = NONE;
                int diagonal = cell(previous, j - 1, i - 1, width);
                if (diagonal >= 0 && was.get(i - 1).equals(have.get(j - 1).canon)) {
                    best = diagonal + 1;
                    move = DIAG;
                }
                int up = cell(previous, j, i - 1, width);
                if (up > best) {
                    best = up;
                    move = UP;
                }
                int left = cell(current, j - 1, i, width);
                if (left > best) {
                    best = left;
                    move = LEFT;
                }
                int slot = slot(j, i, width);
                if (slot < 0) continue;
                current[slot] = best;
                moves[i * span + slot] = move;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        int i = n, j = Math.min(m, n + width);
        while (j > 0 && slot(j, i, width) < 0) j--;
        while (i > 0 && j > 0) {
            int slot = slot(j, i, width);
            if (slot < 0) break;
            byte move = moves[i * span + slot];
            if (move == DIAG) {
                pairs[i - 1] = j - 1;
                i--;
                j--;
            } else if (move == UP) {
                i--;
            } else if (move == LEFT) {
                j--;
            } else {
                break;
            }
        }
        return pairs;
    }

    private static int slot(int j, int i, int width) {
        int slot = j - i + width;
        return slot < 0 || slot >= 2 * width + 1 ? -1 : slot;
    }

    private static int cell(int[] row, int j, int i, int width) {
        if (j < 0) return 0;
        int slot = slot(j, i, width);
        return slot < 0 ? -1 : row[slot];
    }

    private static final class Shape {
        final String superName;
        final Map<String, Integer> fieldCounts = new HashMap<>();
        final Map<String, Integer> methodCounts = new HashMap<>();
        final List<Member> fields = new ArrayList<>();
        final List<Member> methods = new ArrayList<>();

        private Shape(String superName) {
            this.superName = superName;
        }

        static Shape of(String className, DexIndex index) {
            DexIndex.Members raw = index.membersOf(className);
            if (raw == null) return null;
            String superName = index.superOf(className);
            Shape shape = new Shape(canon("L"
                    + (superName == null ? "java.lang.Object" : superName).replace('.', '/') + ";"));
            for (int i = 0; i < raw.fieldNames.size(); i++) {
                String descriptor = raw.fieldTypes.get(i);
                String name = raw.fieldNames.get(i);
                if (name == null || descriptor == null) continue;
                shape.fields.add(new Member(name, canon(descriptor), descriptor));
            }
            for (int i = 0; i < raw.methodNames.size(); i++) {
                String name = raw.methodNames.get(i);
                String descriptor = raw.methodTypes.get(i);
                // a static initialiser is in the dex but never in a shipped shape
                if (name == null || descriptor == null || "<clinit>".equals(name)) continue;
                int size = i < raw.methodSizes.size() ? raw.methodSizes.get(i) : 0;
                shape.methods.add(new Member(name, canonDescriptor(descriptor), descriptor, size));
            }
            // left in dex order, which is what the shipped positions were recorded in
            for (Member field : shape.fields) count(shape.fieldCounts, field.canon);
            for (Member method : shape.methods) count(shape.methodCounts, method.canon);
            return shape;
        }

        static Shape fromJson(JSONObject json) {
            Shape shape = new Shape(json.optString("super"));
            copy(json.optJSONObject("fields"), shape.fieldCounts);
            copy(json.optJSONObject("methods"), shape.methodCounts);
            return shape;
        }

        private static void copy(JSONObject from, Map<String, Integer> into) {
            if (from == null) return;
            for (Iterator<String> it = from.keys(); it.hasNext(); ) {
                String key = it.next();
                into.put(key, from.optInt(key));
            }
        }

        private static void count(Map<String, Integer> counts, String key) {
            Integer previous = counts.get(key);
            counts.put(key, previous == null ? 1 : previous + 1);
        }

        /**
         * How much of the shipped shape the candidate still has. Members get added far more often
         * than removed, so this asks how much of the old shape survived rather than how equal the
         * two are, and only mildly penalises a candidate that grew.
         */
        double similarity(Shape candidate) {
            if (!superName.equals(candidate.superName)) return 0;
            return 0.4 * overlap(fieldCounts, candidate.fieldCounts)
                    + 0.6 * overlap(methodCounts, candidate.methodCounts);
        }

        private static double overlap(Map<String, Integer> wanted, Map<String, Integer> have) {
            int total = 0;
            int kept = 0;
            for (Map.Entry<String, Integer> entry : wanted.entrySet()) {
                int want = entry.getValue();
                total += want;
                Integer there = have.get(entry.getKey());
                kept += Math.min(want, there == null ? 0 : there);
            }
            int size = 0;
            for (Integer value : have.values()) size += value;
            if (total == 0) return size == 0 ? 1 : 0.5;
            double extra = Math.max(0, size - total);
            return ((double) kept / total) / (1.0 + extra / (4.0 * total));
        }

        /**
         * Class names of the parameters a method really takes, for installing a hook on it.
         */
        static List<String> parameterNames(String descriptor) {
            List<String> all = parts(descriptor);
            List<String> names = new ArrayList<>(all.size() - 1);
            for (int i = 0; i < all.size() - 1; i++) {
                String name = javaName(all.get(i));
                if (name == null) return null;
                names.add(name);
            }
            return names;
        }

        private static String javaName(String descriptor) {
            int dims = 0;
            while (dims < descriptor.length() && descriptor.charAt(dims) == '[') dims++;
            String base = descriptor.substring(dims);
            String name;
            switch (base) {
                case "I": name = "int"; break;
                case "Z": name = "boolean"; break;
                case "J": name = "long"; break;
                case "F": name = "float"; break;
                case "D": name = "double"; break;
                case "B": name = "byte"; break;
                case "C": name = "char"; break;
                case "S": name = "short"; break;
                case "V": name = "void"; break;
                default:
                    name = DexIndex.className(base);
                    if (name == null) return null;
            }
            StringBuilder out = new StringBuilder(name);
            for (int i = 0; i < dims; i++) out.append("[]");
            return out.toString();
        }

        /**
         * Where each of the original arguments ends up once R8 has sorted the parameters by type.
         * Only claimed when the real parameters are the same types in a different order; a method
         * that lost or gained one is left alone, and the caller passes what it always did.
         */
        static int[] argumentOrder(JSONArray declared, String descriptor) {
            if (declared == null || declared.length() == 0) return null;
            List<String> all = parts(descriptor);
            List<String> real = new ArrayList<>(all.subList(0, all.size() - 1));
            if (real.size() != declared.length()) return null;
            List<String> wanted = new ArrayList<>(declared.length());
            for (int i = 0; i < declared.length(); i++) wanted.add(declared.optString(i));
            List<String> realCanon = new ArrayList<>(real.size());
            for (String part : real) realCanon.add(canon(part));
            List<String> sortedWanted = new ArrayList<>(wanted);
            List<String> sortedReal = new ArrayList<>(realCanon);
            Collections.sort(sortedWanted);
            Collections.sort(sortedReal);
            if (!sortedWanted.equals(sortedReal)) return null;
            if (wanted.equals(realCanon)) return null;  // nothing moved
            int[] order = new int[realCanon.size()];
            boolean[] used = new boolean[wanted.size()];
            for (int i = 0; i < realCanon.size(); i++) {
                int from = -1;
                for (int j = 0; j < wanted.size(); j++) {
                    if (!used[j] && wanted.get(j).equals(realCanon.get(i))) {
                        from = j;
                        break;
                    }
                }
                if (from < 0) return null;
                used[from] = true;
                order[i] = from;
            }
            return order;
        }

        /** The real type sitting in the signature slot a shipped anchor pointed at. */
        String typeAtSlot(String canon, int memberIndex, int memberCount, int slot) {
            List<String> matching = new ArrayList<>();
            for (Member method : methods) {
                if (method.canon.equals(canon)) matching.add(method.raw);
            }
            if (matching.size() != memberCount || memberIndex < 0 || memberIndex >= matching.size()) return null;
            int unstable = 0;
            for (String part : parts(matching.get(memberIndex))) {
                if (!"L?;".equals(canon(part))) continue;
                if (unstable++ == slot) return DexIndex.className(part);
            }
            return null;
        }

        private static List<String> parts(String descriptor) {
            List<String> out = new ArrayList<>();
            int close = descriptor.indexOf(')');
            String body = descriptor.substring(1, close);
            int i = 0;
            while (i < body.length()) {
                int j = i;
                while (body.charAt(j) == '[') j++;
                if (body.charAt(j) == 'L') j = body.indexOf(';', j);
                out.add(body.substring(i, j + 1));
                i = j + 1;
            }
            out.add(descriptor.substring(close + 1));
            return out;
        }

        private static String canonDescriptor(String descriptor) {
            List<String> all = parts(descriptor);
            StringBuilder out = new StringBuilder("(");
            for (int i = 0; i < all.size() - 1; i++) out.append(canon(all.get(i)));
            return out.append(')').append(canon(all.get(all.size() - 1))).toString();
        }

        private static String canon(String descriptor) {
            int dims = 0;
            while (dims < descriptor.length() && descriptor.charAt(dims) == '[') dims++;
            String base = descriptor.substring(dims);
            if (!base.startsWith("L")) return descriptor;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < dims; i++) out.append('[');
            return out.append(isStableName(DexIndex.className(base)) ? base : "L?;").toString();
        }
    }
}
