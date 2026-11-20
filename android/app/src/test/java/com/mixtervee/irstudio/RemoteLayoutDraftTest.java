package com.mixtervee.irstudio;

import org.junit.Test;
import java.util.ArrayList;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class RemoteLayoutDraftTest {
    private static final String ONE =
            "0000 006D 0002 0000 0157 0057 0016 4DFA";
    private static final String TWO =
            "0000 006D 0002 0000 0157 0058 0016 4DFA";

    @Test public void stagedOrderAndWidthCancelByDefault() {
        IrplusCodec.Remote remote = new IrplusCodec.Remote("", "TV");
        IrplusCodec.Item power = new IrplusCodec.Item("Power", ONE, 6, false);
        IrplusCodec.Item input = new IrplusCodec.Item("Input", TWO, 6, false);
        remote.items.add(power);
        remote.items.add(input);
        RemoteLayoutDraft draft = new RemoteLayoutDraft(remote);
        assertEquals(0, draft.move(1, -1));
        draft.setWidth(0, 12);
        assertEquals("Input", draft.get(0).label);
        assertEquals(12, draft.get(0).span);
        assertTrue(draft.preview().startsWith("Input [12]"));
        // No Save yet: original ordering and widths are intact.
        assertSame(power, remote.items.get(0));
        assertSame(input, remote.items.get(1));
        draft.applyTo(remote);
        assertEquals("Input", remote.items.get(0).label);
        assertEquals(12, remote.items.get(0).span);
    }

    @Test public void duplicateSamplesCanBeUndoneWithoutDeletingButton() {
        IrplusCodec.Item item = new IrplusCodec.Item("Select", ONE, 6, false);
        item = item.addCapture(ONE);
        assertEquals("Verified", item.sampleStatus());
        item = item.addCapture(TWO);
        assertEquals("Mismatch", item.sampleStatus());
        IrplusCodec.Item best = item.selectBestCapture();
        assertEquals(3, best.captures.size());
        assertEquals(ONE, best.prontoHex);
        IrplusCodec.Item undone = item.removeLastCapture();
        assertEquals("Verified", undone.sampleStatus());
        assertEquals(ONE, undone.prontoHex);
        IrplusCodec.Item cleared = undone.clearCaptures();
        assertEquals("Select", cleared.label);
        assertEquals(6, cleared.span);
        assertEquals("Not learned", cleared.sampleStatus());
        assertEquals("", cleared.prontoHex);
    }

    @Test public void unlearnedButtonsRemainLocalButAreSkippedForIrplusExport()
            throws Exception {
        IrplusCodec.Remote remote = new IrplusCodec.Remote("Xflyee", "Clock");
        remote.items.add(new IrplusCodec.Item("Unlearned", "", 6, false));
        remote.items.add(new IrplusCodec.Item("Working", ONE, 6, false));
        String output = IrplusCodec.write(remote);
        assertFalse(output.contains("Unlearned"));
        assertTrue(output.contains("Working"));
        assertEquals("Working", IrplusCodec.read(
                new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)))
                .get(0).items.get(0).label);
    }

    @Test public void preservesSampleHistoryWhenRenamingOrResizing() {
        IrplusCodec.Item item = new IrplusCodec.Item("Power", ONE, 6, false);
        item = item.addCapture(TWO);
        IrplusCodec.Item renamed = item.withLabel("Power On").withSpan(12);
        assertEquals("Power On", renamed.label);
        assertEquals(12, renamed.span);
        assertEquals(2, renamed.captures.size());
        assertEquals(TWO, renamed.prontoHex);
    }

    @Test public void invalidMovesAndWidthsCannotCorruptLayout() {
        IrplusCodec.Remote remote = new IrplusCodec.Remote("", "TV");
        remote.items.add(new IrplusCodec.Item("Power", ONE, 6, false));
        RemoteLayoutDraft draft = new RemoteLayoutDraft(remote);
        assertEquals(0, draft.move(0, -1));
        try {
            draft.setWidth(0, 13);
            fail("Expected rejection");
        } catch (IllegalArgumentException ignored) { }
        assertEquals(6, draft.get(0).span);
    }
}
