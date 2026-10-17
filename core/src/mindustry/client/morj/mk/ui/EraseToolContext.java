package mindustry.client.morj.mk.ui;

import arc.util.Nullable;
import mindustry.client.morj.mk.CliffUtil;
import mindustry.client.morj.mk.MVars;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;

public class EraseToolContext implements ToolContext {
    public static EraseToolContext i = new EraseToolContext();

    private void actuallyDeleteBlock(@Nullable Tile tile) {
        if (tile == null) return;
        boolean recalculateCliffs = MVars.toolOptions.cliffAuto && (tile.block() == Blocks.cliff || MVars.toolOptions.fakeCliffsMap().toggled(tile.x, tile.y));
        tile.setBlock(Blocks.air, Team.derelict, 0);
        if (recalculateCliffs) {
            MVars.toolOptions.fakeCliffsMap().disable(tile.x, tile.y);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) {
                Tile tile$1 = Vars.world.tile(tile.x + dx, tile.y + dy);
                if (tile$1 == null) continue;
                if (tile$1.block() != Blocks.cliff && !MVars.toolOptions.fakeCliffsMap().toggled(tile$1.x, tile$1.y)) continue;
                byte cliffs = CliffUtil.recalculateCliff(this, tile$1.x, tile$1.y);
                if (cliffs == 0) {
                    MVars.toolOptions.fakeCliffsMap().enable(tile$1.x, tile$1.y);
                    tile$1.setBlock(Blocks.air, Team.derelict, 0);
                } else {
                    MVars.toolOptions.fakeCliffsMap().disable(tile$1.x, tile$1.y);
                    tile$1.setBlock(Blocks.air, Team.derelict, 0);
                    tile$1.setBlock(Blocks.cliff, Team.derelict, 0);
                    tile$1.data = cliffs;
                }
            }
        }
    }

    @Override
    public void set(int x, int y, Block block, Floor floor, Block overlay) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (floor != null) tile.setFloor(Blocks.empty.asFloor());
        if (overlay != null) tile.setOverlay(Blocks.air);
        if (block != null) {
            EditorTool.square(x, y, block.size, (x$1, y$1) ->  {
                Tile tile$1 = Vars.world.tiles.get(x$1, y$1);
                if (tile$1 == null) return;
                actuallyDeleteBlock(tile$1);
            });
        }
    }

    @Override
    public void setBlock(int x, int y, Block block) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (block != null) {
            EditorTool.square(x, y, block.size, (x$1, y$1) ->  {
                Tile tile$1 = Vars.world.tiles.get(x$1, y$1);
                if (tile$1 == null) return;
                actuallyDeleteBlock(tile$1);
            });
        }
    }

    @Override
    public void setBlock(int x, int y, Block block, int rotation, Team team) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (block != null) {
            EditorTool.square(x, y, block.size, (x$1, y$1) ->  {
                Tile tile$1 = Vars.world.tiles.get(x$1, y$1);
                if (tile$1 == null) return;
                actuallyDeleteBlock(tile$1);
            });
        }
    }

    @Override
    public void setBlock(int x, int y, Block block, int rotation, Team team, byte data) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (block != null) {
            EditorTool.square(x, y, block.size, (x$1, y$1) ->  {
                Tile tile$1 = Vars.world.tiles.get(x$1, y$1);
                if (tile$1 == null) return;
                actuallyDeleteBlock(tile$1);
            });
        }
        tile.data = data;
    }

    @Override
    public void setAny(int x, int y, Block block) {
        if (block == null) return;
        if (block.isOverlay()) setOverlay(x, y, block);
        else if (block.isFloor()) setFloor(x, y, block.asFloor());
        else setBlock(x, y, block);
    }

    @Override
    public void setFloor(int x, int y, Floor floor) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (floor != null) {
            if (MVars.toolOptions.floorsAsOverlays) tile.setOverlay(Blocks.air.asFloor());
            else tile.setFloor(Blocks.empty.asFloor());
        }
    }

    @Override
    public void setOverlay(int x, int y, Block overlay) {
        Tile tile = Vars.world.tiles.get(x, y);
        if (tile == null) return;
        if (overlay != null) tile.setOverlay(Blocks.air);
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
        if (tile == null) return Blocks.empty.asFloor();
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
        return true;
    }

    @Override
    public boolean unsizedBlocks() {
        return false;
    }
}
