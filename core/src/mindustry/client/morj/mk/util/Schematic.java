package mindustry.client.morj.mk.util;

import arc.struct.Seq;
import arc.util.Log;
import lombok.AllArgsConstructor;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.blocks.environment.Floor;

import java.nio.ByteBuffer;

public class Schematic {
    public static final Schematic EMPTY = new Schematic(0, 0);
    private static final Options DEFAULT = new Options();

    @AllArgsConstructor
    public static class BuildData {
        final int rotation;
        final Object config;
    }

    public static class Options {
        /** Skip air overlays. */
        public boolean skipNoOverlay;
        /** Skip air blocks. */
        public boolean skipAir;
        /** Skip empty floors. */
        public boolean skipEmpty;
        /** Skip buildings. */
        public boolean skipBuildings;
        /** Use net updates. */
        public boolean updateNet = true;
        /** Team to use when placing blocks. */
        public Team team = Team.derelict;
        // /** Mask to select affected blocks. Pasting only. */
        // public Schematic mask = null;
        // /** Starting x position on the mask. */
        // public int maskX = 0;
        // /** Starting y position on the mask. */
        // public int maskY = 0;

        /** Skip air blocks. */
        public Options skipAir() {
            skipAir = true;
            return this;
        }
        /** Skip air overlays. */
        public Options skipNoOverlay() {
            skipNoOverlay = true;
            return this;
        }
        /** Skip empty floors. */
        public Options skipEmpty() {
            skipEmpty = true;
            return this;
        }
        /** Skip buildings. */
        public Options skipBuildings() {
            skipBuildings = true;
            return this;
        }
        /** Disable network updates. */
        public Options noNet() {
            updateNet = false;
            return this;
        }
        /** Team to use when placing blocks. */
        public Options team(Team team) {
            this.team = team;
            return this;
        }
        // /** Set mask. */
        // public Options mask(Schematic mask) { return mask(mask, 0, 0); }
        // /** Set mask. */
        // public Options mask(Schematic mask, int x, int y) {
        //     this.mask = mask;
        //     maskX = x;
        //     maskY = y;
        //     return this;
        // }
        /** Reset all fields to defaults. */
        public Options reset() {
            skipAir = false;
            skipEmpty = false;
            skipNoOverlay = false;
            skipBuildings = false;
            updateNet = true;
            team = Team.derelict;
            // mask = null;
            // maskX = 0;
            // maskY = 0;
            return this;
        }
    }

    public final int width;
    public final int height;
    public final Floor[] overlays;
    public final Block[] blocks;
    public final Floor[] floors;
    public long[] data;
    public BuildData[] build;

    private Schematic(int width, int height) {
        this.width = width;
        this.height = height;
        overlays = new Floor[width * height];
        blocks = new Block[width * height];
        floors = new Floor[width * height];
        data = new long[width * height];
        build = new BuildData[width * height];
    }

    public static Schematic of(Tiles tiles, int x, int y, int w, int h) { return of(tiles, x, y, w, h, DEFAULT); }
    public static Schematic of(Tiles tiles, int x, int y, int w, int h, Options options) {
        if (x >= tiles.width || y >= tiles.height) {
            Log.warn("Not enough space for scheme! Returning an empty one.");
            return EMPTY;
        }
        w = Math.min(tiles.width - x, w);
        h = Math.min(tiles.height - y, h);
        if (w == 0 || h == 0) {
            Log.warn("Not enough space for scheme! Returning an empty one.");
            return EMPTY;
        }

        Schematic schematic = new Schematic(w, h);
        int cursor = 0;
        for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++, cursor++) {
            Tile tile = tiles.get(x + dx, y + dy);
            schematic.overlays[cursor] = tile.overlay();
            schematic.floors[cursor] = tile.floor();
            schematic.data[cursor] = tile.getPackedData();

            if (schematic.overlays[cursor] == Blocks.air && options.skipNoOverlay) schematic.overlays[cursor] = null;
            if (schematic.floors[cursor] == Blocks.empty && options.skipEmpty) schematic.floors[cursor] = null;

            Block block = tile.block();
            a: if (!block.isMultiblock() || tile.isCenter()) {
                Building build = tile.build;
                if (build != null && options.skipBuildings) break a;

                schematic.blocks[cursor] = tile.block();
                if (build != null) {
                    schematic.build[cursor] = new BuildData(build.rotation, build.config());
                }
                if (schematic.blocks[cursor] == Blocks.air && options.skipAir) schematic.blocks[cursor] = null;
            }
        }

        return schematic;
    }
    public static Schematic of(String data) throws FormatException {
        StringRead read = new StringRead(data);
        try {
            if (read.i() != 2) throw new FormatException("Invalid format");
        } catch (FormatException e) { throw new FormatException("Invalid format", e); }

        int width = read.i();
        if (width < 0) throw new FormatException("Invalid width (" + width + " < " + "0)");
        int height = read.i();
        if (height < 0) throw new FormatException("Invalid height (" + height + " < " + "0)");

        if (width == 0 || height == 0) return EMPTY;

        Schematic scheme = new Schematic(width, height);

        int blockCount = read.i();
        if (blockCount < 0) throw new FormatException("Invalid block count (" + blockCount + " < " + "0)");
        Block[] blocks = new Block[blockCount];

        for (int cursor = 0; cursor < blockCount; cursor++) {
            String name = read.sym();
            Block block = Vars.content.block(name);
            if (block == null) throw new FormatException("Could not find block '" + name + "'");
            blocks[cursor] = block;
        }

        for (int cursor = 0, y = 0; y < height; y++) for (int x = 0; x < width; x++, cursor++) {
            scheme.data[cursor] = read.l();

            try { if (!read.nil()) {
                scheme.blocks[cursor] = blocks[read.i()];
            } } catch (ArrayIndexOutOfBoundsException e) {
                throw new FormatException("Invalid block index", e);
            }

            try { if (!read.nil()) {
                Block block = blocks[read.i()];
                if (!block.isFloor() && block != Blocks.air) throw new FormatException("Not an overlay (" + block.name + ")");
                scheme.overlays[cursor] = block.asFloor();
            } } catch (ArrayIndexOutOfBoundsException e) {
                throw new FormatException("Invalid block index", e);
            }

            try { if (!read.nil()) {
                Block block = blocks[read.i()];
                if (!block.isFloor()) throw new FormatException("Not a floor");
                scheme.floors[cursor] = block.asFloor();
            } } catch (ArrayIndexOutOfBoundsException e) {
                throw new FormatException("Invalid block index", e);
            }

            if (!read.nil()) throw new FormatException("Block data is not yet implemented");
        }

        return scheme;
    }

    public void paste(Tiles dst, int dstx, int dsty) { paste(0, 0, width, height, dst, dstx, dsty, DEFAULT); }
    public void paste(Tiles dst, int dstx, int dsty, Options options) {
        paste(0, 0, width, height, dst, dstx, dsty, options);
    }
    public void paste(int x, int y, int w, int h, Tiles dst, int dstx, int dsty) {
        paste(x, y, w, h, dst, dstx, dsty, DEFAULT);
    }
    public void paste(int x, int y, int w, int h, Tiles dst, int dstx, int dsty, Options options) {
        if (dstx >= dst.width) return;
        if (dsty >= dst.height) return;

        if (dstx < 0) {
            x += dstx;
            w -= dstx;
            dstx = 0;
        }
        if (dsty < 0) {
            y += dsty;
            h -= dsty;
            dsty = 0;
        }

        if (x < 0) {
            dstx -= x;
            x = 0;
        }
        if (y < 0) {
            dsty -= y;
            y = 0;
        }

        w = Math.min(w, dst.width - dstx);
        w = Math.min(w, width - x);

        h = Math.min(h, dst.height - dsty);
        h = Math.min(h, height - y);

        if (w == 0 || h == 0) return;
        if (x >= width) return;
        if (y >= height) return;

        for (int dx = 0; dx < w; dx++) for (int dy = 0; dy < h; dy++) {
            int idx = x + dx + (y + dy) * width;

            if (idx >= blocks.length) {
                throw new RuntimeException("Bailing out! Schematic (" + width + "x" + height + "): x=" + x + ", y=" + y + ", w=" + w + ", h=" + h + ", dstx=" + dstx + ", dsty=" + dsty + ", idx=" + idx + ", dx=" + dx + ", dy=" + dy);
            }

            Tile tile = dst.get(dstx + dx, dsty + dy);
            if (tile == null) continue;

            BuildData data = this.build[idx];
            block: if (blocks[idx] != null && !(blocks[idx] == Blocks.air && options.skipAir)) {
                if (options.skipBuildings && tile.build != null || this.build[idx] != null) break block;
                if (options.updateNet) tile.setNet(blocks[idx], options.team, data == null ? 0 : data.rotation);
                else tile.setBlock(blocks[idx], options.team, data == null ? 0 : data.rotation);
            }
            if (overlays[idx] != null && !(blocks[idx] == Blocks.air && options.skipNoOverlay)) {
                if (options.updateNet) tile.setOverlayNet(overlays[idx]);
                else tile.setOverlay(overlays[idx]);
            }
            if (floors[idx] != null && !(blocks[idx] == Blocks.empty && options.skipEmpty)) {
                if (options.updateNet) tile.setFloorNet(floors[idx]);
                else tile.setFloor(floors[idx]);
            }

            boolean dataChanged = tile.data != this.data[idx];

            tile.setPackedData(this.data[idx]);
            if (options.updateNet && dataChanged && tile.block().saveData && Vars.net.server()) {
                ByteBuffer buf = ByteBuffer.wrap(new byte[12]);
                buf.putShort(0, tile.x);
                buf.putShort(2, tile.y);
                buf.putLong(4, this.data[idx]);

                Call.clientBinaryPacketReliable("mindurka.setData", buf.array().clone());
            }
        }
    }

    private static class RefTable {
        StringBuilder refTableBuilder = new StringBuilder();
        Seq<String> refTable = new Seq<>();

        public int ref(String id) {
            int pos = refTable.indexOf(id);
            if (pos == -1) {
                pos = refTable.size;
                refTableBuilder.append(id).append(',');
                refTable.add(id);
            }
            return pos;
        }
    }
    public String serialize() {
        RefTable refs = new RefTable();
        StringBuilderWrite body = new StringBuilderWrite(new StringBuilder());

        for (int cursor = 0, y = 0; y < height; y++) for (int x = 0; x < width; x++, cursor++) {
            body.l(data[cursor]);

            Block block = blocks[cursor];
            if (block == null) body.nil();
            else body.i(refs.ref(block.name));

            block = overlays[cursor];
            if (block == null) body.nil();
            else body.i(refs.ref(block.name));

            block = floors[cursor];
            if (block == null) body.nil();
            else body.i(refs.ref(block.name));

            body.nil();
        }

        return "2," + width + ',' + height + ',' + refs.refTable.size + ',' + refs.refTableBuilder + body.builder;
    }
}
