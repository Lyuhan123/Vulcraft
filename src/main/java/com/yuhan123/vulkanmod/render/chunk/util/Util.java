package com.yuhan123.vulkanmod.render.chunk.util;

/**
 * Thin vulcraft adaptation: drops the 1.21 {@code Direction} tables (only the
 * staged-out visibility graph used them) and keeps the small helpers the
 * copied draw path calls.
 */
public class Util {

    public static byte getOppositeDirIdx(byte idx) {
        return (byte) ((idx & 0b1) != 0 ? idx - 1 : idx + 1);
    }

    public static long posLongHash(int x, int y, int z) {
        return (long) x & 0x00000000FFFFL | ((long) z << 16) & 0x0000FFFF0000L | ((long) y << 32) & 0xFFFF00000000L;
    }

    public static int flooredLog(int v) {
        assert v > 0;
        int log = 30;
        int t = 0x40000000;

        while ((v & t) == 0) {
            t >>= 1;
            log--;
        }

        return log;
    }

    public static long align(long l, int alignment) {
        if (alignment == 0)
            return l;

        long r = l % alignment;
        return r != 0 ? l + alignment - r : l;
    }
}
