import com.craftstudio.cslauncher.game.branding.*;

import java.io.*;
import java.util.*;

/**
 * Desktop check for the CS Dynamic Branding generator against REAL client jars.
 * Usage: java -cp <classes>:gson.jar Harness <assetsDir> <workDir> <client1.jar> [client2.jar ...]
 * For every jar: reads version facts, generates the pack into <workDir>/<version>/resourcepacks,
 * generates again (must be reused), prints the pack.mcmeta and the written/skipped lists, then
 * exercises options.txt activation/deactivation round trips via GameOptionsFile.
 */
public class Harness {
    public static void main(String[] args) throws Exception {
        File assets = new File(args[0]);
        File work = new File(args[1]);
        BrandingAssetSource source = name -> {
            File f = new File(assets, name);
            if (!f.isFile()) throw new FileNotFoundException(name);
            return new FileInputStream(f);
        };
        BrandingPackGenerator gen = new BrandingPackGenerator(source);
        int failures = 0;
        for (int i = 2; i < args.length; i++) {
            File jar = new File(args[i]);
            System.out.println("=== " + jar);
            ClientJarFacts facts;
            try {
                facts = ClientJarFacts.read(jar, BrandingPackGenerator.candidateEntries());
            } catch (BrandingException e) {
                System.out.println("  facts: " + e.reason + " " + e.getMessage());
                continue;
            }
            System.out.println("  version=" + facts.versionId + " format=" + facts.packFormatLabel()
                    + " majorMinor=" + facts.majorMinorForm + " supported=" + facts.isSupported()
                    + " present=" + facts.presentEntries.size() + "/" + BrandingPackGenerator.candidateEntries().size()
                    + " langKeys=" + facts.englishKeys.size());
            File gameDir = new File(work, facts.versionId.replace('/', '_'));
            File packs = new File(gameDir, "resourcepacks");
            long t0 = System.currentTimeMillis();
            BrandingPackGenerator.Result r;
            try {
                r = gen.generate(jar, facts, packs);
            } catch (BrandingException e) {
                System.out.println("  generate: " + e.reason + " " + e.getMessage());
                if (facts.isSupported()) failures++;
                continue;
            }
            long t1 = System.currentTimeMillis();
            System.out.println("  generated in " + (t1 - t0) + " ms, reused=" + r.reused + ", files=" + r.written.size()
                    + ", skipped=" + r.skipped + ", lang=" + r.langOverrides + ", size=" + dirSize(r.packDir) / 1024 + " KB");
            System.out.println("  pack.mcmeta: " + BrandingPackGenerator.readTextForTest(new File(r.packDir, "pack.mcmeta")));
            long t2 = System.currentTimeMillis();
            BrandingPackGenerator.Result again = gen.generate(jar, facts, packs);
            long t3 = System.currentTimeMillis();
            System.out.println("  second run reused=" + again.reused + " in " + (t3 - t2) + " ms");
            if (!again.reused) failures++;
            // marker must be present and listed files must exist
            for (String f : r.written) if (!new File(r.packDir, f).isFile()) { System.out.println("  MISSING " + f); failures++; }
            // widget sprites must carry OUR nine-slice metadata (pill borders), not the vanilla copy
            String btnMeta = BrandingPackGenerator.readTextForTest(new File(r.packDir, "assets/minecraft/textures/gui/sprites/widget/button.png.mcmeta"));
            if (!btnMeta.contains("\"left\":10")) { System.out.println("  BUTTON MCMETA NOT OURS: " + btnMeta); failures++; }

            // scoped generation: "home screen only" must rebuild (different stamp) and contain only menu files
            EnumSet<BrandingPart> menuOnly = BrandingPart.menuOnly();
            BrandingPackGenerator.Result scoped = gen.generate(jar, facts, packs, menuOnly);
            boolean scopedOk = !scoped.reused && scoped.stamp.endsWith(":10000");
            for (String f : scoped.written) {
                if (f.contains("/sprites/widget/") || f.contains("menu_background") || f.endsWith("en_us.json")) { System.out.println("  SCOPED PACK LEAKED " + f); scopedOk = false; }
            }
            if (!new File(scoped.packDir, "assets/minecraft/textures/gui/title/minecraft.png").isFile()) scopedOk = false;
            if (new File(scoped.packDir, "assets/minecraft/textures/gui/sprites/widget/button.png").isFile()) scopedOk = false;
            System.out.println("  scoped (menu only): files=" + scoped.written.size() + " stamp=" + scoped.stamp + " ok=" + scopedOk);
            if (!scopedOk) failures++;
            // switching back to everything regenerates again and is then reusable
            BrandingPackGenerator.Result back = gen.generate(jar, facts, packs, BrandingPart.all());
            if (back.reused || !gen.generate(jar, facts, packs, BrandingPart.all()).reused) { System.out.println("  SWITCH BACK FAILED"); failures++; }
            // dark-loading-only selection needs no pack at all
            if (BrandingPart.needsPack(EnumSet.of(BrandingPart.DARK_LOADING))) { System.out.println("  needsPack(dark only) wrong"); failures++; }
            if (!"menu".equals(BrandingPart.serialize(menuOnly)) || BrandingPart.serialize(BrandingPart.all()) != null
                    || !BrandingPart.parse("bogus, ,").equals(BrandingPart.all()) || !BrandingPart.parse("widgets,menu").equals(EnumSet.of(BrandingPart.MENU, BrandingPart.WIDGETS))) {
                System.out.println("  BrandingPart (de)serialization wrong"); failures++;
            }

            // options.txt round trip
            File opts = new File(gameDir, "options.txt");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(opts), "UTF-8")) {
                w.write("version:3955\nautoJump:false\nresourcePacks:[\"vanilla\",\"file/UserPack.zip\",\"fabric\"]\n"
                        + "incompatibleResourcePacks:[\"file/CS Launcher Plus\"]\nlang:en_us\nrenderDistance:8\n");
            }
            GameOptionsFile o = new GameOptionsFile(opts);
            List<String> packsList = o.getList(BrandingPack.OPT_RESOURCE_PACKS);
            packsList.remove(BrandingPack.PACK_ENTRY); packsList.add(BrandingPack.PACK_ENTRY);
            o.setList(BrandingPack.OPT_RESOURCE_PACKS, packsList);
            List<String> inc = o.getList(BrandingPack.OPT_INCOMPATIBLE_PACKS); inc.remove(BrandingPack.PACK_ENTRY);
            o.setList(BrandingPack.OPT_INCOMPATIBLE_PACKS, inc);
            o.setRaw(BrandingPack.OPT_DARK_LOADING, "true");
            o.save();
            String after = BrandingPackGenerator.readTextForTest(opts);
            System.out.println("  options after activate:\n" + indent(after));
            boolean ok = after.contains("resourcePacks:[\"vanilla\",\"file/UserPack.zip\",\"fabric\",\"file/CS Launcher Plus\"]")
                    && after.contains("incompatibleResourcePacks:[]") && after.contains("darkMojangStudiosBackgroundColor:true")
                    && after.startsWith("version:3955\nautoJump:false\n") && after.contains("lang:en_us\nrenderDistance:8\n");
            if (!ok) { System.out.println("  OPTIONS ACTIVATE CHECK FAILED"); failures++; }
            GameOptionsFile o2 = new GameOptionsFile(opts);
            List<String> l2 = o2.getList(BrandingPack.OPT_RESOURCE_PACKS); l2.remove(BrandingPack.PACK_ENTRY);
            o2.setList(BrandingPack.OPT_RESOURCE_PACKS, l2); o2.remove(BrandingPack.OPT_DARK_LOADING); o2.save();
            String restored = BrandingPackGenerator.readTextForTest(opts);
            boolean ok2 = restored.equals("version:3955\nautoJump:false\nresourcePacks:[\"vanilla\",\"file/UserPack.zip\",\"fabric\"]\n"
                    + "incompatibleResourcePacks:[]\nlang:en_us\nrenderDistance:8\n");
            System.out.println("  options restore exact=" + ok2);
            if (!ok2) failures++;
        }
        // foreign folder must never be replaced
        File foreign = new File(work, "foreign/resourcepacks/" + BrandingPack.DIR_NAME);
        foreign.mkdirs();
        try (Writer w = new FileWriter(new File(foreign, "pack.mcmeta"))) { w.write("{}"); }
        try {
            ClientJarFacts facts = ClientJarFacts.read(new File(args[2]), BrandingPackGenerator.candidateEntries());
            gen.generate(new File(args[2]), facts, foreign.getParentFile());
            System.out.println("FOREIGN FOLDER WAS REPLACED (bad)"); failures++;
        } catch (BrandingException e) {
            System.out.println("foreign folder protected: " + e.reason + " " + e.getMessage());
        }
        System.out.println(failures == 0 ? "ALL CHECKS PASSED" : "FAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    static long dirSize(File f) {
        if (f.isFile()) return f.length();
        long s = 0; File[] c = f.listFiles(); if (c != null) for (File x : c) s += dirSize(x); return s;
    }
    static String indent(String s) { return "    " + s.replace("\n", "\n    "); }
}
