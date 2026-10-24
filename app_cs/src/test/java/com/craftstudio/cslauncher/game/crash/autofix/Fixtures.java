package com.craftstudio.cslauncher.game.crash.autofix;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Fixtures {
    private Fixtures() {}

    static List<String> log(String name) {
        InputStream in = Fixtures.class.getClassLoader().getResourceAsStream("crashlogs/" + name);
        if (in == null) throw new IllegalStateException("fixture missing: " + name);
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = r.readLine()) != null) lines.add(l);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return lines;
    }

    static CrashAnalysis analyze(String name, int code, boolean signal, String loader) {
        return CrashClassifier.analyze(new CrashClassifier.Input(log(name), code, signal, null, loader));
    }
}
