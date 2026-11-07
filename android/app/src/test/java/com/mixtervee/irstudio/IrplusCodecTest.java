package com.mixtervee.irstudio;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class IrplusCodecTest {
    private static final String PRONTO =
        "0000 006D 0002 0000 0157 0057 0016 4DFA";

    @Test public void decodesRawProntoAndRejectsBadHeader() {
        IrplusCodec.Signal signal = IrplusCodec.decodePronto(PRONTO);
        assertTrue(signal.carrierHz >= 37000 && signal.carrierHz <= 39000);
        assertEquals(4, signal.microseconds.length);
        assertTrue(signal.microseconds[0] > 8000);
        try {
            IrplusCodec.decodePronto("0100 006D 0002 0000 0157 0057 0016 4DFA");
            fail("nonraw Pronto should be rejected");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void roundTripsRowsAndXmlLabels() throws Exception {
        IrplusCodec.Remote original = new IrplusCodec.Remote("Xflyee", "099");
        original.items.add(new IrplusCodec.Item("SET & Mode", PRONTO, 6, false));
        original.items.add(new IrplusCodec.Item("", "", 6, true));
        original.items.add(new IrplusCodec.Item("Alarm", PRONTO, 12, false));
        String xml = IrplusCodec.write(original);
        List<IrplusCodec.Remote> imported = IrplusCodec.read(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, imported.size());
        assertEquals(3, imported.get(0).items.size());
        assertTrue(imported.get(0).items.get(1).space);
        assertEquals(6, imported.get(0).items.get(1).span);
        assertEquals("SET & Mode", imported.get(0).items.get(0).label);
    }
}
