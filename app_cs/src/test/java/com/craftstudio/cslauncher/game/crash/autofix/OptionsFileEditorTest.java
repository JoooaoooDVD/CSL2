package com.craftstudio.cslauncher.game.crash.autofix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class OptionsFileEditorTest {
    private static final List<String> OPTIONS = Arrays.asList(
            "version:3955",
            "resourcePacks:[\"vanilla\",\"fabric\",\"file/Faithful-32x.zip\",\"file/Other Pack\"]",
            "incompatibleResourcePacks:[\"file/Faithful-32x.zip\"]",
            "lang:en_us");

    @Test public void removesNamedPackOnly() {
        OptionsFileEditor.Result r = OptionsFileEditor.disableResourcePack(OPTIONS, "Faithful-32x.zip");
        assertTrue(r.changed());
        assertEquals("version:3955", r.lines.get(0));
        assertEquals("resourcePacks:[\"vanilla\",\"fabric\",\"file/Other Pack\"]", r.lines.get(1));
        assertEquals("incompatibleResourcePacks:[]", r.lines.get(2));
        assertEquals("lang:en_us", r.lines.get(3));
        assertFalse(OptionsFileEditor.isEnabled(r.lines, "Faithful-32x.zip"));
        assertTrue(OptionsFileEditor.isEnabled(r.lines, "Other Pack"));
    }

    @Test public void emptyNameRemovesAllFilePacksButKeepsBuiltins() {
        OptionsFileEditor.Result r = OptionsFileEditor.disableResourcePack(OPTIONS, "");
        assertEquals("resourcePacks:[\"vanilla\",\"fabric\"]", r.lines.get(1));
        assertEquals(2, r.removedEntries.size());
    }

    @Test public void acceptsFilePrefixAndCaseDifferences() {
        OptionsFileEditor.Result r = OptionsFileEditor.disableResourcePack(OPTIONS, "file/faithful-32x.zip");
        assertTrue(r.changed());
        assertFalse(OptionsFileEditor.isEnabled(OPTIONS, "missing.zip"));
    }

    @Test public void parsesEscapedQuotes() {
        List<String> parsed = OptionsFileEditor.parseList("[\"a\\\"b\",\"c\"]");
        assertEquals(Arrays.asList("a\"b", "c"), parsed);
        assertEquals("[\"a\\\"b\",\"c\"]", OptionsFileEditor.toJsonList(parsed));
    }
}
