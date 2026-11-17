package com.mixtervee.irstudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Backup is a read-only copy of ONE stored U1 IR waveform, not U1 settings. */
public final class U1BackupStore {
    private U1BackupStore() {}

    public static File[] list(File directory) {
        File[] all = directory.listFiles(f -> f.isFile()
                && f.getName().startsWith("SofaBaton-U1-slot8-before-learn-")
                && f.getName().endsWith(".json"));
        if (all == null) return new File[0];
        Arrays.sort(all, Comparator.comparing(File::getName).reversed());
        return all;
    }

    public static File save(File directory, U1Protocol.Signal sample) throws Exception {
        if (sample.slot != U1Protocol.SLOT || sample.timingsUs.length < 12)
            throw new IllegalArgumentException("Cannot back up an invalid U1 slot");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Cannot create protected U1 backup folder");
        File file = new File(directory, "SofaBaton-U1-slot8-before-learn-"
                + System.currentTimeMillis() + "-"
                + Long.toHexString(System.nanoTime()) + ".json");
        File tmp = new File(directory, file.getName() + ".tmp");
        JSONObject data = new JSONObject();
        data.put("key", "SofaBaton U1 stored IR before learning");
        data.put("original_slot", sample.slot);
        data.put("carrier_hz", sample.carrierHz);
        JSONArray timings = new JSONArray();
        for (int value : sample.timingsUs) timings.put(value);
        data.put("timings_us", timings);
        data.put("note", "Raw waveform only. Does NOT restore the U1's "
                + "buttons, profiles or internal programming.");
        try {
            try (FileOutputStream output = new FileOutputStream(tmp)) {
                output.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
                output.write('\n');
                output.flush();
                output.getFD().sync();
            }
            if (!tmp.renameTo(file))
                throw new IllegalStateException("Cannot finalize U1 backup file");
            U1Protocol.Signal checked = read(file);
            if (checked.carrierHz != sample.carrierHz
                    || !Arrays.equals(checked.timingsUs, sample.timingsUs))
                throw new IllegalStateException("U1 backup failed read-back validation");
            return file;
        } catch (Exception error) {
            if (tmp.exists()) tmp.delete();
            if (file.exists()) file.delete();
            throw error;
        }
    }

    public static U1Protocol.Signal read(File file) throws Exception {
        if (file == null || !file.isFile() || file.length() > 131072)
            throw new IllegalArgumentException("Missing or oversized U1 backup");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                if (out.size() > 131072)
                    throw new IllegalArgumentException("U1 backup file too large");
            }
        }
        JSONObject json = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        int slot = json.optInt("original_slot", -1);
        int carrier = json.getInt("carrier_hz");
        JSONArray array = json.getJSONArray("timings_us");
        if (slot != U1Protocol.SLOT || carrier < 10000 || carrier > 100000
                || array.length() < 12 || array.length() > 4096
                || (array.length() & 1) != 0)
            throw new IllegalArgumentException("Backup contains invalid slot/carrier/timings");
        int[] timings = new int[array.length()];
        for (int i = 0; i < timings.length; i++) {
            int duration = array.getInt(i);
            if (duration <= 0 || duration > 5000000)
                throw new IllegalArgumentException("Backup IR timing out of range");
            timings[i] = duration;
        }
        return new U1Protocol.Signal(slot, carrier, timings, 0);
    }
}
