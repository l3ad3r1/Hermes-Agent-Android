package com.hermes.tools.tinker;

import com.tencent.tinker.build.apkparser.AndroidParser;
import com.tencent.tinker.build.patch.Configuration;
import com.tencent.tinker.build.patch.Runner;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hermes' tinker-patch-cli.
 *
 * <pre>
 *   patch --old base.apk --new fix.apk --config tinker_config.xml --out outDir
 *       Diffs the archived base against the fix build with tinker-patch-lib (dex, native libs,
 *       resources). Writes outDir/patch_unsigned.apk; signing is done afterwards by the
 *       hermes-tinker scripts (SHA-256 JAR signature with the release key), never here.
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
        HermesTinkerPatchCli cli = new HermesTinkerPatchCli();
        mBeginTime = System.currentTimeMillis();
        cli.mConfig = new Configuration(config, new File(out), oldApk, newApk);
        com.tencent.tinker.build.util.Logger.initLogger(cli.mConfig);
        try {
            cli.tinkerPatch();
        } finally {
            com.tencent.tinker.build.util.Logger.closeLogger();
        }
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
        System.err.println("       hermes-tinker-patch-cli info app.apk");
        System.exit(ERRNO_USAGE);
    }
}
