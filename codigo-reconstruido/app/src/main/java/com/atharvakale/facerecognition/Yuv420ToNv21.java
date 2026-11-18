package com.atharvakale.facerecognition;

import java.nio.ByteBuffer;

/** Copies YUV_420_888 planes into NV21 without modifying camera buffers. */
final class Yuv420ToNv21 {
    private Yuv420ToNv21() {}

    static byte[] convert(int width, int height,
            ByteBuffer y, int yRow, int yPixel,
            ByteBuffer u, int uRow, int uPixel,
            ByteBuffer v, int vRow, int vPixel) {
        if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0)
            throw new IllegalArgumentException("NV21 requires positive even image dimensions");
        long size = (long) width * height * 3 / 2;
        if (size > Integer.MAX_VALUE) throw new IllegalArgumentException("Image too large");
        checkPlane(y, yRow, yPixel, width, height);
        checkPlane(u, uRow, uPixel, width / 2, height / 2);
        checkPlane(v, vRow, vPixel, width / 2, height / 2);
        byte[] out = new byte[(int) size];
        int pos = 0, yBase = y.position(), uBase = u.position(), vBase = v.position();
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++)
                out[pos++] = y.get(yBase + row * yRow + col * yPixel);
        for (int row = 0; row < height / 2; row++) {
            for (int col = 0; col < width / 2; col++) {
                out[pos++] = v.get(vBase + row * vRow + col * vPixel);
                out[pos++] = u.get(uBase + row * uRow + col * uPixel);
            }
        }
        return out;
    }

    private static void checkPlane(ByteBuffer buffer, int rowStride, int pixelStride, int columns, int rows) {
        if (buffer == null || rowStride <= 0 || pixelStride <= 0 ||
                (long) (columns - 1) * pixelStride >= rowStride)
            throw new IllegalArgumentException("Invalid plane strides");
        long last = (long) buffer.position() + (long) (rows - 1) * rowStride + (long) (columns - 1) * pixelStride;
        if (last >= buffer.limit()) throw new IllegalArgumentException("Truncated image plane");
    }
}
