package mindustry.client.morj.mk.ui;

import arc.struct.ByteSeq;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.client.morj.mk.Util;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;
import net.jpountz.lz4.LZ4Factory;

// Only touch on your own risk.

public class DrawOperation {
    private ByteSeq data;
    private Seq<Object> dataObjects;

    private int compressed = -1;
    private byte[] compressedData;
    private Object[] compressedDataObjects;

    public short lastX = -1, lastY = -1;

    private final byte opFloor = 0,
        opFloorData = 1,
        opExtraData = 2,
        opBlock = 3,
        opBlockData = 4,
        opRotation = 5,
        opTeam = 6,
        opTile = 7,
        opOverlay = 8,
        opOverlayData = 9;

    public int size() {
        int size = 0;

        if (data != null) size += data.size;
        else if (compressedData != null) size += compressedData.length;

        if (dataObjects != null) size += dataObjects.size * 1024;
        else if (compressedDataObjects != null) size += compressedDataObjects.length * 1024;

        return size;
    }

    public void maybeCompress() {
        if (data != null) {
            compressedData = data.toArray();
            compressed = compressedData.length > 50 ? compressedData.length : -1;
            if (compressed != -1) compressedData = LZ4Factory.fastestInstance().fastCompressor().compress(compressedData);
            data = null;
        }
        if (dataObjects != null) {
            compressedDataObjects = dataObjects.toArray(Object.class);
            dataObjects = null;
        }
    }

    public void maybeDecompress() {
        if (compressedData != null) {
            if (compressed != -1) {
                byte[] decompressed = new byte[compressed];
                LZ4Factory.fastestInstance().safeDecompressor().decompress(compressedData, decompressed);
                data = new ByteSeq(decompressed);
            } else {
                data = new ByteSeq(compressedData);
            }
            compressedData = null;
        }
        if (compressedDataObjects != null) {
            dataObjects = new Seq<>(compressedDataObjects);
            compressedDataObjects = null;
        }
    }

    public void undo() {
        maybeDecompress();

        if (data == null) {
            dataObjects = null;
            return;
        }

        Tile cursor = null;

        int i = data.size - 1;
        int bi = dataObjects == null ? 0 : dataObjects.size - 1;
        try {
            for (; i >= 0; i--) {
                switch (data.items[i]) {
                    case opTile: {
                        i -= 2;
                        short x = Util.readShort(data, i);
                        i -= 2;
                        short y = Util.readShort(data, i);
                        i -= 2;
                        cursor = Vars.world.tile(x, y);
                        break;
                    }

                    case opFloor: {
                        i -= 2;
                        Floor floor = (Floor) Vars.content.block(Util.readShort(data, i));
                        i -= 2;
                        short x = Util.readShort(data, i);
                        i -= 2;
                        short y = Util.readShort(data, i);
                        cursor = Vars.world.tile(x, y);
                        cursor.setFloor(floor);
                        break;
                    }
                    case opFloorData: {
                        assert cursor != null;
                        byte tileData = data.get(--i);
                        cursor.floorData = tileData;
                        break;
                    }

                    case opBlock: {
                        i -= 2;
                        Block block = Vars.content.block(Util.readShort(data, i));

                        i -= 2;
                        short x = Util.readShort(data, i);

                        i -= 2;
                        short y = Util.readShort(data, i);

                        cursor = Vars.world.tile(x, y);

                        int rotation;
                        if (block.rotate) {
                            i -= 4;
                            rotation = Util.readInt(data, i);
                        } else rotation = 0;

                        Team team;
                        if (block.hasBuilding()) team = Team.all[data.get(--i) & 0xff];
                        else team = Team.derelict;

                        Building build = (Building) dataObjects.get(bi--);

                        cursor.setBlock(block, team, rotation, () -> build);
                        break;
                    }
                    case opBlockData: {
                        assert cursor != null;
                        byte tileData = data.get(--i);
                        cursor.data = tileData;
                        break;
                    }
                    case opTeam: {
                        assert cursor != null;
                        cursor.setTeam(Team.all[data.get(--i) & 0xff]);
                        break;
                    }
                    case opRotation: {
                        assert cursor != null;
                        i -= 4;
                        int rotation = Util.readInt(data, i);
                        if (cursor.build != null) cursor.build.rotation = rotation;
                        break;
                    }

                    case opOverlay: {
                        i -= 2;
                        Floor floor = (Floor) Vars.content.block(Util.readShort(data, i));
                        i -= 2;
                        short x = Util.readShort(data, i);
                        i -= 2;
                        short y = Util.readShort(data, i);
                        cursor = Vars.world.tile(x, y);
                        cursor.setOverlay(floor);
                        break;
                    }
                    case opOverlayData: {
                        assert cursor != null;
                        byte tileData = data.get(--i);
                        cursor.overlayData = tileData;
                        break;
                    }

                    case opExtraData: {
                        assert cursor != null;
                        i -= 4;
                        int tileData = Util.readInt(data, i);
                        cursor.extraData = tileData;
                        break;
                    }

                    default:
                        throw new IllegalStateException("Invalid command " + data.items[i] + ". Is stack corrupted?");
                }
            }
        } catch (Exception e) {
            StringBuilder builder = new StringBuilder();
            cursor = null;
            i = data.size - 1;
            bi = dataObjects == null ? 0 : dataObjects.size - 1;
            for (; i >= 0; i--) {
                Log.err("i=" + i + ", bi=" + bi);
                switch (data.items[i]) {
                    case opTile: {
                        i -= 2;
                        short x = Util.readShort(data, i);
                        i -= 2;
                        short y = Util.readShort(data, i);
                        i -= 2;
                        cursor = Vars.world.tile(x, y);
                        builder.append("- tile(").append(x).append(", ").append(y).append(")\n");
                        Log.err("- tile("+x+", "+y+")");
                        break;
                    }

                    case opFloor: {
                        i -= 2;
                        Floor floor = (Floor) Vars.content.block(Util.readShort(data, i));
                        i -= 2;
                        short x = Util.readShort(data, i);
                        i -= 2;
                        short y = Util.readShort(data, i);
                        cursor = Vars.world.tile(x, y);
                        builder.append("- floor(").append(floor.name).append(", ").append(x).append(", ").append(y).append(")\n");
                        Log.err("- floor("+floor.name+", "+x+", "+y+")");
                        break;
                    }
                    case opFloorData: {
                        byte tileData = data.get(--i);
                        if (cursor == null) builder.append("- floorData(").append(tileData).append(") !NOTILE\n");
                        else builder.append("- floorData(").append(tileData).append(")\n");
                        Log.err("- floorData("+tileData+")" + (cursor == null ? " !NOTILE" : ""));
                        break;
                    }

                    case opBlock: {
                        i -= 2;
                        Block block = Vars.content.block(Util.readShort(data, i));

                        i -= 2;
                        short x = Util.readShort(data, i);

                        i -= 2;
                        short y = Util.readShort(data, i);

                        cursor = Vars.world.tile(x, y);

                        int rotation;
                        if (block.rotate) {
                            i -= 4;
                            rotation = Util.readInt(data, i);
                        } else rotation = 0;

                        Team team;
                        if (block.hasBuilding()) team = Team.all[data.get(--i) & 0xff];
                        else team = Team.derelict;

                        Building build = (Building) dataObjects.get(bi--);

                        builder.append("- block(").append(block.name).append(", ").append(x).append(", ").append(y).append(", team#").append(team.id).append(")\n");
                        Log.err("- block("+block.name+", "+x+", "+y+", team#"+team.id+")");
                        break;
                    }
                    case opBlockData: {
                        byte tileData = data.get(--i);
                        if (cursor == null) builder.append("- blockData(").append(tileData).append(") !NOTILE\n");
                        else builder.append("- blockData(").append(tileData).append(")\n");
                        break;
                    }
                    case opTeam: {
                        if (cursor == null) builder.append("- team(team#").append(data.get(--i) & 0xff).append(") !NOTILE\n");
                        else builder.append("- team(team#").append(data.get(--i) & 0xff).append(")\n");
                        break;
                    }
                    case opRotation: {
                        i -= 4;
                        int rotation = Util.readInt(data, i);
                        if (cursor == null) builder.append("- rotation(").append(rotation).append(") !NOTILE\n");
                        else builder.append("- rotation(").append(rotation).append(")\n");
                        break;
                    }

                    case opOverlay: {
                        i -= 2;
                        Floor floor = (Floor) Vars.content.block(Util.readShort(data, i));
                        i -= 2;
                        short x = Util.readShort(data, i);
                        i -= 2;
                        short y = Util.readShort(data, i);
                        cursor = Vars.world.tile(x, y);
                        builder.append("- overlay(").append(floor.name).append(", ").append(x).append(", ").append(y).append(")\n");
                        break;
                    }
                    case opOverlayData: {
                        byte tileData = data.get(--i);
                        if (cursor == null) builder.append("- overlayData(").append(tileData).append(") !NOTILE\n");
                        else builder.append("- overlayData(").append(tileData).append(")\n");
                        break;
                    }

                    case opExtraData: {
                        i -= 4;
                        int tileData = Util.readInt(data, i);
                        if (cursor == null) builder.append("- extraData(").append(tileData).append(") !NOTILE\n");
                        else builder.append("- extraData(").append(tileData).append(")\n");
                        break;
                    }

                    default:
                        throw new IllegalStateException("Invalid command " + data.items[i] + ". Is stack corrupted?");
                }
            }

            Log.err("Fatal!");
            Log.err("Commands:\n" + builder);
            Log.err("Idx: " + i);
            throw new RuntimeException(e);
        }

        data = null;
        dataObjects = null;
    }

    public void tile(short x, short y) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);

        Util.writeShort(data, y);
        Util.writeShort(data, x);
        data.add(opTile);

        lastX = x;
        lastY = y;

        // Log.info("tile(" + x + ", " + y + ")");
    }

    public void floor(Floor floor, short x, short y) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);

        Util.writeShort(data, y);
        Util.writeShort(data, x);
        Util.writeShort(data, floor.id);
        data.add(opFloor);

        lastX = x;
        lastY = y;

        // Log.info("floor(" + floor.name + ", " + x + ", " + y + ")");
    }
    public void floorData(byte newData) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);

        data.add(newData, opFloorData);

        // Log.info("floorData(" + (newData & 0xff) + ")");
    }

    public void block(Block block, short x, short y, Team team, int rotation, Building build) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);
        if (dataObjects == null) dataObjects = new Seq<>(4);

        dataObjects.add(build);
        if (block.hasBuilding()) data.add((byte) team.id);
        if (block.rotate) Util.writeInt(data, rotation);
        Util.writeShort(data, y);
        Util.writeShort(data, x);
        Util.writeShort(data, block.id);
        data.add(opBlock);

        lastX = x;
        lastY = y;

        // Log.info("block(" + block.name + ", " + x + ", " + y + ", team#" + team.id + ", rot = " + rotation + ")");
    }
    public void rotation(int rotation) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);

        Util.writeInt(data, rotation);
        data.add(opRotation);
    }
    public void team(Team team) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);

        data.add((byte) team.id, opTeam);
    }
    public void blockData(byte newData) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);

        data.add(newData, opBlockData);

        // Log.info("blockData(" + (newData & 0xff) + ")");
    }

    public void extraData(int newData) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);

        Util.writeInt(data, newData);
        data.add(opExtraData);

        // Log.info("extraData(" + (newData & 0xffffffffL) + ")");
    }

    public void overlay(Floor overlay, short x, short y) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);

        Util.writeShort(data, y);
        Util.writeShort(data, x);
        Util.writeShort(data, overlay.id);
        data.add(opOverlay);

        lastX = x;
        lastY = y;

        // Log.info("overlay(" + overlay.name + ", " + x + ", " + y + ")");
    }
    public void overlayData(byte newData) {
        maybeDecompress();
        if (data == null) data = new ByteSeq(32);

        data.add(newData, opOverlayData);

        // Log.info("overlayData(" + (newData & 0xff) + ")");
    }
}
