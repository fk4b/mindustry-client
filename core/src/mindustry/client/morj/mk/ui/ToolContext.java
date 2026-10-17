package mindustry.client.morj.mk.ui;

import arc.util.Nullable;
import mindustry.game.Team;
import mindustry.world.Block;
import mindustry.world.blocks.environment.Floor;

public interface ToolContext {
    void set(int x, int y, @Nullable Block block, @Nullable Floor floor, @Nullable Block overlay);
    void setBlock(int x, int y, Block block);
    void setBlock(int x, int y, Block block, int rotation, Team team);
    void setBlock(int x, int y, Block block, int rotation, Team team, byte data);
    void setFloor(int x, int y, Floor floor);
    void setOverlay(int x, int y, Block overlay);

    void setAny(int x, int y, Block block);

    Block block(int x, int y);
    Floor floor(int x, int y);
    Block overlay(int x, int y);
    Team team(int x, int y);

    int width();
    int height();

    /**
     * Check if any writes will be actually applied to a layer.
     *
     * @return {@code true} if there is an underlying layer, otherwise {@code false}.
     */
    boolean isLayer();
    /**
     * Check if using the erase mode.
     *
     * @return {@code true} if we are indeed erasing, otherwise {@code false}.
     */
    boolean isErase();
    boolean unsizedBlocks();
}
