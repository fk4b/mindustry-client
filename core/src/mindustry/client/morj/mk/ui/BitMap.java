package mindustry.client.morj.mk.ui;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import mindustry.world.Tiles;

import java.util.Arrays;

@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BitMap {
    public final byte[] bytes;
    public final int width;
    public final int height;

    public static BitMap of(Tiles tiles) {
        return of(tiles.width, tiles.height);
    }
    public static BitMap of(int width, int height) {
        return new BitMap(new byte[width * height / 8 + 1], width, height);
    }

    public boolean toggled(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        int tpos = y * width + x;
        int pos = tpos / 8;
        int bpos = tpos % 8;

        int part = bytes[pos] & 0xff;
        return (part >> bpos & 1) == 1;
    }

    public void enable(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        int tpos = y * width + x;
        int pos = tpos / 8;
        int bpos = tpos % 8;

        int part = bytes[pos] & 0xff;
        part |= 1 << bpos;
        bytes[pos] = (byte) part;
    }

    public void disable(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        int tpos = y * width + x;
        int pos = tpos / 8;
        int bpos = tpos % 8;

        int part = bytes[pos] & 0xff;
        part &= ~(1 << bpos);
        bytes[pos] = (byte) part;
    }

    public void zero() {
        Arrays.fill(bytes, (byte) 0);
    }
}
