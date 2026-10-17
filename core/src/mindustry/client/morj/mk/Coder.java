package mindustry.client.morj.mk;

import arc.func.Func;
import arc.struct.Seq;
import arc.struct.ShortSeq;
import arc.util.Nullable;
import arc.util.Strings;
import lombok.AllArgsConstructor;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.Tiles;

public class Coder {
    private Coder() {}

    @AllArgsConstructor
    public static class Metrics {
        public int width;
        public int height;
    }

    public static abstract class Result<T> {
        /**
         * Map this {@code Result}.
         * <p>
         * Previous object is invalidated when using this method. Use {@link this#dup} to
         * get a copy beforehand if this is undesired.
         */
        public abstract <Y> Result<Y> map(Func<T, Y> mapper);

        /**
         * Duplicate this {@code Result}.
         * <p>
         * Does not duplicate the underlying values, only the container object.
         */
        public abstract Result<T> dup();

        /**
         * Unwrap this {@code Result}.
         * <p>
         * Throws an {@link IllegalStateException} if this is an error.
         */
        public abstract T unwrap() throws IllegalStateException;

        public static <T> Result<T> ok(T value) {
            return new Ok<>(value);
        }
        public static <T> Result<T> err(CharSequence first, CharSequence... rest) {
            return new Err<>(String.join(first, rest));
        }

        public static class Ok<T> extends Result<T> {
            public T value;

            public Ok(T v) {
                value = v;
            }

            @Override
            public <Y> Result<Y> map(Func<T, Y> mapper) {
                Y value = mapper.get(this.value);
                Ok<Y> punned = (Ok<Y>) this;
                punned.value = value;
                return punned;
            }

            @Override
            public Result<T> dup() {
                return new Ok<>(value);
            }

            @Override
            public T unwrap() throws IllegalStateException {
                return value;
            }
        }

        public static class Err<T> extends Result<T> {
            public String error;

            public Err(String text) {
                error = text;
            }

            @Override
            public <Y> Result<Y> map(Func<T, Y> mapper) {
                return (Err<Y>) this;
            }

            @Override
            public Result<T> dup() {
                return new Err<>(error);
            }

            @Override
            public T unwrap() throws IllegalStateException {
                throw new IllegalStateException(error);
            }
        }
    }

    public static Result<String> encodeArea(Tiles space, int startX, int startY, int width, int height) {
        if (startX < 0) return Result.err("illegal value of startX (startX < 0)");
        if (startY < 0) return Result.err("illegal value of startY (startY < 0)");
        if (width < 0) return Result.err("illegal value of width (width < 0)");
        if (height < 0) return Result.err("illegal value of height (height < 0)");

        if (space.width < startX + width || space.height < startY + height) {
            return new Result.Err<>("could not select region (" + startX + "x" + startY + ", " + (startX + width) +
                    "x" + (startY + height) + ") of space (0x0, " + space.width + "x" + space.height + ")");
        }

        StringBuilder builder = new StringBuilder("1,");
        builder.append(width).append(",").append(height).append(",");

        Seq<String> blockList = new Seq<>(true, 8);
        ShortSeq blockListIDs = new ShortSeq(true, 8);

        for (int x = startX; x < startX + width; x++) for (int y = startY; y < startY + height; y++) {
            Tile tile = space.get(x, y);
            if (tile.floor() != Blocks.empty) {
                if (!blockListIDs.contains(tile.floor().id)) {
                    blockList.add(tile.floor().name);
                    blockListIDs.add(tile.floor().id);
                }
            }
            if (tile.block() != Blocks.air && tile.isCenter()) {
                if (!blockListIDs.contains(tile.block().id)) {
                    blockList.add(tile.block().name);
                    blockListIDs.add(tile.block().id);
                }
            }
            if (tile.overlay() != Blocks.air) {
                if (!blockListIDs.contains(tile.overlay().id)) {
                    blockList.add(tile.overlay().name);
                    blockListIDs.add(tile.overlay().id);
                }
            }
        }

        builder.append(blockList.size).append(",");
        for (String block : blockList) {
            builder.append(block).append(",");
        }

        for (int x = startX; x < startX + width; x++) for (int y = startY; y < startY + height; y++) {
            Tile tile = space.get(x, y);

            if (tile.floor() != Blocks.empty) {
                int id = blockListIDs.indexOf(tile.floor().id);
                builder.append(id + 1).append(",");
            } else {
                builder.append("0,");
            }
            if (tile.block() != Blocks.air && tile.isCenter()) {
                int id = blockListIDs.indexOf(tile.block().id);
                builder.append(id + 1).append(",")
                        .append(tile.build == null ? 0 : tile.build.rotation).append(",");
            } else {
                builder.append("0,");
            }
            if (tile.overlay() != Blocks.air) {
                int id = blockListIDs.indexOf(tile.overlay().id);
                builder.append(id + 1).append(",");
            } else {
                builder.append("0,");
            }
            builder.append(Long.toHexString(tile.getPackedData())).append(",");
        }

        return Result.ok(builder.toString());
    }

    public static Result<Metrics> decodeArea(String area, Team team, @Nullable Tiles tiles, int startX, int startY) {
        if (!area.startsWith("1,"))
            return new Result.Err<>("unsupported version");

        int ptr = 2;

        int newPtr = area.indexOf(",", ptr);
        if (newPtr == -1)
            return new Result.Err<>("invalid area format");
        int width = Strings.parseInt(area, 10, -1, ptr, newPtr);
        if (width < 0)
            return new Result.Err<>("invalid width (" + width + " < 0)");
        ptr = newPtr + 1;

        newPtr = area.indexOf(",", ptr);
        if (newPtr == -1)
            return new Result.Err<>("invalid area format");
        int height = Strings.parseInt(area, 10, -1, ptr, newPtr);
        if (height < 0)
            return new Result.Err<>("invalid height (" + height + " < 0)");
        ptr = newPtr + 1;

        newPtr = area.indexOf(",", ptr);
        if (newPtr == -1)
            return new Result.Err<>("invalid area format");
        int blockCount = Strings.parseInt(area, 10, -1, ptr, newPtr);
        if (blockCount < 0)
            return new Result.Err<>("invalid block count (" + blockCount + " < 0)");
        ptr = newPtr + 1;

        Block[] blocks = new Block[blockCount];
        int i = 0;
        while (blockCount-- > 0) {
            newPtr = area.indexOf(",", ptr);
            if (newPtr == -1)
                return new Result.Err<>("invalid area format");
            Block block = Vars.content.block(area.substring(ptr, newPtr));
            if (block == null)
                return new Result.Err<>("unknown block " + area.substring(ptr, newPtr));
            ptr = newPtr + 1;
            blocks[i++] = block;
        }

        for (int dx = 0; dx < width; dx++) for (int dy = 0; dy < height; dy++) {
            int x = startX + dx;
            int y = startY + dy;
            @Nullable Tile tile = tiles == null ? null : tiles.get(x, y);

            newPtr = area.indexOf(",", ptr);
            if (newPtr == -1)
                return new Result.Err<>("invalid area format");
            int blockId = Strings.parseInt(area, 10, -1, ptr, newPtr);
            if (blockId < 0)
                return new Result.Err<>("invalid floor id (" + blockId + " < 0)");
            if (blockId > blocks.length)
                return new Result.Err<>("invalid floor id (" + blockId + " > " + blocks.length + ")");
            ptr = newPtr + 1;

            if (blockId == 0) {}
            else if (blocks[blockId - 1].isFloor()) { if (tile != null) tile.setFloor(blocks[blockId - 1].asFloor()); }
            else return new Result.Err<>("invalid floor " + blocks[blockId - 1].name);

            newPtr = area.indexOf(",", ptr);
            if (newPtr == -1)
                return new Result.Err<>("invalid area format");
            blockId = Strings.parseInt(area, 10, -1, ptr, newPtr);
            if (blockId < 0)
                return new Result.Err<>("invalid block id (" + blockId + " < 0)");
            if (blockId > blocks.length)
                return new Result.Err<>("invalid block id (" + blockId + " > " + blocks.length + ")");
            ptr = newPtr + 1;

            if (blockId != 0) {
                newPtr = area.indexOf(",", ptr);
                if (newPtr == -1)
                    return new Result.Err<>("invalid area format");
                int rotation = Strings.parseInt(area, 10, Integer.MIN_VALUE, ptr, newPtr);
                if (rotation == Integer.MIN_VALUE)
                    return new Result.Err<>("invalid rotation");
                ptr = newPtr + 1;

                if (tile != null) tile.setBlock(blocks[blockId - 1], team, rotation);
            }

            newPtr = area.indexOf(",", ptr);
            if (newPtr == -1)
                return new Result.Err<>("invalid area format");
            blockId = Strings.parseInt(area, 10, -1, ptr, newPtr);
            if (blockId < 0)
                return new Result.Err<>("invalid overlay id (" + blockId + " < 0)");
            if (blockId > blocks.length)
                return new Result.Err<>("invalid overlay id (" + blockId + " > " + blocks.length + ")");
            ptr = newPtr + 1;

            if (blockId != 0 && tile != null) tile.setOverlay(blocks[blockId - 1]);

            newPtr = area.indexOf(",", ptr);
            if (newPtr == -1)
                return new Result.Err<>("invalid area format");
            long packedData = Strings.parseLong(area, 10, ptr, newPtr, Long.MIN_VALUE);
            if (packedData == Long.MIN_VALUE)
                return new Result.Err<>("invalid packed data");

            if (tile != null) tile.setPackedData(packedData);

            ptr = newPtr + 1;
        }

        if (ptr != area.length())
            return new Result.Err<>("could not parse the whole data (" + (area.length() - ptr) + " bytes remaining)");

        return Result.ok(new Metrics(width, height));
    }
}
