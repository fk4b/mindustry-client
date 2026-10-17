package mindustry.client.morj.mk.ui;

import lombok.RequiredArgsConstructor;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;

@RequiredArgsConstructor
public class PreviewToolContext implements ToolContext {
    public final BitMap bitmap;

    public boolean hasRegion = false;
    public int startx = 0, starty = 0, endx = 0, endy = 0;

    private void enable(Block block, int x, int y) {
        EditorTool.square(x, y, block.size, this::enable);
    }
    private void enable(int x, int y) {
        if (!hasRegion) {
            startx = x;
            starty = y;
            endx = x;
            endy = y;
            hasRegion = true;
        } else {
            if (x < startx) startx = x;
            if (y < starty) starty = y;
            if (x > endx) endx = x;
            if (y > endy) endy = y;
        }

        bitmap.enable(x, y);
    }

    public void zero() {
        bitmap.zero();
        hasRegion = false;
    }

    @Override
    public void set(int x, int y, Block block, Floor floor, Block overlay) {
        if (floor != null || overlay != null) this.enable(x, y);
        if (block != null) enable(block, x, y);
    }

    @Override
    public void setBlock(int x, int y, Block block) {
        if (block != null) enable(block, x, y);
    }

    @Override
    public void setBlock(int x, int y, Block block, int rotation, Team team) {
        if (block != null) enable(block, x, y);
    }

    @Override
    public void setBlock(int x, int y, Block block, int rotation, Team team, byte data) {
        if (block != null)  enable(block, x, y);
    }

    @Override
    public void setFloor(int x, int y, Floor floor) {
        if (floor != null) this.enable(x, y);
    }

    @Override
    public void setOverlay(int x, int y, Block overlay) {
        if (overlay != null) this.enable(x, y);
    }

    @Override
    public void setAny(int x, int y, Block block) {
        if (block.isFloor() || block.isOverlay()) this.enable(x, y);
        else enable(block, x, y);
    }

    @Override
    public Block block(int x, int y) {
        Tile tile = Vars.world.tile(x, y);
        if (tile == null) return Blocks.air;
        return tile.block();
    }

    @Override
    public Floor floor(int x, int y) {
        Tile tile = Vars.world.tile(x, y);
        if (tile == null) return Blocks.empty.asFloor();
        return tile.floor();
    }

    @Override
    public Block overlay(int x, int y) {
        Tile tile = Vars.world.tile(x, y);
        if (tile == null) return Blocks.air;
        return tile.overlay();
    }

    @Override
    public Team team(int x, int y) {
        Tile tile = Vars.world.tile(x, y);
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
        return false;
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
