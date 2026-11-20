package com.mixtervee.irstudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** Offline conversion tests. Do NOT assume official app imports this file. */
public class OcrustarUirExporterTest {
    private static final String PRONTO =
            "0000 006D 0002 0000 0157 0057 0016 4DFA";

    @Test public void exportKnownProntoAsFrequencyAndPulseArrays() {
        IrplusCodec.Remote remote = new IrplusCodec.Remote("TCL", "Test TV");
        remote.items.add(new IrplusCodec.Item("Pwr \"On\"", PRONTO, 6, false));
        remote.items.add(new IrplusCodec.Item("", "", 6, true));
        remote.items.add(new IrplusCodec.Item("Not learned", "", 6, false));
        remote.items.add(new IrplusCodec.Item("VOL\\Up", PRONTO, 6, false));
        String json = OcrustarUirExporter.export(remote);
        assertTrue(json.contains("\"frequency\": 38000")
                || json.contains("\"frequency\": 38029"));
        assertTrue(json.contains("\"irkeys\": ["));
        assertTrue(json.contains("\"keyId\": 1"));
        assertTrue(json.contains("\"keyId\": 2"));
        assertTrue(json.contains("\"keyName\": \"Pwr \\\"On\\\"\""));
        assertTrue(json.contains("\"keyName\": \"VOL\\\\Up\""));
        assertTrue(json.contains("\"irData\": ["));
        assertFalse(json.contains("Not learned"));
        assertTrue(json.endsWith("}\n"));
    }

    @Test public void rejectsUnlearnedRemote() {
        IrplusCodec.Remote remote = new IrplusCodec.Remote("", "Empty");
        remote.items.add(new IrplusCodec.Item("Power", "", 12, false));
        try {
            OcrustarUirExporter.export(remote);
            fail("must reject empty IR");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("no learned IR"));
        }
    }

    @Test public void rejectsMixedCarrierInsteadOfMislabelingKeys() {
        IrplusCodec.Remote remote = new IrplusCodec.Remote("TCL", "Test TV");
        remote.items.add(new IrplusCodec.Item("Power", PRONTO, 12, false));
        remote.items.add(new IrplusCodec.Item("Other Carrier",
                "0000 0050 0002 0000 0157 0057 0016 4DFA", 12, false));
        try {
            OcrustarUirExporter.export(remote);
            fail("must reject mixed frequencies");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("different IR carrier"));
        }
    }

    @Test public void rejectsInvalidProntoRatherThanExportingBadIR() {
        IrplusCodec.Remote remote = new IrplusCodec.Remote("Xflyee", "099");
        remote.items.add(new IrplusCodec.Item("Power", "garbage", 12, false));
        try {
            OcrustarUirExporter.export(remote);
            fail("must reject malformed Pronto");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Power"));
        }
    }
}
