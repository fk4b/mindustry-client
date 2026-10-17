package mindustry.client.morj.mk.ui;

import mindustry.client.morj.mk.CliffUtil;
import mindustry.client.morj.mk.MVars;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;

public class LayerToolContext implements ToolContext {
    public static LayerToolContext i = new LayerToolContext();

    public boolean blendPermitted(int x, int y, int size) {
        if (MVars.toolOptions.blend == Blend.replace) {
            return !EditorTool.squareAny(x, y, size, (x$1, y$1) -> {
                Tile tile = Vars.world.tiles.get(x$1, y$1);
                if (tile == null) return true;
                return tile.block() == Blocks.air;
            });
        }
        else if (MVars.toolOptions.blend == Blend.under) {
            return !EditorTool.squareAny(x, y, size, (x$1, y$1) -> {
                Tile tile = Vars.world.tiles.get(x$1, y$1);
                if (tile == null) return false;
                return tile.block() != Blocks.air;
            });
        }
        return true;
    }

    private void actualSetBlock(int x, int y, Block block, int rotation, Team team) {
        if (block == Blocks.cliff) {
            if (MVars.toolOptions.cliffAuto) {
                actualSetBlock(x, y, block, rotation, team, CliffUtil.recalculateCliff(this, x, y));
                for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) {
                    Tile tile = Vars.world.tile(x + dx, y + dy);
                    if (tile == null) continue;
                    if (tile.block() != Blocks.cliff && !MVars.toolOptions.fakeCliffsMap.toggled(x + dx, y + dy) || dx == 0 && dy == 0) continue;
                    byte data = CliffUtil.recalculateCliff(this, x + dx, y + dy);
                    actualSetBlock(x + dx, y + dy, block, rotation, team, data);
                }
            } else {
                actualSetBlock(x, y, block, rotation, team, MVars.toolOptions.cliffSides);
            }

            return;
        }

        actualSetBlock(x, y, block, rotation, team, (byte) 0);
    }
    private void actualSetBlock(int x, int y, Block block, int rotation, Team team, byte data) {
        if (block == null) return;
        if (!blendPermitted(x, y, block.size)) return;

        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (MVars.toolOptions.cliffAuto) {
            if (block == Blocks.cliff && data == 0) {
                tile.setBlock(Blocks.air);
                MVars.toolOptions.fakeCliffsMap().enable(x, y);
                return;
            } else MVars.toolOptions.fakeCliffsMap().disable(x, y);
        }
        tile.data = data;
        tile.setBlock(Blocks.air);
        tile.setBlock(block, team, rotation);
        ((EditorTile) tile).placeEnded(block);
    }

    private void actualSetFloor(int x, int y, Floor floor, byte data) {
        if (floor == null) return;
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        tile.floorData = data;
        if (MVars.toolOptions.floorsAsOverlays) tile.setOverlay(floor);
        else tile.setFloor(floor);
        ((EditorTile) tile).placeEnded(floor);
    }

    @Override
    public void set(int x, int y, Block block, Floor floor, Block overlay) {
        if (block != null) actualSetBlock(x, y, block, 0, MVars.toolOptions.team());
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (floor != null) actualSetFloor(x, y, floor, (byte) 0);
        if (overlay != null) tile.setOverlay(overlay);
    }

    @Override
    public void setBlock(int x, int y, Block block) {
        actualSetBlock(x, y, block, 0, MVars.toolOptions.team());
    }

    @Override
    public void setBlock(int x, int y, Block block, int rotation, Team team) {
        actualSetBlock(x, y, block, rotation, team);
    }

    @Override
    public void setBlock(int x, int y, Block block, int rotation, Team team, byte data) {
        actualSetBlock(x, y, block, rotation, team, data);
    }

    @Override
    public void setAny(int x, int y, Block block) {
        if (block == null) return;
        if (block.isOverlay()) setOverlay(x, y, block);
        else if (block.isFloor()) setFloor(x, y, block.asFloor());
        else actualSetBlock(x, y, block, 0, MVars.toolOptions.team());
    }

    @Override
    public void setFloor(int x, int y, Floor floor) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (floor != null) actualSetFloor(x, y, floor, (byte) 0);
    }

    @Override
    public void setOverlay(int x, int y, Block overlay) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (overlay != null) {
            tile.setOverlay(overlay);
            ((EditorTile) tile).placeEnded(overlay);
        }
    }

    @Override
    public Block block(int x, int y) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return Blocks.air;
        return tile.block();
    }

    @Override
    public Floor floor(int x, int y) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) {
            if (MVars.toolOptions.floorsAsOverlays) return Blocks.air.asFloor();
            return Blocks.empty.asFloor();
        }
        if (MVars.toolOptions.floorsAsOverlays) return tile.overlay();
        return tile.floor();
    }

    @Override
    public Block overlay(int x, int y) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return Blocks.air;
        return tile.overlay();
    }

    @Override
    public Team team(int x, int y) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return Team.derelict;
        return tile.team();
    }

    @Override
    public int width() {
        return Vars.world.width();
    }

    @Override
    public int height() {
        return Vars.world.height();
    }

    @Override
    public boolean isLayer() {
        return true;
    }

    @Override
    public boolean isErase() {
        return false;
    }

    @Override
    public boolean unsizedBlocks() {
        return false;
    }
}
