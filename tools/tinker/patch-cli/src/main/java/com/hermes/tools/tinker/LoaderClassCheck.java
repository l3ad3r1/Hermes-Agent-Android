package com.hermes.tools.tinker;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The loader-class rules of docs/TINKER-HOTFIX.md, checked on R8 output.
 *
 * Tinker needs the loader classes (the ones that run before a patch can be loaded) to be identical in
 * the base and the fix, and to refer only to each other and the framework. tinker-patch-lib checks
 * both on the dex bytes, which a release build cannot satisfy: R8 outlines repeated code (string
 * building, log messages) into synthetic helper classes (<code>X$$ExternalSyntheticOutlineN</code>,
 * <code>$$ExternalSyntheticLambdaN</code>) and renames and regroups them on every build, so the loader
 * classes call a differently named helper in the fix than in the base.
 *
 * That difference alone could never take effect (a loader class always resolves a helper through the
 * original class loader, to the copy in the installed base), so this check applies the same two rules
 * with one allowance: a call into an R8 synthetic helper (named in that build's mapping.txt) counts as
 * the same call whatever the helper is called. The allowance holds only for a helper that is
 * self-contained. R8 merges unrelated helpers into one class, and such a class refers to app classes;
 * calling it from a loader class makes the original class loader load its own copies of them, which
 * clash with the patched ones when the fix starts (observed on a device: an AbstractMethodError in
 * kotlinx.coroutines, which removed the patch again). So a helper that loader classes call may refer
 * only to the framework and to other such helpers ({@link #unsafeHelpers}). Any other difference in a
 * loader class, and any reference to a class that is not a loader class, a framework class or a
 * synthetic helper, fails too.
 *
 * It works on <code>dexdump -d</code> text from the Android build tools.
 */
final class LoaderClassCheck {

    private static final String[] FRAMEWORK = {
        "Ljava/", "Ljavax/", "Landroid/", "Ldalvik/", "Llibcore/", "Lorg/xmlpull/", "Lorg/w3c/", "Lorg/json/",
    };
    private static final String SYNTHETIC = "Lsynthetic;";

    private static final Pattern CLASS_HEADER = Pattern.compile("^\\s+Class descriptor\\s+: '(.*)'");
    private static final Pattern CODE_PREFIX = Pattern.compile("^[0-9a-f]{6}:\\s*(?:[0-9a-f]{4}\\s*|\\.\\.\\.\\s*)*\\|[0-9a-f]{4}:");
    private static final Pattern METHOD_HEADER = Pattern.compile("^[0-9a-f]{6}:\\s+\\|\\[[0-9a-f]+\\]");
    private static final Pattern INDEX_COMMENT = Pattern.compile("\\s*//\\s*(string|type|method|field|proto|call_site)@[0-9a-f]+");
    private static final Pattern INDEX = Pattern.compile("(string|type|method|field|proto)@[0-9a-f]+");
    private static final Pattern CALL_TARGET = Pattern.compile("(L[\\w/$]+;)\\.([\\w$<>]+):");
    private static final Pattern DESCRIPTOR = Pattern.compile("L[\\w/$]+;");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    /** dexdump bookkeeping that is an offset, an index or debug info, not code. */
    private static final Pattern NOISE = Pattern.compile(
        "^\\s*(source_file_idx|Class #|class_idx|superclass_idx|interfaces|annotations_off|class_data_off|"
            + "static_fields|direct_methods|virtual_methods|instance_fields|insns size|catches|positions|locals|"
            + "0x[0-9a-f]+ line=|0x[0-9a-f]+ - 0x[0-9a-f]+ reg=)");

    private LoaderClassCheck() {}

    /** Loader patterns from the <code>&lt;loader value="..."/&gt;</code> entries of tinker_config.xml. */
    static List<Pattern> loaderPatterns(File config) throws IOException {
        String xml = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8)
            .replaceAll("(?s)<!--.*?-->", "");
        List<Pattern> out = new ArrayList<>();
        Matcher m = Pattern.compile("<loader\\s+value=\"([^\"]+)\"\\s*/>").matcher(xml);
        while (m.find()) {
            String dotted = m.group(1);
            String body = Pattern.quote("L" + dotted.replace('.', '/').replace("*", "\u0000")).replace("\u0000", "\\E.*\\Q");
            out.add(Pattern.compile("^" + body + (dotted.endsWith("*") ? "$" : ";$")));
        }
        if (out.isEmpty()) throw new IOException(config + " lists no <loader> classes");
        return out;
    }

    /**
     * R8 names the classes it generates after the class they were taken from: outlines and lambdas
     * (<code>$$ExternalSynthetic...</code>) and the shared enum-unboxing helper class, which R8 also
     * merges other synthetic helpers into.
     */
    static boolean isR8Synthetic(String originalName) {
        return originalName.contains("$$ExternalSynthetic") || originalName.endsWith("$EnumUnboxingSharedUtility");
    }

    /** Descriptors, as written in the dex, of the R8 synthetic helper classes that mapping.txt names. */
    static Set<String> syntheticClasses(File mapping) throws IOException {
        Set<String> out = new LinkedHashSet<>();
        try (BufferedReader r = Files.newBufferedReader(mapping.toPath(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == ' ' || line.charAt(0) == '#' || !line.endsWith(":")) continue;
                int arrow = line.indexOf(" -> ");
                if (arrow < 0) continue;
                String original = line.substring(0, arrow);
                if (isR8Synthetic(original)) {
                    String obfuscated = line.substring(arrow + 4, line.length() - 1);
                    out.add("L" + obfuscated.replace('.', '/') + ";");
                }
            }
        }
        return out;
    }

    /** What the check reads from one APK. */
    static final class Dexes {
        /** Normalized lines of every loader class, by descriptor (calls into R8 helpers made generic). */
        final Map<String, List<String>> loader = new LinkedHashMap<>();
        /** Lines of every R8 synthetic class, by descriptor, as they are. */
        final Map<String, List<String>> helpers = new LinkedHashMap<>();
        /** The R8 synthetic classes that loader classes call. */
        final Set<String> called = new LinkedHashSet<>();
    }

    /** The loader classes and R8 helpers of the APK's dex files. */
    static Dexes loaderClasses(File apk, String dexdump, List<Pattern> loader, Set<String> synthetic)
        throws IOException, InterruptedException {
        Dexes out = new Dexes();
        File tmp = Files.createTempDirectory("hermes-loader-check").toFile();
        try (ZipFile zip = new ZipFile(apk)) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().matches("classes\\d*\\.dex")) continue;
                File dex = new File(tmp, entry.getName());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, dex.toPath());
                }
                Process p = new ProcessBuilder(dexdump, "-d", dex.getPath()).redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    parse(r, loader, synthetic, out);
                }
                if (p.waitFor() != 0) throw new IOException("dexdump failed on " + entry.getName() + " of " + apk);
                Files.delete(dex.toPath());
            }
        } finally {
            tmp.delete();
        }
        return out;
    }

    /** Reads dexdump -d text, keeping the lines of the loader classes and of the R8 synthetic classes. */
    static void parse(BufferedReader r, List<Pattern> loader, Set<String> synthetic, Dexes out)
        throws IOException {
        List<String> current = null;
        boolean inLoader = false;
        String line;
        while ((line = r.readLine()) != null) {
            Matcher header = CLASS_HEADER.matcher(line);
            if (header.find()) {
                String descriptor = header.group(1);
                inLoader = isLoader(loader, descriptor);
                if (inLoader) {
                    current = new ArrayList<>();
                    out.loader.put(descriptor, current);
                } else if (synthetic.contains(descriptor)) {
                    current = new ArrayList<>();
                    out.helpers.put(descriptor, current);
                } else {
                    current = null;
                }
                continue;
            }
            if (current == null) continue;
            if (inLoader) {
                Matcher call = CALL_TARGET.matcher(line);
                while (call.find()) if (synthetic.contains(call.group(1))) out.called.add(call.group(1));
            }
            String n = normalize(line, inLoader ? synthetic : Set.of());
            if (n != null) current.add(n);
        }
    }

    /**
     * Whether the R8 helpers the loader classes call are safe to load in the original class loader.
     * A loader class runs before a patch is loaded, in the original class loader, so a helper it calls
     * is loaded there too, and loading it loads (or at least verifies against) every class it refers
     * to. If it refers to app classes, the original loader gets its own copies of those, and the
     * patched copies the fix then loads clash with them (an AbstractMethodError at start). A helper may
     * therefore refer only to the framework and to other helpers that are safe in the same way.
     */
    static List<String> unsafeHelpers(Dexes d) {
        List<String> problems = new ArrayList<>();
        for (String helper : d.called) {
            Set<String> bad = new LinkedHashSet<>();
            collectAppReferences(helper, d, new LinkedHashSet<>(), bad);
            if (!bad.isEmpty()) {
                problems.add("R8 helper " + helper + ", which loader classes call, refers to app classes " + bad
                    + ": the original class loader would load its own copies of them and clash with the patched ones");
            }
        }
        return problems;
    }

    private static void collectAppReferences(String helper, Dexes d, Set<String> seen, Set<String> bad) {
        if (!seen.add(helper)) return;
        // A synthetic class that belongs to a loader class (for example the one javac/D8 generate for
        // try-with-resources) is a loader class itself, and is checked as one.
        if (d.loader.containsKey(helper)) return;
        List<String> lines = d.helpers.get(helper);
        if (lines == null) {
            bad.add(helper + " (not found in the dex)");
            return;
        }
        for (String line : lines) {
            Matcher m = DESCRIPTOR.matcher(STRING_LITERAL.matcher(line).replaceAll(""));
            while (m.find()) {
                String ref = m.group();
                if (ref.equals(helper) || isFramework(ref) || d.loader.containsKey(ref)) continue;
                if (d.helpers.containsKey(ref)) collectAppReferences(ref, d, seen, bad);
                else bad.add(ref);
            }
        }
    }

    private static boolean isFramework(String descriptor) {
        for (String prefix : FRAMEWORK) if (descriptor.startsWith(prefix)) return true;
        return false;
    }

    static boolean isLoader(List<Pattern> loader, String descriptor) {
        for (Pattern p : loader) if (p.matcher(descriptor).find()) return true;
        return false;
    }

    /** One dexdump line without offsets, indices and debug info, or null if it carries nothing else. */
    static String normalize(String line, Set<String> synthetic) {
        String l = CODE_PREFIX.matcher(line).replaceFirst("|");
        l = METHOD_HEADER.matcher(l).replaceFirst("|M");
        l = INDEX_COMMENT.matcher(l).replaceAll("");
        l = INDEX.matcher(l).replaceAll("@");
        if (NOISE.matcher(l).find()) return null;
        // A call into an R8 synthetic helper is the same call whatever the helper and its method are called.
        Matcher m = CALL_TARGET.matcher(l);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                synthetic.contains(m.group(1)) ? SYNTHETIC + ".helper:" : m.group(0)));
        }
        m.appendTail(sb);
        l = sb.toString().trim();
        return l.isEmpty() ? null : l;
    }

    /** What differs between the base's and the fix's loader classes; empty if they are the same. */
    static List<String> differences(Map<String, List<String>> base, Map<String, List<String>> fix) {
        List<String> problems = new ArrayList<>();
        for (String c : base.keySet()) {
            if (!fix.containsKey(c)) problems.add("loader class " + c + " is in the base but not in the fix");
        }
        for (String c : fix.keySet()) {
            if (!base.containsKey(c)) problems.add("loader class " + c + " is in the fix but not in the base");
        }
        for (Map.Entry<String, List<String>> e : base.entrySet()) {
            List<String> other = fix.get(e.getKey());
            if (other == null || other.equals(e.getValue())) continue;
            problems.add("loader class " + e.getKey() + " differs: " + firstDifference(e.getValue(), other));
        }
        return problems;
    }

    private static String firstDifference(List<String> a, List<String> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            if (!a.get(i).equals(b.get(i))) return "base '" + a.get(i) + "' / fix '" + b.get(i) + "'";
        }
        return "base has " + a.size() + " lines, fix has " + b.size();
    }

    /** Loader classes may refer only to loader classes, the framework and R8 synthetic helpers. */
    static List<String> illegalReferences(Map<String, List<String>> classes, List<Pattern> loader) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : classes.entrySet()) {
            Set<String> bad = new LinkedHashSet<>();
            for (String line : e.getValue()) {
                Matcher m = DESCRIPTOR.matcher(STRING_LITERAL.matcher(line).replaceAll(""));
                while (m.find()) {
                    String d = m.group();
                    if (!allowedReference(d, loader)) bad.add(d);
                }
            }
            if (!bad.isEmpty()) problems.add("loader class " + e.getKey() + " refers to non-loader classes " + bad);
        }
        return problems;
    }

    private static boolean allowedReference(String descriptor, List<Pattern> loader) {
        if (descriptor.equals(SYNTHETIC) || isLoader(loader, descriptor)) return true;
        for (String prefix : FRAMEWORK) if (descriptor.startsWith(prefix)) return true;
        return false;
    }

    /** Both rules, for the base against the fix. */
    static List<String> check(File baseApk, File fixApk, File baseMapping, File fixMapping, String dexdump, File config)
        throws IOException, InterruptedException {
        List<Pattern> loader = loaderPatterns(config);
        Dexes base = loaderClasses(baseApk, dexdump, loader, syntheticClasses(baseMapping));
        Dexes fix = loaderClasses(fixApk, dexdump, loader, syntheticClasses(fixMapping));
        if (base.loader.isEmpty()) return List.of("no loader classes found in " + baseApk + " (is dexdump the right version?)");
        List<String> problems = differences(base.loader, fix.loader);
        problems.addAll(illegalReferences(base.loader, loader));
        problems.addAll(illegalReferences(fix.loader, loader));
        problems.addAll(unsafeHelpers(base));
        problems.addAll(unsafeHelpers(fix));
        return problems;
    }
}
