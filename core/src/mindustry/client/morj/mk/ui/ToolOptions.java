package mindustry.client.morj.mk.ui;

import arc.util.Nullable;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.rules.Castle;
import mindustry.client.morj.mk.rules.FortsPlotState;
import mindustry.client.morj.mk.rules.Gamemode;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Block;


public class ToolOptions {
    public static class Slot {
        public Block selectedBlock = Blocks.coreShard;
        public Team team = Team.sharded;

        public void reset() {
            selectedBlock = Blocks.stone;
            team = Team.sharded;
        }
    }

    // uwu
    public Slot[] available = new Slot[] {
            new Slot(), new Slot(), new Slot(), new Slot(), new Slot(),
            new Slot(), new Slot(), new Slot(), new Slot(), new Slot(),
    };
    public Slot current = available[0];

    public boolean fortsCarverPlace = true;
    public int radius = 1;
    public EditorTool tool = EditorTool.pencil;
    public EditorMode mode = EditorMode.normal;

    public Block selectedBlock() {
        if (mode.downsizeBlock) {
            if (current.selectedBlock.isOverlay()) return Blocks.oreCopper;
            else if (current.selectedBlock.isFloor()) return Blocks.stone;
            else return Blocks.stoneWall;
        }

        return current.selectedBlock;
    }
    public Team team() {
        return current.team;
    }

    public @Nullable BitMap fakeCliffsMap;
    public boolean cliffAuto;
    public byte cliffSides;
    public Blend blend;
    public FortsPlotState fortsPlotState = FortsPlotState.enabled;
    public boolean floorsAsOverlays = false;
    public String hubServer = "";
    public Item selectedItemCastle = Items.copper;
    public boolean invincible = false;
    public int blockCost = 0;
    public int minerCost = 0;
    public int minerInterval = 0;
    public int minerAmount = 0;
    public Block minerDrill = Blocks.blastDrill;

    private Gamemode.Impl gamemode() {
        return MVars.rules.gamemode();
    }

    public int blockCostFor(Block block) {
        Gamemode.Impl gm = gamemode();
        if (gm instanceof Castle.Impl) {
            blockCost = ((Castle.Impl) gm).blockCostFor(block);
            return blockCost;
        }
        return 0;
    }

    public int minerIntervalFor(Item item) {
        Gamemode.Impl gm = gamemode();
        if (gm instanceof Castle.Impl) {
            minerInterval = ((Castle.Impl) gm).itemIntervalFor(item);
            return minerInterval;
        }
        return 0;
    }

    public int minerCostFor(Item item) {
        Gamemode.Impl gm = gamemode();
        if (gm instanceof Castle.Impl) {
            minerCost = ((Castle.Impl) gm).itemCostFor(item);
            return minerCost;
        }
        return 0;
    }

    public int minerAmountFor(Item item) {
        Gamemode.Impl gm = gamemode();
        if (gm instanceof Castle.Impl) {
            minerAmount = ((Castle.Impl) gm).itemAmountFor(item);
            return minerAmount;
        }
        return 0;
    }


    public @Nullable BitMap fakeCliffsMap() {
        if (!cliffAuto) return null;
        if (fakeCliffsMap == null || Vars.world.width() != fakeCliffsMap.width || Vars.world.height() != fakeCliffsMap.height)
            fakeCliffsMap = BitMap.of(Vars.world.width(), Vars.world.height());
        return fakeCliffsMap;
    }

    public void reset() {
        for (Slot slot : available) slot.reset();

        tool = EditorTool.pencil;
        mode = EditorMode.normal;
        radius = 1;
        fortsCarverPlace = true;
        fortsPlotState = FortsPlotState.enabled;
        cliffAuto = false;
        cliffSides = (byte) 0;
        blend = Blend.normal;
        hubServer = "";
    }
}
