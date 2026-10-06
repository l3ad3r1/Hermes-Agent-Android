package com.hermes.tools.tinker;

import com.tencent.tinker.build.apkparser.AndroidParser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A patch cannot carry manifest changes (new activities, services, receivers, providers, permissions,
 * anything else), so the fix's manifest must equal the base's apart from the TINKER_ID the two builds
 * necessarily differ in. tinker-patch-lib checks the component lists, but only as long as it is not told
 * to ignore warnings, which a release build has to be (see {@link LoaderClassCheck}); this keeps that
 * guarantee, and makes it stricter: the whole decoded manifest is compared.
 */
final class ManifestCheck {

    private ManifestCheck() {}

    static List<String> check(File baseApk, File fixApk) throws Exception {
        AndroidParser base = AndroidParser.getAndroidManifest(baseApk);
        AndroidParser fix = AndroidParser.getAndroidManifest(fixApk);
        List<String> problems = new ArrayList<>();
        compare("activities", base.activities, fix.activities, problems);
        compare("services", base.services, fix.services, problems);
        compare("receivers", base.receivers, fix.receivers, problems);
        compare("providers", base.providers, fix.providers, problems);
        String baseId = String.valueOf(base.metaDatas.get("TINKER_ID"));
        String fixId = String.valueOf(fix.metaDatas.get("TINKER_ID"));
        String a = mask(base.xml, baseId, fixId);
        String b = mask(fix.xml, baseId, fixId);
        if (!a.equals(b)) problems.add("AndroidManifest.xml differs from the base's apart from TINKER_ID: " + firstDifference(a, b));
        return problems;
    }

    private static void compare(String what, List<String> base, List<String> fix, List<String> problems) {
        if (!base.equals(fix)) problems.add("manifest " + what + " differ: base " + base + " / fix " + fix);
    }

    static String mask(String xml, String... tinkerIds) {
        String out = xml;
        for (String id : tinkerIds) {
            if (id != null && !id.isEmpty() && !id.equals("null")) out = out.replace(id, "@TINKER_ID@");
        }
        return out;
    }

    private static String firstDifference(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) i++;
        int from = Math.max(0, i - 40);
        return "base ..." + a.substring(from, Math.min(a.length(), i + 60)).replace('\n', ' ')
            + " / fix ..." + b.substring(from, Math.min(b.length(), i + 60)).replace('\n', ' ');
    }
}
