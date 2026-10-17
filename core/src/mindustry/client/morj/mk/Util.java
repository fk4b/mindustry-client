package mindustry.client.morj.mk;

import arc.struct.ByteSeq;
import arc.struct.IntMap;
import arc.util.Strings;

import java.nio.ByteBuffer;

public class Util {
    private Util() {}

    public static Object[] noargs = new Object[0];

    public static boolean enabled(byte bitfield, int field) {
        if (field < 0 || field > 7) return false;
        return (((int) bitfield & 0xff) >> field & 1) == 1;
    }
    public static boolean disabled(byte bitfield, int field) {
        if (field < 0 || field > 7) return false;
        return (((int) bitfield & 0xff) >> field & 1) == 0;
    }
    public static byte enable(byte bitfield, int field) {
        if (field < 0 || field > 7) return bitfield;
        int modded = bitfield & 0xff;
        modded |= 1 << field;
        return (byte) modded;
    }
    public static byte disable(byte bitfield, int field) {
        if (field < 0 || field > 7) return bitfield;
        int modded = bitfield & 0xff;
        modded &= ~(1 << field);
        return (byte) modded;
    }
    public static byte toggle(byte bitfield, int field) {
        if (field < 0 || field > 7) return bitfield;
        int modded = bitfield & 0xff;
        modded ^= (1 << field);
        return (byte) modded;
    }

    public static long packxy(int x, int y) {
        long x$1 = x & 0xffffffffL;
        long y$1 = y & 0xffffffffL;
        return x$1 << 32 | y$1;
    }
    public static int unpackx(long p) {
        return (int) (p >> 32);
    }
    public static int unpacky(long p) {
        return (int) (p & 0xffffffffL);
    }

    public static int mod(int x, int a) {
        x = x % a;
        if (x < 0) x = x + a;
        return x;
    }

    public interface IterateIntMap<T> {
        void get(int key, T value);
    }

    public static <T> void forEach(IntMap<T> map, IterateIntMap<T> callback) {
        IntMap.Keys keys = map.keys();
        while (keys.hasNext) {
            int key = keys.next();
            callback.get(key, map.get(key));
        }
    }

    public interface Yeet<T> {
        T run() throws Exception;
    }
    public static <T> T yeet(Yeet<T> fn) {
        try {
            return fn.run();
        } catch (Exception e) {
            throw new RuntimeException("Yeet!", e);
        }
    }

    public interface YeetV {
        void run() throws Exception;
    }
    public static void yeet(YeetV fn) {
        try {
            fn.run();
        } catch (Exception e) {
            throw new RuntimeException("Yeet!", e);
        }
    }

    public static void writeShort(ByteSeq a, short value) {
        a.add((byte) (value / 256));
        a.add((byte) (value % 256));
    }
    public static short readShort(ByteSeq a, int pos) {
        if (pos + 1 >= a.size) throw new ArrayIndexOutOfBoundsException("Cannot read short number! (" + (pos + 1) + " >= " + a.size + ")");
        int most = a.get(pos) * 256;
        int least = a.get(pos + 1) & 0xff;

        return (short) (most >= 0 ? most + least : most - least);
    }

    private static final ByteBuffer intBuffer = ByteBuffer.allocate(4);
    public static void writeInt(ByteSeq a, int value) {
        intBuffer.position(0);
        intBuffer.putInt(value);
        a.addAll(intBuffer.array());
    }
    public static int readInt(ByteSeq a, int pos) {
        intBuffer.position(0);
        intBuffer.put(a.items, pos, 4);
        return intBuffer.getInt(0);
    }

    public static boolean keyHasHeadByte(String key, String head) {
        int end = head.length();
        if (!key.startsWith(head)) return false;
        char c;
        switch (key.length() - end) {
            case 1:
                c = key.charAt(end);
                return c >= '0' && c <= '9';
            case 2:
                c = key.charAt(end);
                if (c < '1' || c > '9') return false;
                c = key.charAt(end + 1);
                return c >= '0' && c <= '9';
            case 3:
                c = key.charAt(end);
                if (c < '1' || c > '2') return false;
                if (c == '2') {
                    c = key.charAt(end + 1);
                    if (c < '0' || c > '5') return false;
                    if (c == '5') {
                        c = key.charAt(end + 2);
                        return c >= '0' && c <= '5';
                    } else {
                        c = key.charAt(end + 2);
                        return c >= '0' && c <= '9';
                    }
                } else {
                    c = key.charAt(end + 1);
                    if (c < '0' || c > '9') return false;
                    c = key.charAt(end + 2);
                    return c >= '0' && c <= '9';
                }
            default: return false;
        }
    }
    public static int keyHeadByte(String key, String head) {
        return Strings.parseInt(key, 10, 0, head.length(), key.length());
    }
}
