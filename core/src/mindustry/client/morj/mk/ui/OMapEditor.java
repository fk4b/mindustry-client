package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Pixmap;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Log;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.rules.Gamemode;
import mindustry.client.morj.mk.rules.MRules;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.editor.MapEditor;
import mindustry.game.Rules;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.io.MapIO;
import mindustry.maps.Map;
import mindustry.mod.DataPatcher;
import mindustry.world.Tile;
import mindustry.world.Tiles;
import mindustry.world.WorldContext;

import java.io.IOException;

public class OMapEditor extends MapEditor {
    private final Context context = new Context();
    private boolean loading;

    private DrawOperation currentOp;
    private Seq<DrawOperation> undoStack;
    private Seq<DrawOperation> redoStack;
    private Seq<Rules> rulesUndoStack;
    private Seq<Rules> rulesRedoStack;
    private boolean rulesPushOnChange;

    public boolean undoing = false;

    public OMapEditor() {
        super();
    }

    @Override
    public boolean isLoading() {
        return loading;
    }

    public static void inject() {
        Vars.editor = new OMapEditor();
    }

    @Override
    public void beginEdit(Map map) {}
    @Override
    public void beginEdit(Pixmap pixmap) {}
    @Override
    public void beginEdit(int width, int height) {}

    public void OBeginEdit(int width, int height) {
        reset();

        loading = true;
        createTiles(width, height);
        renderer.resize(width, height);
        MVars.rules = new MRules(Vars.state.rules, Vars.world.width(), Vars.world.height());
        undoStack = new Seq<>(16);
        redoStack = new Seq<>(4);
        rulesUndoStack = new Seq<>(4);
        rulesRedoStack = new Seq<>(2);
        loading = false;
    }

    public void OBeginEdit(Fi file) {
        try {
            OBeginEdit(MapIO.createMap(file, true));
        } catch (IOException e) {
            Vars.ui.showException(e);
        }
    }

    public void OBeginEdit(Map map) {
        reset();

        undoStack = new Seq<>(16);
        redoStack = new Seq<>(4);

        loading = true;
        tags.putAll(map.tags);
        if (map.file.parent().parent().name().equals("1127400") && Vars.steam) {
            tags.put("steamid", map.file.parent().name());
        }
        load(() -> MapIO.loadMap(map, context));
        renderer.resize(width(), height());
        MVars.rules = new MRules(Vars.state.rules, Vars.world.width(), Vars.world.height());

        if (MVars.rules.gamemode() != null) {
            a: {
                if (Vars.state.patcher.patches.size == 0) break a;
                DataPatcher.PatchSet patches = Vars.state.patcher.patches.first();
                if (!patches.name.equals("Mindurka Default Patch")) break a;
                Vars.state.patcher.patches.remove(0);
            }

            Gamemode.Impl gamemode = MVars.rules.gamemode();

            try {
                Seq<String> patches = Vars.state.patcher.patches.map(x -> x.patch);
                b: if (gamemode != null) {
                    String patch = gamemode.builtInContentPatch();
                    if (patch == null) break b;
                    patches.insert(0, "name: Mindurka Default Patch\n" + patch);
                }
                Vars.state.patcher.apply(patches);
            } catch (Exception error) {
                Log.err(error);
                Vars.ui.showException(error);
            }
        }

        loading = false;

        if (MVars.rules.originalPatchVer < 7) Core.app.post(() -> {
            Groups.build.each(Building::heal);
        });

        MVars.editorDialog.build();
    }

    public void OBeginEdit(Pixmap pixmap) {
        reset();

        undoStack = new Seq<>(16);
        redoStack = new Seq<>(4);

        loading = true;
        createTiles(pixmap.width, pixmap.height);
        load(() -> MapIO.readImage(pixmap, tiles()));
        renderer.resize(pixmap.width, pixmap.height);
        MVars.rules = new MRules(Vars.state.rules, Vars.world.width(), Vars.world.height());
        // 'load' sets this to 'false'. What's the fucking point?
        loading = false;
    }

    private void reset() {
        clearOp();
        brushSize = 1;
        drawBlock = Blocks.stone;
        tags = new StringMap();
    }

    public DrawOperation currentOp() {
        if (currentOp == null) currentOp = new DrawOperation();
        return currentOp;
    }

    @Override
    public void clearOp() {
        super.clearOp();
        currentOp = null;
    }

    @Override
    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    @Override
    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    @Override
    public void flushOp() {
        redoStack.clear();
        if (currentOp == null) return;
        currentOp.maybeCompress();
        undoStack.add(currentOp);
        currentOp = null;
    }

    public void undoCurrentOp() {
        if (currentOp == null) return;
        DrawOperation that = currentOp;
        that.maybeCompress();
        currentOp = null;
        undoing = true;
        that.undo();
        undoing = false;
        if (currentOp != null) redoStack.add(currentOp);
        currentOp = null;
    }

    @Override
    public void undo() {
        if (currentOp != null) {
            undoCurrentOp();
            return;
        }
        if (undoStack.isEmpty()) return;

        DrawOperation op = undoStack.pop();
        undoing = true;
        op.undo();
        undoing = false;
        if (currentOp != null) redoStack.add(currentOp);
        currentOp = null;
    }

    @Override
    public void redo() {
        if (currentOp != null) {
            currentOp.maybeCompress();
            undoStack.add(currentOp);
            currentOp = null;
        }

        if (redoStack.isEmpty()) return;

        undoing = true;
        redoStack.pop().undo();
        if (currentOp != null) {
            currentOp.maybeCompress();
            undoStack.add(currentOp);
        }
        currentOp = null;
        undoing = false;
    }

    public void activateRulesStack() {
        rulesPushOnChange = true;
    }

    public void pushRulesChanged() {
        if (!rulesPushOnChange) return;
        rulesPushOnChange = false;
        rulesRedoStack.clear();
        rulesUndoStack.add(Vars.state.rules.copy());
    }

    public void rulesUndo() {
        if (rulesUndoStack.isEmpty()) return;
        rulesPushOnChange = true;
    }
    public void rulesRedo() {
    }

    @Override
    public void load(Runnable r) {
        loading = true;
        r.run();
        loading = false;
    }

    private void createTiles(int width, int height) {
        Tiles tiles = Vars.world.resize(width, height);

        for (int x = 0; x < width; x++) for (int y = 0; y < height; y++) {
            tiles.set(x, y, new EditorTile(x, y, Blocks.stone.id, 0, 0));
        }
    }

    @Override
    public void resize(int width, int height, int shiftX, int shiftY) {
        super.resize(width, height, shiftX, shiftY);

        for (int i = 0; i < width * height; i++) {
            Tile tile = Vars.world.tiles.geti(i);
            if (tile instanceof mindustry.editor.EditorTile) {
                Tile newTile = new EditorTile(tile.x, tile.y, tile.floorID(), tile.overlayID(), tile.blockID());
                newTile.data = tile.data;
                newTile.extraData = tile.extraData;
                newTile.floorData = tile.floorData;
                newTile.overlayData = tile.overlayData;
                newTile.build = tile.build;
                Vars.world.tiles.seti(i, newTile);
            }
        }

        MVars.rules = new MRules(Vars.state.rules, Vars.world.width(), Vars.world.height());
    }

    class Context implements WorldContext {
        @Override
        public Tile tile(int index) {
            return Vars.world.tiles.geti(index);
        }

        @Override
        public void resize(int width, int height) {
            Vars.world.resize(width, height);
        }

        @Override
        public Tile create(int x, int y, int floorID, int overlayID, int wallID) {
            Tile tile = new EditorTile(x, y, floorID, overlayID, wallID);
            tiles().set(x, y, tile);
            return tile;
        }

        @Override
        public boolean isGenerating() {
            return Vars.world.isGenerating();
        }

        @Override
        public void begin() {
            Vars.world.beginMapLoad();
        }

        @Override
        public void end() {
            Vars.world.endMapLoad();
        }
    }
}
