package mindustry.client.morj.mk.ui;

import arc.func.Prov;
import arc.util.Reflect;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.util.Hack;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.editor.EditorRenderer;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.graphics.CacheLayer;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.environment.Floor;

public class EditorTile extends Tile {
    public EditorTile(int x, int y, int floor, int overlay, int wall) {
        super(x, y, floor, overlay, wall);
    }

    private static final Object[] cachedArgs2 = new Object[2];
    private static final Object[] cachedArgs1 = new Object[1];

    @Override
    public boolean isEditorTile() { return true; }

    @Override
    public void setFloor(Floor type) {
        if (skip()) {
            super.setFloor(type);
            return;
        }

        if (floor == type && !type.saveData) return;

        Vars.world.floorChanges++;

        if (floor.saveData) MVars.mapEditor.currentOp().floorData(floorData);
        if (block.saveData || overlay.saveData || floor.saveData) MVars.mapEditor.currentOp().extraData(extraData);
        MVars.mapEditor.currentOp().floor(floor, x, y);

        Floor oldFloor = floor;
        floor = type;
        Hack.blockRemoved(oldFloor, this);
        type.floorChanged(this);

        updateStatic();
    }

    @Override
    public void setBlock(Block type, Team team, int rotation, Prov<Building> entityprov) {
        if (skip()) {
            super.setBlock(type, team, rotation, entityprov);
            return;
        }

        if (block == type && (build == null || build.rotation == rotation && build.team == team) && !type.saveData) return;

        if (block.saveData) MVars.mapEditor.currentOp().blockData(data);
        if (block.saveData || overlay.saveData || floor.saveData) MVars.mapEditor.currentOp().extraData(extraData);

        Block prevBlock = block;
        EditorTile prevCenter = build == null ? this : (EditorTile) build.tile;
        Building prevBuild = build;
        Team prevTeam = team();

        DrawOperation op = MVars.mapEditor.currentOp();

        super.setBlock(type, team, rotation, entityprov);

        if (requiresBlockUpdate(prevBlock) || requiresBlockUpdate(type)) {
            if (prevBlock.size > 1) prevCenter.getLinkedTilesAs(prevBlock, this::updateBlock);
            getLinkedTiles(this::updateBlock);
        } else {
            Vars.renderer.blocks.updateShadowTile(this);
        }

        if (build != null) build.wasVisible = true;

        Vars.world.tileChanges++;

        Hack.blockRemoved(prevBlock, this);
        type.blockChanged(this);

        op.block(prevBlock, prevCenter.x, prevCenter.y, prevTeam, prevBuild == null ? 0 : prevBuild.rotation, prevBuild);
    }

    @Override
    public void setOverlay(Block type) {
        if (skip()) {
            super.setOverlay(type);
            return;
        }

        if (this.overlay == type && !type.saveData) return;

        assert type.isOverlay();

        if (overlay.saveData) MVars.mapEditor.currentOp().overlayData(overlayData);
        if (block.saveData || overlay.saveData || floor.saveData) MVars.mapEditor.currentOp().extraData(extraData);

        Vars.world.floorChanges++;

        Floor oldOverlay = overlay;
        overlay = type.asFloor();

        Hack.floorRemoved(oldOverlay, this);
        overlay.floorChanged(this);
        updateStatic();

        MVars.mapEditor.currentOp().overlay(oldOverlay, x, y);
    }

    public void placeEnded(Block type) {
        if (!type.saveData) return;

        type.placeEnded(this, null, 0, MVars.toolOptions.selectedBlock().lastConfig);
    }

    @Override
    public boolean isDarkened() {
        return skip() && super.isDarkened();
    }

    @Override
    protected void fireChanged(){
        if(Vars.state.isGame()){
            super.fireChanged();
        }else{
            updateStatic();
        }
    }

    @Override
    protected void firePreChanged(){
        if(Vars.state.isGame()){
            super.firePreChanged();
        }else{
            updateStatic();
        }
    }

    @Override
    public void recache(){
        if(skip()){
            super.recache();
        }
    }

    @Override
    protected void changed(){
        if(Vars.state.isGame()){
            super.changed();
        }
    }

    private void updateStatic() {
        cachedArgs2[0] = (int) x;
        cachedArgs2[1] = (int) y;
        Reflect.invoke(EditorRenderer.class, MVars.mapEditor.renderer, "updateStatic", cachedArgs2, int.class, int.class);
    }

    private void updateBlock(Tile tile) {
        cachedArgs1[0] = tile;
        Reflect.invoke(EditorRenderer.class, MVars.mapEditor.renderer, "updateBlock", cachedArgs1, Tile.class);
    }

    private boolean requiresBlockUpdate(Block block){
        return block != Blocks.air && block.cacheLayer == CacheLayer.normal;
    }

    private boolean skip() {
        return Vars.state.isGame() || MVars.mapEditor == null || MVars.mapEditor.isLoading() || Vars.world.isGenerating() || MVars.editorDialog.loading;
    }
}
