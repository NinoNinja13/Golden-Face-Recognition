package com.atharvakale.facerecognition;

import java.util.ArrayList;
import java.util.List;

/** Backwards-compatible extra: an array of 192-value rows (legacy had one row). */
final class FaceSamples {
    static final int SIZE = 192;
    static float[][] decode(Object extra) {
        List<float[]> rows = new ArrayList<>();
        if (extra instanceof float[][]) {
            for (float[] row : (float[][]) extra) add(rows, row);
        } else if (extra instanceof List) {
            for (Object obj : (List<?>) extra) {
                if (!(obj instanceof List) || ((List<?>)obj).size() != SIZE) continue;
                float[] row = new float[SIZE]; boolean valid = true;
                for (int i=0; i<SIZE; i++) {
                    Object value = ((List<?>)obj).get(i);
                    if (!(value instanceof Number)) { valid = false; break; }
                    row[i] = ((Number)value).floatValue();
                }
                if (valid) add(rows, row);
            }
        }
        return rows.toArray(new float[rows.size()][]);
    }
    private static void add(List<float[]> rows, float[] row) {
        if (row == null || row.length != SIZE) return;
        for (float v : row) if (Float.isNaN(v) || Float.isInfinite(v)) return;
        rows.add(row.clone());
    }
    static float[][] append(Object old, float[][] added) {
        List<float[]> rows = new ArrayList<>();
        for (float[] row : decode(old)) add(rows, row);
        if (added != null) for (float[] row : added) add(rows, row);
        return rows.toArray(new float[rows.size()][]);
    }
    static float distance(float[] query, float[][] samples) {
        if (query == null || query.length != SIZE) return Float.MAX_VALUE;
        float best = Float.MAX_VALUE;
        for (float[] row : samples) {
            if (row == null || row.length != SIZE) continue;
            float sum=0;
            for (int i=0; i<SIZE; i++) { float d=query[i]-row[i]; sum+=d*d; }
            float value=(float)Math.sqrt(sum);
            if (value < best) best=value;
        }
        return best;
    }
}
