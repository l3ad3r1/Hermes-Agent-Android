package com.hermes.tools.tinker;

import com.tencent.tinker.build.apkparser.AndroidParser;
import com.tencent.tinker.build.patch.Configuration;
import com.tencent.tinker.build.patch.Runner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hermes' tinker-patch-cli.
 *
 * <pre>
 *   patch --old base.apk --new fix.apk --config tinker_config.xml --out outDir
 *       Diffs the archived base against the fix build with tinker-patch-lib (dex, native libs,
 *       resources). Writes outDir/patch_unsigned.apk; signing is done afterwards by the
 *       hermes-tinker scripts (SHA-256 JAR signature with the release key), never here.
 *       With --old-mapping, --new-mapping and --dexdump (a release build) it first runs the R8-aware
 *       loader-class and manifest checks (LoaderClassCheck, ManifestCheck) and, only if they pass, lets
 *       tinker-patch-lib run with ignoreWarning=true; see docs/TINKER-HOTFIX.md, "R8 and loader classes".
 *   check --old base.apk --new fix.apk --config tinker_config.xml --old-mapping m --new-mapping m --dexdump exe
 *       Runs only those two checks and prints what they found.
 *   info app.apk
 *       Prints TINKER_ID, package, versionCode and versionName as read from the APK's binary
 *       manifest by the same parser tinker-patch-lib uses, as key=value lines.
 * </pre>
 *
 * Exit status: 0 on success, 1 on a patch error, 2 on bad usage.
 */
public final class HermesTinkerPatchCli extends Runner {

    private HermesTinkerPatchCli() {
        super(false);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        try {
            switch (args[0]) {
                case "patch":
                    patch(options(args));
                    break;
                case "check":
                    check(options(args));
                    break;
                case "info":
                    if (args.length != 2) {
                        usage();
                        return;
                    }
                    info(new File(args[1]));
                    break;
                default:
                    usage();
            }
        } catch (IllegalArgumentException e) {
            System.err.println("error: " + e.getMessage());
            usage();
        } catch (Exception e) {
            e.printStackTrace(System.err);
            System.exit(ERRNO_ERRORS);
        }
    }

    private static void patch(Map<String, String> o) throws Exception {
        File oldApk = requireFile(o, "--old");
        File newApk = requireFile(o, "--new");
        File config = requireFile(o, "--config");
        String out = o.get("--out");
        if (out == null) throw new IllegalArgumentException("--out is required");
        File effectiveConfig = config;
        if (hasRelease(o)) {
            runReleaseChecks(o, oldApk, newApk, config);
            effectiveConfig = withIgnoreWarning(config);
        }
        try {
            HermesTinkerPatchCli cli = new HermesTinkerPatchCli();
            mBeginTime = System.currentTimeMillis();
            cli.mConfig = new Configuration(effectiveConfig, new File(out), oldApk, newApk);
            com.tencent.tinker.build.util.Logger.initLogger(cli.mConfig);
            try {
                cli.tinkerPatch();
            } finally {
                com.tencent.tinker.build.util.Logger.closeLogger();
            }
        } finally {
            if (effectiveConfig != config) effectiveConfig.delete();
        }
    }

    private static void check(Map<String, String> o) throws Exception {
        if (!hasRelease(o)) throw new IllegalArgumentException("--old-mapping, --new-mapping and --dexdump are required");
        runReleaseChecks(o, requireFile(o, "--old"), requireFile(o, "--new"), requireFile(o, "--config"));
        System.out.println("loader classes and manifest are consistent between base and fix");
    }

    private static boolean hasRelease(Map<String, String> o) {
        boolean any = o.containsKey("--old-mapping") || o.containsKey("--new-mapping") || o.containsKey("--dexdump");
        if (any && !(o.containsKey("--old-mapping") && o.containsKey("--new-mapping") && o.containsKey("--dexdump"))) {
            throw new IllegalArgumentException("--old-mapping, --new-mapping and --dexdump go together");
        }
        return any;
    }

    /**
     * tinker-patch-lib's own loader-class and manifest checks cannot pass on R8 output, so a release
     * build runs ours instead, then lets the patch library run without them. Exits 1 on any finding.
     */
    private static void runReleaseChecks(Map<String, String> o, File oldApk, File newApk, File config) throws Exception {
        File oldMapping = requireFile(o, "--old-mapping");
        File newMapping = requireFile(o, "--new-mapping");
        String dexdump = o.get("--dexdump");
        if (!new File(dexdump).isFile()) throw new IllegalArgumentException("--dexdump " + dexdump + " does not exist");
        List<String> problems = new ArrayList<>(LoaderClassCheck.check(oldApk, newApk, oldMapping, newMapping, dexdump, config));
        problems.addAll(ManifestCheck.check(oldApk, newApk));
        if (!problems.isEmpty()) {
            System.err.println("the fix cannot be delivered as a patch:");
            for (String p : problems) System.err.println("  - " + p);
            System.exit(ERRNO_ERRORS);
        }
    }

    /** A copy of the config with ignoreWarning on; the checks it would have made were just made above. */
    private static File withIgnoreWarning(File config) throws IOException {
        String xml = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
        String patched = xml.replaceAll("<ignoreWarning\\s+value=\"false\"\\s*/>", "<ignoreWarning value=\"true\"/>");
        if (patched.equals(xml)) throw new IOException(config + " has no <ignoreWarning value=\"false\"/> to relax");
        File tmp = File.createTempFile("hermes-tinker-config", ".xml");
        Files.write(tmp.toPath(), patched.getBytes(StandardCharsets.UTF_8));
        return tmp;
    }

    static Map<String, String> info(File apk) throws Exception {
        AndroidParser manifest = AndroidParser.getAndroidManifest(apk);
        Map<String, String> info = new LinkedHashMap<>();
        info.put("tinkerId", String.valueOf(manifest.metaDatas.get("TINKER_ID")));
        info.put("packageName", manifest.apkMeta.getPackageName());
        info.put("versionCode", String.valueOf(manifest.apkMeta.getVersionCode()));
        info.put("versionName", manifest.apkMeta.getVersionName());
        for (Map.Entry<String, String> e : info.entrySet()) {
            System.out.println(e.getKey() + "=" + e.getValue());
        }
        return info;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> o = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("bad argument " + args[i]);
            }
            o.put(args[i], args[i + 1]);
        }
        return o;
    }

    private static File requireFile(Map<String, String> o, String key) {
        String path = o.get(key);
        if (path == null) throw new IllegalArgumentException(key + " is required");
        File f = new File(path);
        if (!f.isFile()) throw new IllegalArgumentException(key + " " + path + " does not exist");
        return f;
    }

    private static void usage() {
        System.err.println("usage: hermes-tinker-patch-cli patch --old base.apk --new fix.apk --config tinker_config.xml --out dir");
        System.err.println("         [--old-mapping base-mapping.txt --new-mapping fix-mapping.txt --dexdump dexdump]  (release builds)");
        System.err.println("       hermes-tinker-patch-cli check --old base.apk --new fix.apk --config tinker_config.xml --old-mapping m --new-mapping m --dexdump exe");
        System.err.println("       hermes-tinker-patch-cli info app.apk");
        System.exit(ERRNO_USAGE);
    }
}
