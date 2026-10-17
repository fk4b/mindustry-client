package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.files.Fi;
import arc.func.Cons;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.scene.event.EventListener;
import arc.scene.event.Touchable;
import arc.scene.event.VisibilityListener;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.Dialog;
import arc.scene.ui.ImageButton;
import arc.scene.ui.Label;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.Slider;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.Align;
import arc.util.Log;
import arc.util.OS;
import arc.util.Reflect;
import arc.util.Strings;
import arc.util.Structs;
import arc.util.Time;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.Util;
import mindustry.client.morj.mk.rules.Gamemodes;
import mindustry.client.morj.mk.rules.MRules;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.GameState;
import mindustry.editor.EditorRenderer;
import mindustry.editor.MapEditorDialog;
import mindustry.editor.MapGenerateDialog;
import mindustry.editor.MapInfoDialog;
import mindustry.editor.MapLoadDialog;
import mindustry.editor.MapResizeDialog;
import mindustry.editor.MapView;
import mindustry.editor.SectorGenerateDialog;
import mindustry.game.Gamemode;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.game.Teams;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.gen.Unit;
import mindustry.io.MapIO;
import mindustry.maps.Map;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.ui.dialogs.LoadoutDialog;
import mindustry.ui.dialogs.MapPlayDialog;
import mindustry.world.Block;
import mindustry.world.blocks.environment.OverlayFloor;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.meta.BuildVisibility;
import mindustry.world.meta.Env;

public class OEditorDialog extends MapEditorDialog {
    private final OMapView view;
    private final OMapEditor editor;
    private MapInfoDialog infoDialog;
    private MapResizeDialog resizeDialog;
    public boolean loading = false;
    private final MapLoadDialog loadDialog = new MapLoadDialog(map -> Vars.ui.loadAnd(() -> {
        try {
            MVars.mapEditor.OBeginEdit(map);
        } catch (Exception e) {
            Vars.ui.showException(e);
            Log.err(e);
        }
    }));
    private BaseDialog menu;

    private Rules lastSavedRules = null;
    private MRules lastSavedMRules = null;
    private boolean reloadMap = false;
    private boolean shownWithMap = false;
    private Map map;
    private Runnable refreshTools;

    private final Seq<Block> blocksOut = new Seq<>();
    private Table blockSelection;
    private ScrollPane blocksPane;

    private Table blockOptions;

    public OEditorDialog(OMapEditor editor, MapEditorDialog oldDialog) {
        super();

        this.editor = editor;
        Reflect.set(MapEditorDialog.class, this, "resizeDialog", new OMapResizeDialog((width, height, shiftX, shiftY) -> {
            if (!(editor.width() == width && editor.height() == height && shiftX == 0 && shiftY == 0)) {
                Vars.ui.loadAnd(() -> {
                    editor.resize(width, height, shiftX, shiftY);
                });
            }
        }));
        resizeDialog = Reflect.get(MapEditorDialog.class, this, "resizeDialog");

        Reflect.set(MapEditorDialog.class, this, "loadDialog", loadDialog);
        resizeDialog.hidden(() -> {
            Reflect.set(MapResizeDialog.class, resizeDialog, "shiftX", 0);
            Reflect.set(MapResizeDialog.class, resizeDialog, "shiftY", 0);
        });

        view = (MVars.mapView = new OMapView());

        background(Styles.black);

        setFillParent(true);
        clearChildren();
        margin(0);

        for (int i = 0; i < 3; i++) {
            for (int o = getListeners().size - 1; o >= 0; o--) {
                EventListener listener = getListeners().get(o);
                if (listener instanceof VisibilityListener) {
                    removeListener(listener);
                    break;
                }
            }
        }

        update(() -> {
            if (Core.scene.getKeyboardFocus() == this) {
                doInput();
            }
        });

        shown(() -> {
            oldDialog.hide();

            beginLandscape();
            editor.clearOp();
            Core.scene.setScrollFocus(view);

            if (!shownWithMap) {
                Vars.logic.reset();
                Vars.state.rules = new Rules();
                editor.OBeginEdit(200, 200);
            } else if (MVars.rules.gamemode() != null) MVars.rules.gamemode().editingResumed();
            MVars.toolOptions.reset();
            MVars.rules = new MRules(Vars.state.rules, Vars.world.width(), Vars.world.height());
            shownWithMap = false;
        });

        hidden(() -> {
            editor.clearOp();
            Vars.platform.updateRPC();
            refreshTools = null;
            endLandscape();
        });

        shown(this::build);

        buildMenu();

        // Patch editor compatibility.
        infoDialog = Reflect.get(MapEditorDialog.class, this, "infoDialog");
    }

    @Override
    public MapView getView() {
        return view;
    }

    public void beginLandscape() {
        Vars.platform.beginForceLandscape();
    }

    public void endLandscape() {
        if (!Core.settings.getBool("landscape")) Vars.platform.endForceLandscape();
    }

    public void resumeEditing() {
        loading = true;
        Vars.state.set(GameState.State.menu);
        shownWithMap = true;
        reloadMap = false;
        show();
        Vars.state.rules = (lastSavedRules == null ? new Rules() : lastSavedRules);
        MVars.rules = (lastSavedMRules == null ? new MRules(Vars.state.rules, Vars.world.width(), Vars.world.height()) : lastSavedMRules);
        if (MVars.rules.gamemode() != null) MVars.rules.gamemode().editingResumed();
        lastSavedRules = null;
        lastSavedMRules = null;
        loading = false;
        Reflect.invoke(EditorRenderer.class, editor.renderer, "recache", Util.noargs);
        Reflect.invoke(EditorRenderer.class, editor.renderer, "recacheShadows", Util.noargs);
    }

    private void editInGame() {
        loading = true;
        menu.hide();
        Vars.ui.loadAnd(() -> {
            lastSavedRules = Vars.state.rules;
            lastSavedMRules = MVars.rules;
            hide();
            //only reset the player; logic.reset() will clear entities, which we do not want
            Vars.state.teams = new Teams();
            Vars.player.reset();
            Vars.state.rules = Gamemode.editor.apply(Vars.state.rules.copy());
            Vars.state.rules.limitMapArea = false;
            Vars.state.rules.sector = null;
            Vars.state.rules.fog = false;
            Vars.state.rules.schematicsAllowed = true;
            Vars.state.map = new Map(StringMap.of(
                    "name", "Editor Playtesting",
                    "width", editor.width(),
                    "height", editor.height()
            ));
            Vars.state.set(GameState.State.playing);
            Vars.world.endMapLoad();
            Vars.player.clearUnit();

            for(Unit unit : Groups.unit){
                if(unit.spawnedByCore){
                    unit.remove();
                }
            }

            Groups.build.clear();
            Groups.weather.clear();
            Vars.logic.play();

            Point2 center = view.project(Core.graphics.getWidth()/2f, Core.graphics.getHeight()/2f);

            CoreBlock.CoreBuild best = Vars.player.bestCore();

            Vars.player.set(center.x * Vars.tilesize, center.y * Vars.tilesize);
            Unit unit = (best != null ? ((CoreBlock)best.block).unitType
                    : (Vars.state.rules.hasEnv(Env.scorching) ? UnitTypes.evoke : UnitTypes.alpha)).spawn(editor.drawTeam, Vars.player.x, Vars.player.y);
            unit.spawnedByCore = true;
            Vars.player.unit(unit);
            Vars.player.set(unit);

            Core.camera.position.set(unit.x, unit.y);
        });
        loading = false;
    }

    @Override
    public void beginEditMap(Fi file) {
        Vars.ui.loadAnd(() -> {
            try {
                shownWithMap = true;
                map = MapIO.createMap(file, true);
                editor.OBeginEdit(map);
                show();
            } catch (Exception e) {
                Log.err(e);
                Vars.ui.showException("@editor.errorload", e);
            }
        });
    }

    private void playtest() {
        menu.hide();
        Map map = save();

        if(map != null){
            //skip dialog, play immediately when shift clicked
            if(Core.input.shift()){
                hide();
                //auto pick best fit
                Vars.control.playMap(map, map.applyRules(
                        Gamemode.survival.valid(map) ? Gamemode.survival :
                                Gamemode.attack.valid(map) ? Gamemode.attack :
                                        Gamemode.sandbox), true
                );
            } else {
                final MapPlayDialog playtestDialog = Reflect.get(MapEditorDialog.class, this, "playtestDialog");
                playtestDialog.playListener = this::hide;
                playtestDialog.show(map, true);
            }
        }
    }

    public void refreshTools() {
        if (refreshTools != null) refreshTools.run();
    }

    @Override
    public void build() {
        float size = Vars.mobile ? 50f : 58f;

        clearChildren();
        table(cont -> {
            cont.left();

            Table mid = new Table();
            {
                mid.top();

                mid.table(row -> {
                    Seq<Team> baseTeams = Seq.with(Team.baseTeams);
                    Seq<Team> coreTeams = Seq.with(Team.all)
                            .select(t -> t != null && !t.cores().isEmpty() && !baseTeams.contains(t));

                    Seq<Team> teams = baseTeams.addAll(coreTeams);
                    int i = 0;
                    for (Team team : teams) {
                        if (i > 0 && i % 6 == 0) row.row();
                        ImageButton button = new ImageButton(Tex.whiteui, Styles.clearNoneTogglei);
                        button.margin(4f);
                        button.getImageCell().grow();
                        button.getStyle().imageUpColor = team.color;
                        button.clicked(() -> MVars.toolOptions.current.team = team);
                        button.update(() -> button.setChecked(MVars.toolOptions.current.team == team));
                        row.add(button).size(size, size).left();
                        i++;
                    }
                }).growX().left();
                mid.row();

                Table tools = new Table().top();

                Table[] lastTable = { null };
                Cons<EditorTool> addTool = tool -> {
                    ImageButton button = new ImageButton(Vars.ui.getIcon(tool.name()), Styles.squareTogglei);
                    button.clicked(() -> {
                        MVars.toolOptions.tool = tool;
                        if (lastTable[0] != null) {
                            lastTable[0].remove();
                        }
                        rebuildBlockOptions();
                    });
                    button.update(() -> button.setChecked(MVars.toolOptions.tool == tool));
                    // buttons.add(button);

                    // Label mode = new Label("");
                    // mode.setColor(Pal.remove);
                    // mode.update(() -> mode.setText(tool.mode == -1 ? "" : "M" + (tool.mode + 1) + " "));
                    // mode.setAlignment(Align.bottomRight, Align.bottomRight);
                    // mode.touchable = Touchable.disabled;

                    tools.stack(button);
                };
                Cons<EditorMode> addMode = mode -> {
                    ImageButton button = new ImageButton(Vars.ui.getIcon(mode.name()), Styles.squareTogglei);
                    button.clicked(() -> {
                        MVars.toolOptions.mode = button.isChecked() ? mode : EditorMode.normal;
                        if (lastTable[0] != null) {
                            lastTable[0].remove();
                        }
                        rebuildBlockOptions();
                    });
                    button.update(() -> button.setChecked(MVars.toolOptions.mode == mode));
                    // buttons.add(button);

                    // Label mode = new Label("");
                    // mode.setColor(Pal.remove);
                    // mode.update(() -> mode.setText(tool.mode == -1 ? "" : "M" + (tool.mode + 1) + " "));
                    // mode.setAlignment(Align.bottomRight, Align.bottomRight);
                    // mode.touchable = Touchable.disabled;

                    tools.stack(button);
                };

                tools.defaults().size(size, size);

                mid.add(tools).top().padBottom(6).left();

                refreshTools = () -> {
                    tools.clear();

                    tools.button(Icon.menu, Styles.flati, () -> {
                        if (!menu.isShown()) {
                            menu.show();
                        }
                    });

                    ImageButton grid = tools.button(Icon.grid, Styles.squareTogglei, () -> view.setGrid(!view.isGrid())).get();
                    ImageButton undo = tools.button(Icon.undo, Styles.flati, editor::undo).get();
                    ImageButton redo = tools.button(Icon.redo, Styles.flati, editor::redo).get();

                    undo.setDisabled(() -> !editor.canUndo());
                    redo.setDisabled(() -> !editor.canRedo());
                    undo.getImage().setColor(editor.canUndo() ? Color.white : Color.gray);
                    redo.getImage().setColor(editor.canRedo() ? Color.white : Color.gray);

                    undo.update(() -> undo.getImage().setColor(editor.canUndo() ? Color.white : Color.gray));
                    redo.update(() -> redo.getImage().setColor(editor.canRedo() ? Color.white : Color.gray));
                    grid.update(() -> grid.setChecked(view.isGrid()));

                    addMode.get(EditorMode.zoom);
                    addMode.get(EditorMode.eraser);
                    tools.row();
                    addTool.get(EditorTool.pencil);
                    addTool.get(EditorTool.line);
                    addTool.get(EditorTool.fill);
                    addTool.get(EditorTool.rect);

                    if (MVars.rules.gamemode() != null && MVars.rules.gamemodeFactory() == Gamemodes.forts) {
                        tools.row();
                        if (EditorTool.fortsPlotToggle.visibleIf.get()) addTool.get(EditorTool.fortsPlotToggle);
                        if (EditorTool.fortsPlotCarver.visibleIf.get()) addTool.get(EditorTool.fortsPlotCarver);
                    }

                    if (MVars.rules.gamemode() != null && MVars.rules.gamemodeFactory() == Gamemodes.hub) {
                        tools.row();
                        addTool.get(EditorTool.hubServerConfig);
                    }
                    if (MVars.rules.gamemode() != null && MVars.rules.gamemodeFactory() == Gamemodes.castle) {
                        tools.row();
                        addTool.get(EditorTool.castleRoomPlace);
                        addTool.get(EditorTool.castleMinerPlacer);
                    }
                };
                refreshTools.run();

                mid.row();

                mid.table(t -> blockOptions = t).top().padBottom(6).left().growX().fillX();
                rebuildBlockOptions();
            }
            ScrollPane midPane = new ScrollPane(mid, Styles.smallPane);
            midPane.setFadeScrollBars(false);
            midPane.setOverscroll(false, false);
            midPane.setFillParent(true);
            Table midOverlay = new Table();
            midOverlay.table(t -> {
                ImageButton button = new ImageButton(Icon.cancel, Styles.clearNoneTogglei);
                button.margin(4f);
                button.getImageCell().grow();
                button.clicked(() -> {
                    view.editorAction = null;
                    view.redrawPreview = true;
                    button.setChecked(true);
                });
                button.setChecked(true);
                t.table(bt -> {
                    bt.add(button).size(size, size).left().top().center().row();
                }).center().row();

                Table optionsTable = new Table();
                t.add(optionsTable).left().top().row();
                SpecialEditorAction[] lastAction = {null};
                t.update(() -> {
                    if (view.editorAction == lastAction[0]) return;
                    lastAction[0] = view.editorAction;
                    optionsTable.clear();
                    if (view.editorAction instanceof mindustry.client.morj.mk.rules.SchematicEditorAction) {
                        ((mindustry.client.morj.mk.rules.SchematicEditorAction) view.editorAction).buildOptionsTable(optionsTable);
                    }
                });
            }).grow();
            midOverlay.background(Tex.whiteui);
            midOverlay.setColor(Color.black);
            midOverlay.visible(() -> view.editorAction != null);
            midOverlay.update(() -> {
                midOverlay.touchable = view.editorAction == null ? Touchable.disabled : Touchable.enabled;
            });
            midOverlay.setFillParent(true);
            cont.stack(midPane, midOverlay).margin(0).left().growY();

            cont.table(t -> t.add(view).grow()).grow();

            cont.table(this::addBlockSelection).right().growY();
        }).grow();
    }

    private void addBlockSelection(Table cont) {
        int cols = Vars.mobile ? 4 : 6;

        blockSelection = new Table();
        blockSelection.left();
        blocksPane = new ScrollPane(blockSelection, Styles.smallPane);
        blocksPane.setFadeScrollBars(false);
        blocksPane.setOverscroll(true, false);
        blocksPane.exited(() -> {
            if (blocksPane.hasScroll()) {
                Core.scene.setScrollFocus(view);
            }
        });

        Table[] configTable = { null };

        cont.table(search -> {
            search.image(Icon.zoom).padRight(8);
            search.field("", this::rebuildBlockSelection).growX()
                    .name("editor/search").maxTextLength(Vars.maxNameLength).get().setMessageText("@players.search");
        }).growX().pad(-2).padLeft(6f);
        cont.row();
        cont.table(Tex.underline, extra -> extra.labelWrap(() -> MVars.toolOptions.current.selectedBlock.localizedName).width(cols * 50f).center()).growX();
        cont.row();
        cont.collapser(t -> {
            configTable[0] = t;
        }, () -> MVars.toolOptions.current.selectedBlock != null && MVars.toolOptions.current.selectedBlock.editorConfigurable).with(c -> c.setEnforceMinSize(true)).growX().row();
        cont.add(blocksPane).expandY().growX().top().left();

        rebuildBlockSelection("");
    }

    private void rebuildBlockSelection(String searchText){
        int cols = Vars.mobile ? 4 : 6;

        blockSelection.clear();

        blocksOut.clear();
        blocksOut.addAll(Vars.content.blocks());
        blocksOut.sort((b1, b2) -> {
            int core = -Boolean.compare(b1 instanceof CoreBlock, b2 instanceof CoreBlock);
            if (core != 0) return core;
            int synth = Boolean.compare(b1.synthetic(), b2.synthetic());
            if (synth != 0) return synth;
            int ore = Boolean.compare(b1 instanceof OverlayFloor, b2 instanceof OverlayFloor);
            if (ore != 0) return ore;
            return Integer.compare(b1.id, b2.id);
        });

        int i = 0;

        for (Block block : blocksOut) {
            TextureRegion region = block.uiIcon;

            if ((!Core.atlas.isFound(region) || !block.inEditor
                    || block.buildVisibility == BuildVisibility.debugOnly) && block != Blocks.cliff
                    || (!searchText.isEmpty() && !block.localizedName.toLowerCase().contains(searchText.trim().replaceAll(" +", " ").toLowerCase()))
            ) continue;

            ImageButton button = new ImageButton(Tex.whiteui, Styles.clearNoneTogglei);
            button.getStyle().imageUp = new TextureRegionDrawable(region);
            button.clicked(() -> {
                MVars.toolOptions.current.selectedBlock = block;
                rebuildBlockOptions();
            });
            button.resizeImage(8 * 4f);
            button.update(() -> button.setChecked(MVars.toolOptions.current.selectedBlock == block));
            button.setChecked(MVars.toolOptions.current.selectedBlock == block);
            blockSelection.add(button).size(50f).tooltip(block.localizedName).left();

            if (i == 0) MVars.toolOptions.current.selectedBlock = block;

            if (++i % cols == 0) {
                blockSelection.row();
            }
        }

        if (i == 0) {
            blockSelection.add("@none.found").padLeft(54f).padTop(10f);
        }
    }

    void rebuildBlockOptions() {
        float size = Vars.mobile ? 50f : 58f;

        blockOptions.clearChildren();

        MVars.toolOptions.tool.toolOptions(blockOptions);

        if (!MVars.toolOptions.tool.blockTool) return;

        blockOptions.table(t -> {
            t.label(() -> "@editor.mindurka.brushsize").left().pad(4f);

            Slider slider = new Slider(1, 16, 1, false);
            slider.setValue(MVars.toolOptions.radius);
            slider.moved(f -> MVars.toolOptions.radius = (int) f);

            Label label = new Label(() -> Integer.toString(MVars.toolOptions.radius));
            label.setAlignment(Align.center);
            label.touchable = Touchable.disabled;

            t.stack(slider, label).left().get();

            t.table().growX().fillX();
        }).margin(4).left().growX().fillX();
        blockOptions.row();

        if (MVars.toolOptions.current.selectedBlock.isFloor() && !MVars.toolOptions.current.selectedBlock.isOverlay()) blockOptions.table(t -> {
            t.label(() -> "@mindurka.floorasoverlays").left().pad(4f);

            {
                ImageButton button = new ImageButton(Vars.ui.getIcon("floors-as-overlays-off"), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.floorsAsOverlays = false);
                button.update(() -> button.setChecked(!MVars.toolOptions.floorsAsOverlays));
                t.add(button).pad(4f).left().size(size, size).tooltip("@mindurka.floorasoverlays.floor");
            }

            {
                ImageButton button = new ImageButton(Vars.ui.getIcon("floors-as-overlays-on"), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.floorsAsOverlays = true);
                button.update(() -> button.setChecked(MVars.toolOptions.floorsAsOverlays));
                t.add(button).pad(4f).left().size(size, size).tooltip("@mindurka.floorasoverlays.overlay");
            }

            t.table().growX().fillX();
        }).margin(4).left().growX().fillX().row();

        blockOptions.table(t -> {
            t.label(() -> "@mindurka.blend").left().pad(4f);

            for (Blend value : Blend.values()) {
                ImageButton button = new ImageButton(Vars.ui.getIcon("blend-" + value.name()), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.blend = value);
                button.update(() -> button.setChecked(MVars.toolOptions.blend == value));
                t.add(button).pad(4f).left().size(size, size);
            }

            t.table().growX().fillX();
        }).margin(4).left().growX().fillX();
        blockOptions.row();

        if (MVars.toolOptions.current.selectedBlock == Blocks.cliff) {
            Table root = new Table();
            Table table = new Table();

            root.label(() -> "@editor.mindurka.cliffsides").left().pad(4f);
            root.row();
            root.add(table).left().growX().fillX();
            table.defaults().size(size, size).pad(0).left();

            {
                final int field = 5;
                ImageButton button = new ImageButton(Vars.ui.getIcon("cliff-" + field), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.cliffSides = Util.toggle(MVars.toolOptions.cliffSides, field));
                button.setDisabled(() -> MVars.toolOptions.cliffAuto);
                button.update(() -> button.setChecked(Util.enabled(MVars.toolOptions.cliffSides, field)));
                table.add(button).pad(0).left();
            }
            {
                final int field = 6;
                ImageButton button = new ImageButton(Vars.ui.getIcon("cliff-" + field), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.cliffSides = Util.toggle(MVars.toolOptions.cliffSides, field));
                button.setDisabled(() -> MVars.toolOptions.cliffAuto);
                button.update(() -> button.setChecked(Util.enabled(MVars.toolOptions.cliffSides, field)));
                table.add(button).pad(0).left();
            }
            {
                final int field = 7;
                ImageButton button = new ImageButton(Vars.ui.getIcon("cliff-" + field), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.cliffSides = Util.toggle(MVars.toolOptions.cliffSides, field));
                button.setDisabled(() -> MVars.toolOptions.cliffAuto);
                button.update(() -> button.setChecked(Util.enabled(MVars.toolOptions.cliffSides, field)));
                table.add(button).pad(0).left();
            }
            table.table().growX().fillX();

            table.row();

            {
                final int field = 4;
                ImageButton button = new ImageButton(Vars.ui.getIcon("cliff-" + field), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.cliffSides = Util.toggle(MVars.toolOptions.cliffSides, field));
                button.setDisabled(() -> MVars.toolOptions.cliffAuto);
                button.update(() -> button.setChecked(Util.enabled(MVars.toolOptions.cliffSides, field)));
                table.add(button).pad(0).left();
            }
            {
                ImageButton button = new ImageButton(Vars.ui.getIcon("cliff-auto"), Styles.squareTogglei);
                button.clicked(() -> {
                    MVars.toolOptions.cliffAuto = !MVars.toolOptions.cliffAuto;
                    if (!MVars.toolOptions.cliffAuto) MVars.toolOptions.fakeCliffsMap = null;
                });
                button.update(() -> button.setChecked(MVars.toolOptions.cliffAuto));
                table.add(button).pad(0).left();
            }
            {
                final int field = 0;
                ImageButton button = new ImageButton(Vars.ui.getIcon("cliff-" + field), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.cliffSides = Util.toggle(MVars.toolOptions.cliffSides, field));
                button.setDisabled(() -> MVars.toolOptions.cliffAuto);
                button.update(() -> button.setChecked(Util.enabled(MVars.toolOptions.cliffSides, field)));
                table.add(button).pad(0).left();
            }
            table.table().growX().fillX();

            table.row();

            {
                final int field = 3;
                ImageButton button = new ImageButton(Vars.ui.getIcon("cliff-" + field), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.cliffSides = Util.toggle(MVars.toolOptions.cliffSides, field));
                button.setDisabled(() -> MVars.toolOptions.cliffAuto);
                button.update(() -> button.setChecked(Util.enabled(MVars.toolOptions.cliffSides, field)));
                table.add(button).pad(0).left();
            }
            {
                final int field = 2;
                ImageButton button = new ImageButton(Vars.ui.getIcon("cliff-" + field), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.cliffSides = Util.toggle(MVars.toolOptions.cliffSides, field));
                button.setDisabled(() -> MVars.toolOptions.cliffAuto);
                button.update(() -> button.setChecked(Util.enabled(MVars.toolOptions.cliffSides, field)));
                table.add(button).pad(0).left();
            }
            {
                final int field = 1;
                ImageButton button = new ImageButton(Vars.ui.getIcon("cliff-" + field), Styles.squareTogglei);
                button.clicked(() -> MVars.toolOptions.cliffSides = Util.toggle(MVars.toolOptions.cliffSides, field));
                button.setDisabled(() -> MVars.toolOptions.cliffAuto);
                button.update(() -> button.setChecked(Util.enabled(MVars.toolOptions.cliffSides, field)));
                table.add(button).pad(0).left();
            }
            table.table().growX().fillX();

            blockOptions.add(root).margin(4).left().growX().fillX();
            blockOptions.row();
        }

        if (MVars.toolOptions.current.selectedBlock.editorConfigurable)
            MVars.toolOptions.current.selectedBlock.buildEditorConfig(blockOptions);
    }

    private final KeyCode[] keysSlots = new KeyCode[] {
            KeyCode.num1,
            KeyCode.num2,
            KeyCode.num3,
            KeyCode.num4,
            KeyCode.num5,
            KeyCode.num6,
            KeyCode.num7,
            KeyCode.num8,
            KeyCode.num9,
            KeyCode.num0,
    };
    private void doInput(){
        if (Core.input.ctrl() && Core.input.shift()) {
            if (Core.input.keyTap(KeyCode.z) && view.editorAction == null) editor.redo();
        } else if (Core.input.ctrl()) {
            if (Core.input.keyTap(KeyCode.y) && view.editorAction == null) editor.redo();
            if (Core.input.keyTap(KeyCode.s) && view.editorAction == null) save();
            if (Core.input.keyTap(KeyCode.g)) view.setGrid(!view.isGrid());
            if (Core.input.keyTap(KeyCode.z) && view.editorAction == null) editor.undo();
        } else if (Core.input.shift()) {
            if (Core.input.keyTap(KeyCode.z) && view.editorAction == null) editor.redo();
        } else {
            if (Core.input.keyTap(KeyCode.z) && view.editorAction == null) editor.undo();

            if (Core.input.keyTap(KeyCode.escape)) {
                if (view.editorAction == null) {
                    if (!menu.isShown()) {
                        menu.show();
                    }
                } else {
                    view.editorAction = null;
                    view.redrawPreview = true;
                }
            }

            if (view.editorAction == null) for (EditorTool tool : EditorTool.values()) {
                if (tool.lockedBehind != null && tool.lockedBehind != MVars.rules.gamemodeFactory()) continue;
                if (tool.visibleIf != null && !tool.visibleIf.get()) continue;
                if (tool.key() == KeyCode.unset) continue;
                if (!Core.input.keyTap(tool.key())) continue;
                MVars.toolOptions.tool = tool;
            }

            for (int i = 0; i < keysSlots.length; i++) {
                if (!Core.input.keyTap(keysSlots[i])) continue;
                MVars.toolOptions.current = MVars.toolOptions.available[i];
                rebuildBlockOptions();
            }
        }

        // if(Core.input.keyTap(KeyCode.r)){
        //     editor.rotation = Mathf.mod(editor.rotation + 1, 4);
        // }

        // if(Core.input.keyTap(KeyCode.e)){
        //     editor.rotation = Mathf.mod(editor.rotation - 1, 4);
        // }
    }

    private void buildMenu() {
        menu = new BaseDialog("@menu");
        menu.addCloseButton();

        float swidth = 180f;

        menu.cont.table(t -> {
            final MapGenerateDialog generateDialog = Reflect.get(MapEditorDialog.class, this, "generateDialog");

            t.defaults().size(swidth, 60f).padBottom(5).padRight(5).padLeft(5);

            t.button("@editor.savemap", Icon.save, this::save);

            t.button("@editor.mapinfo", Icon.pencil, () -> {
                infoDialog.show();
                menu.hide();
            });

            t.row();

            t.button("@editor.generate", Icon.terrain, () -> {
                generateDialog.show(generateDialog::applyToEditor);
                menu.hide();
            });

            t.button("@editor.resize", Icon.resize, () -> {
                Reflect.<MapResizeDialog>get(MapEditorDialog.class, this, "resizeDialog").show();
                menu.hide();
            });

            t.row();

            t.button("@editor.import", Icon.download, () -> createDialog("@editor.import",
                    "@editor.importmap", "@editor.importmap.description", Icon.download, (Runnable)loadDialog::show,
                    "@editor.importfile", "@editor.importfile.description", Icon.file, (Runnable)() ->
                            Vars.platform.showFileChooser(true, Vars.mapExtension, file -> Vars.ui.loadAnd(() -> {
                                Vars.maps.tryCatchMapError(() -> {
                                    if(MapIO.isImage(file)){
                                        Vars.ui.showInfo("@editor.errorimage");
                                    }else{
                                        editor.OBeginEdit(MapIO.createMap(file, true));
                                    }
                                });
                            })),

                    "@editor.importimage", "@editor.importimage.description", Icon.fileImage, (Runnable)() ->
                            Vars.platform.showFileChooser(true, "png", file ->
                                    Vars.ui.loadAnd(() -> {
                                        Pixmap pixmap = null;
                                        try{
                                            pixmap = new Pixmap(file);
                                            editor.OBeginEdit(pixmap);
                                        }catch(Exception e){
                                            Vars.ui.showException("@editor.errorload", e);
                                            Log.err(e);
                                        }finally{
                                            if(pixmap != null) pixmap.dispose();
                                        }
                                    })))
            );

            t.button("@editor.export", Icon.upload, () -> createDialog("@editor.export",
                    "@editor.exportfile", "@editor.exportfile.description", Icon.file,
                    (Runnable)() -> Vars.platform.export(editor.tags.get("name", "unknown"), Vars.mapExtension, file -> MapIO.writeMap(file, editor.createMap(file))),
                    "@editor.exportimage", "@editor.exportimage.description", Icon.fileImage,
                    (Runnable)() -> Vars.platform.export(editor.tags.get("name", "unknown"), "png", file -> {
                        Pixmap out = MapIO.writeImage(editor.tiles());
                        file.writePng(out);
                        out.dispose();
                    })));

            t.row();

            t.button("@editor.ingame", Icon.right, this::editInGame);

            t.button("@editor.playtest", Icon.play, this::playtest);
        });

        menu.cont.row();

        if (Vars.steam) {
            menu.cont.button("@editor.publish.workshop", Icon.link, () -> {
                Map builtin = Vars.maps.all().find(m -> m.name().equals(editor.tags.get("name", "").trim()));

                if(editor.tags.containsKey("steamid") && builtin != null && !builtin.custom){
                    Vars.platform.viewListingID(editor.tags.get("steamid"));
                    return;
                }

                Map map = save();

                if(editor.tags.containsKey("steamid") && map != null){
                    Vars.platform.viewListing(map);
                    return;
                }

                if(map == null) return;

                if(map.tags.get("description", "").length() < 4){
                    Vars.ui.showErrorMessage("@editor.nodescription");
                    return;
                }

                if(!Structs.contains(Gamemode.all, g -> g.valid(map))){
                    Vars.ui.showErrorMessage("@map.nospawn");
                    return;
                }

                Vars.platform.publish(map);
            }).padTop(-3).size(swidth * 2f + 10, 60f).update(b ->
                    b.setText(editor.tags.containsKey("steamid") ?
                            editor.tags.get("author", "").equals(Vars.steamPlayerName) ? "@workshop.listing" : "@view.workshop" :
                            "@editor.publish.workshop"));

            menu.cont.row();
        }

        menu.cont.button("@editor.sectorgenerate", Icon.terrain, () -> {
            menu.hide();
            Reflect.<SectorGenerateDialog>get(MapEditorDialog.class, this, "sectorGenDialog").show();
        }).padTop(!Vars.steam ? -3 : 1).size(swidth * 2f + 10, 60f);

        menu.cont.row();

        //this is gated behind a property, because it's (1) not useful to most people, (2) confusing and (3) may crash or otherwise bug out
        if(OS.hasProp("mindustry.editor.simulate.button")){
            menu.cont.button("Simulate", Icon.logic, () -> {
                menu.hide();

                BaseDialog dialog = new BaseDialog("Simulate");

                int[] seconds = {60 * 1};

                dialog.cont.add("Seconds: ");
                dialog.cont.field(seconds[0] + "", text -> seconds[0] = Strings.parseInt(text, 1)).valid(s -> Strings.parseInt(s, 9999999) < 10f * 60f);

                dialog.addCloseButton();

                dialog.buttons.button("@ok", Icon.ok, () -> {
                    Vars.ui.loadAnd(() -> {

                        float deltaScl = 2f;
                        int steps = Mathf.ceil(seconds[0] * 60f / deltaScl);
                        float oldDelta = Time.delta;
                        Time.delta = deltaScl;

                        Seq<Building> builds = new Seq<>();
                        Time.clear();

                        Vars.world.tiles.eachTile(t -> {
                            if(t.build != null && t.isCenter() && t.block().update && t.build.allowUpdate()){
                                builds.add(t.build);
                                t.build.updateProximity();
                            }
                        });

                        for(int i = 0; i < steps; i++){
                            Time.update();
                            for(Building build : builds){
                                build.update();
                            }
                            Groups.powerGraph.update();
                        }

                        //spawned units will cause havoc, so clear them
                        Groups.unit.clear();

                        Time.clear();
                        Time.delta = oldDelta;
                    });

                    dialog.hide();
                }).size(210f, 64f);

                dialog.show();

            }).size(swidth * 2f + 10, 60f);

            menu.cont.row();
        }

        menu.cont.button("@quit", Icon.exit, () -> {
            tryExit();
            menu.hide();
        }).padTop(1).size(swidth * 2f + 10, 60f);
    }

    private void tryExit(){
        Vars.ui.showConfirm("@confirm", "@editor.unsaved", this::hide);
    }

    /**
     * Argument format:
     * 0) button name
     * 1) description
     * 2) icon name
     * 3) listener
     */
    private void createDialog(String title, Object... arguments){
        BaseDialog dialog = new BaseDialog(title);

        float h = 90f;

        dialog.cont.defaults().size(360f, h).padBottom(5).padRight(5).padLeft(5);

        for(int i = 0; i < arguments.length; i += 4){
            String name = (String)arguments[i];
            String description = (String)arguments[i + 1];
            Drawable iconname = (Drawable)arguments[i + 2];
            Runnable listenable = (Runnable)arguments[i + 3];

            TextButton button = dialog.cont.button(name, () -> {
                listenable.run();
                dialog.hide();
                menu.hide();
            }).left().margin(0).get();

            button.clearChildren();
            button.image(iconname).padLeft(10);
            button.table(t -> {
                t.add(name).growX().wrap();
                t.row();
                t.add(description).color(Color.gray).growX().wrap();
            }).growX().pad(10f).padLeft(5);

            button.row();

            dialog.cont.row();
        }

        dialog.addCloseButton();
        dialog.show();
    }
}
