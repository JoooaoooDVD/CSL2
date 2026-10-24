package com.craftstudio.cslauncher.game.crash.autofix;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.craftstudio.cslauncher.game.crash.autofix.ai.AiDiagnosis;
import com.craftstudio.cslauncher.game.crash.autofix.ai.AiRequest;
import com.craftstudio.cslauncher.game.crash.autofix.ai.AiResponseValidator;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class AiResponseValidatorTest {
    private final AiRequest req = new AiRequest("fabric", "1.21.1", 1, false,
            Arrays.asList("sodium", "lithium"), Collections.singletonList("file/Faithful.zip"), Collections.singletonList("sodium-extra.json"));

    @Test public void acceptsWellFormedAnswer() throws Exception {
        AiDiagnosis d = AiResponseValidator.validate("{\"category\":\"MOD_CONFLICT\",\"confidence\":0.8,\"explanation\":\"x\",\"component\":\"sodium\","
                + "\"actions\":[{\"kind\":\"DISABLE_MOD\",\"target\":\"sodium\",\"detail\":\"\"}]}", req);
        assertEquals(CrashCategory.MOD_CONFLICT, d.category);
        assertEquals(1, d.actions.size());
        assertEquals(RepairStep.Kind.DISABLE_MOD, d.actions.get(0).kind);
    }

    @Test public void acceptsFencedJson() throws Exception {
        AiDiagnosis d = AiResponseValidator.validate("```json\n{\"category\":\"UNKNOWN_ERROR\",\"confidence\":0.1,\"explanation\":\"x\"}\n```", req);
        assertEquals(CrashCategory.UNKNOWN_ERROR, d.category);
        assertTrue(d.actions.isEmpty());
    }

    private void expectInvalid(String json) {
        try {
            AiResponseValidator.validate(json, req);
            fail("accepted: " + json);
        } catch (AiResponseValidator.InvalidResponse expected) {
            // ok
        }
    }

    @Test public void rejectsEverythingOffSchema() {
        expectInvalid("");
        expectInvalid("not json");
        expectInvalid("[]");
        expectInvalid("{\"category\":\"MADE_UP\",\"confidence\":0.5,\"explanation\":\"x\"}");
        expectInvalid("{\"category\":\"MOD_CONFLICT\",\"confidence\":1.5,\"explanation\":\"x\"}");
        expectInvalid("{\"category\":\"MOD_CONFLICT\",\"confidence\":0.5,\"explanation\":\"x\",\"shell\":\"rm -rf\"}");
        expectInvalid("{\"category\":\"MOD_CONFLICT\",\"confidence\":0.5,\"explanation\":\"x\",\"actions\":[{\"kind\":\"DELETE_WORLD\",\"target\":\"x\"}]}");
        // mod not installed → not allowed
        expectInvalid("{\"category\":\"MOD_CONFLICT\",\"confidence\":0.5,\"explanation\":\"x\",\"actions\":[{\"kind\":\"DISABLE_MOD\",\"target\":\"iris\"}]}");
        // config traversal / unknown file
        expectInvalid("{\"category\":\"CORRUPTED_CONFIGURATION\",\"confidence\":0.5,\"explanation\":\"x\",\"actions\":[{\"kind\":\"RESET_CONFIG\",\"target\":\"../options.txt\"}]}");
        // jvm token must look like an option
        expectInvalid("{\"category\":\"INVALID_JVM_ARGUMENT\",\"confidence\":0.5,\"explanation\":\"x\",\"actions\":[{\"kind\":\"PRUNE_JVM_ARGS\",\"target\":\"rm -rf /\"}]}");
        // too many actions
        expectInvalid("{\"category\":\"MOD_CONFLICT\",\"confidence\":0.5,\"explanation\":\"x\",\"actions\":[{\"kind\":\"CHECK_NETWORK\"},{\"kind\":\"CHECK_NETWORK\"},{\"kind\":\"CHECK_NETWORK\"},{\"kind\":\"CHECK_NETWORK\"}]}");
    }
}
