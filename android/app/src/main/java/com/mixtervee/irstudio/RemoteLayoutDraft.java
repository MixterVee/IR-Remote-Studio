package com.mixtervee.irstudio;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Staged Android layout editor, with no device access or persistence. */
public final class RemoteLayoutDraft {
    private final List<IrplusCodec.Item> working;

    public RemoteLayoutDraft(IrplusCodec.Remote remote) {
        working = new ArrayList<>(remote.items);
    }

    public int size() { return working.size(); }

    public IrplusCodec.Item get(int index) { return working.get(index); }

    public List<IrplusCodec.Item> items() {
        return Collections.unmodifiableList(working);
    }

    public int move(int index, int offset) {
        int destination = index + offset;
        if (index < 0 || index >= size() || (offset != -1 && offset != 1)
                || destination < 0 || destination >= size()) return index;
        Collections.swap(working, index, destination);
        return destination;
    }

    public void setWidth(int index, int width) {
        if (width < 1 || width > 12) throw new IllegalArgumentException("Width is 1-12.");
        working.set(index, get(index).withSpan(width));
    }

    /** Text preview uses the same twelve-column grouping as IRplus. */
    public String preview() {
        StringBuilder rows = new StringBuilder();
        int used = 0;
        for (IrplusCodec.Item item : working) {
            if (used > 0 && used + item.span > 12) {
                rows.append("\n");
                used = 0;
            }
            if (used > 0) rows.append("  ");
            rows.append(item.space ? "(space)" : item.label);
            rows.append(" [").append(item.span).append("]");
            used += item.span;
            if (used == 12) {
                rows.append("\n");
                used = 0;
            }
        }
        return rows.length() == 0 ? "No buttons yet" : rows.toString().trim();
    }

    /** In-memory commit; caller saves and rolls back on disk failure. */
    public void applyTo(IrplusCodec.Remote remote) {
        remote.items.clear();
        remote.items.addAll(working);
    }
}
