package com.hermes.tools.tinker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.Test;

/** The R8-aware loader-class check on small dexdump excerpts. */
public class LoaderClassCheckTest {

    private static final String CONFIG = "<tinkerPatch><issue id=\"dex\">"
        + "<!-- <loader value=\"commented.out.*\"/> -->"
        + "<loader value=\"com.tencent.tinker.loader.*\"/>"
        + "<loader value=\"dagger.hilt.internal.GeneratedComponentManager\"/>"
        + "</issue></tinkerPatch>";

    private static List<Pattern> loader() throws IOException {
        File f = File.createTempFile("tinker_config", ".xml");
        try {
            Files.write(f.toPath(), CONFIG.getBytes(StandardCharsets.UTF_8));
            return LoaderClassCheck.loaderPatterns(f);
        } finally {
            f.delete();
        }
    }

    private static Set<String> synthetic(String... descriptors) {
        Set<String> s = new LinkedHashSet<>();
        for (String d : descriptors) s.add(d);
        return s;
    }

    /** A dexdump excerpt: a loader class that logs through a helper, then an app class. */
    private static String dump(String helper, String method, String message, int offset) {
        return "  Class descriptor  : 'Lcom/tencent/tinker/loader/TinkerLoader;'\n"
            + "  source_file_idx   : 30538 (r8-map-id-aaaa)\n"
            + "    #0              : (in Lcom/tencent/tinker/loader/TinkerLoader;)\n"
            + String.format("%06x:                                        |[%06x] com.tencent.tinker.loader.TinkerLoader.tryLoad:()V\n", offset, offset)
            + String.format("%06x: 1a00 4d0f                          |0000: const-string v0, \"%s\" // string@%04x\n", offset + 2, message, offset & 0xffff)
            + String.format("%06x: 7110 %04x 0000                     |0002: invoke-static {v0}, L%s;.%s:(Ljava/lang/String;)V // method@%04x\n",
                offset + 4, offset & 0xffff, helper, method, offset & 0xffff)
            + String.format("%06x: 0e00                               |0005: return-void\n", offset + 10)
            + String.format("%06x: 0003 0200 1000 0000 3000 3100 3200 ... |000a: array-data (20 units)\n", offset + 12)
            + "    catches       : (none)\n"
            + "    positions     : \n"
            + "      0x0000 line=12\n"
            + "  Class descriptor  : 'Lcom/hermes/agent/App;'\n"
            + "    #0              : (in Lcom/hermes/agent/App;)\n"
            + "0000a0: 0e00                               |0000: return-void\n";
    }

    private static LoaderClassCheck.Dexes dexes(String text, Set<String> synthetic) throws Exception {
        LoaderClassCheck.Dexes out = new LoaderClassCheck.Dexes();
        LoaderClassCheck.parse(new BufferedReader(new StringReader(text)), loader(), synthetic, out);
        return out;
    }

    private static Map<String, List<String>> parse(String text, Set<String> synthetic) throws Exception {
        return dexes(text, synthetic).loader;
    }

    /** An R8 helper class whose one method refers to the given class (or only to the framework). */
    private static String helperClass(String descriptor, String refersTo) {
        return "  Class descriptor  : '" + descriptor + "'\n"
            + "  Superclass        : 'Ljava/lang/Object;'\n"
            + "    #0              : (in " + descriptor + ")\n"
            + "0000b0: 1a00 4d0f                          |0000: const-string v0, \"x\" // string@0001\n"
            + "0000b4: 6e10 0100 0000                     |0002: invoke-virtual {v0}, " + refersTo + ".length:()I // method@0002\n"
            + "0000ba: 0e00                               |0005: return-void\n";
    }

    @Test
    public void onlyLoaderClassesAreKept() throws Exception {
        Map<String, List<String>> classes = parse(dump("wm", "y", "hello", 0x5000), synthetic("Lwm;"));
        assertEquals(1, classes.size());
        assertTrue(classes.containsKey("Lcom/tencent/tinker/loader/TinkerLoader;"));
    }

    @Test
    public void aRenamedAndMovedHelperIsTheSameCall() throws Exception {
        Map<String, List<String>> base = parse(dump("wm", "y", "hello", 0x533750), synthetic("Lwm;"));
        Map<String, List<String>> fix = parse(dump("ks5", "v", "hello", 0x4d3f2c), synthetic("Lks5;"));
        assertEquals(List.of(), LoaderClassCheck.differences(base, fix));
    }

    @Test
    public void aChangedStringInALoaderClassIsFound() throws Exception {
        Map<String, List<String>> base = parse(dump("wm", "y", "hello", 0x5000), synthetic("Lwm;"));
        Map<String, List<String>> fix = parse(dump("ks5", "v", "goodbye", 0x5000), synthetic("Lks5;"));
        List<String> problems = LoaderClassCheck.differences(base, fix);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0), problems.get(0).contains("TinkerLoader"));
    }

    @Test
    public void aCallToAnAppClassThatLooksLikeAHelperIsNotExcused() throws Exception {
        // "ks5" is not named as an R8 synthetic in the fix's mapping, so the call really changed.
        Map<String, List<String>> base = parse(dump("wm", "y", "hello", 0x5000), synthetic("Lwm;"));
        Map<String, List<String>> fix = parse(dump("ks5", "v", "hello", 0x5000), synthetic());
        assertEquals(1, LoaderClassCheck.differences(base, fix).size());
    }

    @Test
    public void aMissingOrAddedLoaderClassIsFound() throws Exception {
        Map<String, List<String>> base = parse(dump("wm", "y", "hello", 0x5000), synthetic("Lwm;"));
        assertEquals(1, LoaderClassCheck.differences(base, new LinkedHashMap<>()).size());
        assertEquals(1, LoaderClassCheck.differences(new LinkedHashMap<>(), base).size());
    }

    @Test
    public void loaderClassesMayCallFrameworkLoaderAndSyntheticClassesOnly() throws Exception {
        Map<String, List<String>> ok = parse(dump("wm", "y", "hello", 0x5000), synthetic("Lwm;"));
        assertEquals(List.of(), LoaderClassCheck.illegalReferences(ok, loader()));

        // Same excerpt, but the helper is not an R8 synthetic: now it is an app class the loader calls.
        Map<String, List<String>> bad = parse(dump("wm", "y", "hello", 0x5000), synthetic());
        List<String> problems = LoaderClassCheck.illegalReferences(bad, loader());
        assertEquals(1, problems.size());
        assertTrue(problems.get(0), problems.get(0).contains("Lwm;"));
    }

    @Test
    public void classNamesInsideStringsAreNotReferences() throws Exception {
        Map<String, List<String>> classes = parse(dump("wm", "y", "see Lcom/hermes/agent/Other; here", 0x5000), synthetic("Lwm;"));
        assertEquals(List.of(), LoaderClassCheck.illegalReferences(classes, loader()));
    }

    @Test
    public void loaderPatternsMatchWildcardsAndExactNamesAndSkipComments() throws Exception {
        List<Pattern> p = loader();
        assertTrue(LoaderClassCheck.isLoader(p, "Lcom/tencent/tinker/loader/app/TinkerApplication;"));
        assertTrue(LoaderClassCheck.isLoader(p, "Ldagger/hilt/internal/GeneratedComponentManager;"));
        assertFalse(LoaderClassCheck.isLoader(p, "Ldagger/hilt/internal/GeneratedComponentManagerHolder;"));
        assertFalse(LoaderClassCheck.isLoader(p, "Lcommented/out/Thing;"));
        assertFalse(LoaderClassCheck.isLoader(p, "Lcom/hermes/agent/App;"));
    }

    @Test
    public void r8SyntheticNames() {
        assertTrue(LoaderClassCheck.isR8Synthetic("androidx.collection.ArrayMap$$ExternalSyntheticBUOutline0"));
        assertTrue(LoaderClassCheck.isR8Synthetic("androidx.work.OperationKt$$ExternalSyntheticLambda0"));
        assertTrue(LoaderClassCheck.isR8Synthetic("androidx.compose.animation.core.AnimationEndReason$EnumUnboxingSharedUtility"));
        assertFalse(LoaderClassCheck.isR8Synthetic("com.hermes.agent.data.Repository"));
    }

    @Test
    public void manifestMaskHidesOnlyTheTinkerIds() {
        String base = "<meta-data name=\"TINKER_ID\" value=\"hermes-91-aaaa\"/><activity name=\".Main\"/>";
        String fix = "<meta-data name=\"TINKER_ID\" value=\"hermes-91-bbbb\"/><activity name=\".Main\"/>";
        assertEquals(ManifestCheck.mask(base, "hermes-91-aaaa", "hermes-91-bbbb"), ManifestCheck.mask(fix, "hermes-91-aaaa", "hermes-91-bbbb"));
        String extra = fix + "<activity name=\".New\"/>";
        assertFalse(ManifestCheck.mask(base, "hermes-91-aaaa", "hermes-91-bbbb").equals(ManifestCheck.mask(extra, "hermes-91-aaaa", "hermes-91-bbbb")));
    }

    @Test
    public void aHelperThatOnlyUsesTheFrameworkIsSafe() throws Exception {
        LoaderClassCheck.Dexes d = dexes(dump("wm", "y", "hello", 0x5000) + helperClass("Lwm;", "Ljava/lang/String;"), synthetic("Lwm;"));
        assertEquals(synthetic("Lwm;"), d.called);
        assertEquals(List.of(), LoaderClassCheck.unsafeHelpers(d));
    }

    @Test
    public void aHelperThatReferencesAnAppClassIsNotSafe() throws Exception {
        // Loading it in the original class loader would load that app class there too.
        LoaderClassCheck.Dexes d = dexes(dump("wm", "y", "hello", 0x5000) + helperClass("Lwm;", "Lcom/hermes/agent/domain/tool/ToolResult;"), synthetic("Lwm;"));
        List<String> problems = LoaderClassCheck.unsafeHelpers(d);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0), problems.get(0).contains("ToolResult"));
    }

    @Test
    public void aHelperIsOnlyAsSafeAsTheHelpersItCalls() throws Exception {
        LoaderClassCheck.Dexes d = dexes(dump("wm", "y", "hello", 0x5000)
            + helperClass("Lwm;", "Lgq1;") + helperClass("Lgq1;", "Lcom/hermes/agent/App;"), synthetic("Lwm;", "Lgq1;"));
        assertEquals(1, LoaderClassCheck.unsafeHelpers(d).size());
        LoaderClassCheck.Dexes ok = dexes(dump("wm", "y", "hello", 0x5000)
            + helperClass("Lwm;", "Lgq1;") + helperClass("Lgq1;", "Ljava/lang/String;"), synthetic("Lwm;", "Lgq1;"));
        assertEquals(List.of(), LoaderClassCheck.unsafeHelpers(ok));
    }

    @Test
    public void aHelperTheLoaderNeverCallsIsNotChecked() throws Exception {
        LoaderClassCheck.Dexes d = dexes(dump("wm", "y", "hello", 0x5000) + helperClass("Lwm;", "Ljava/lang/String;")
            + helperClass("Lgq1;", "Lcom/hermes/agent/App;"), synthetic("Lwm;", "Lgq1;"));
        assertEquals(List.of(), LoaderClassCheck.unsafeHelpers(d));
    }
}
