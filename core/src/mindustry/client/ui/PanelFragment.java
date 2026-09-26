package mindustry.client.ui;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;

import arc.input.KeyCode;
import arc.math.Mathf;

import arc.math.geom.Vec2;
import arc.scene.*;
import arc.scene.event.ClickListener;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.event.Touchable;
import arc.scene.style.Drawable;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.ai.ItemUnitStance;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.ai.types.CommandAI;
import mindustry.client.ClientVars;
import mindustry.client.fallen.*;
import mindustry.client.navigation.BuildPath;
import mindustry.client.navigation.MinePath;
import mindustry.client.navigation.Navigation;
import mindustry.client.navigation.RepairPath;
import mindustry.client.fallen.assistai.PolySettingsDialog;
import mindustry.client.fallen.assistai.SelfBuilderAI;
import mindustry.client.utils.AutoTransfer;
import mindustry.client.utils.BuilderAssist;
import mindustry.client.utils.GlobalChat;
import mindustry.content.*;
import mindustry.core.NetClient;
import mindustry.entities.Units;
import mindustry.game.EventType.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.maps.Map;
import mindustry.type.*;
import mindustry.ui.*;
import mindustry.ui.fragments.ChatFragment;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.*;
import mindustry.world.blocks.sandbox.ItemSource;
import mindustry.world.blocks.sandbox.LiquidSource;
import mindustry.world.blocks.sandbox.PowerSource;
import mindustry.world.blocks.sandbox.PowerVoid;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.UnitFactory;
import arc.struct.Seq;


import static arc.Core.*;
import static mindustry.Vars.*;

public class PanelFragment extends Table{
    public Table fdpanel; //Создание интерфейса дял кнопок
    public static Seq<Item> itemtomine = new Seq<>(); //Создание выборки для копания
    /** Resource flags for mining AI (public for MinersFDAI / settings). */
    public static boolean minecopper = false, minelead = false, minetitan = false,
            minesand = false, minecoal = false, minescrap = false;
    private static boolean mineBerylliumwall;

    public static Item[] ALL_MINE_ORES = {};
    public static final ObjectSet<Item> enabledOres = new ObjectSet<>();
    public static final ObjectMap<UnitType, ObjectSet<Item>> unitMineOres = new ObjectMap<>();
    private float brokenFade = 0f;
    public static int max_length = 146;
    private final IntMap<EffState> effStorage = new IntMap<>();


    private boolean eneblemining = false;
    /** When true, keep trying to start mining after join until unit+core are ready. */
    private boolean pendingAutoMine = false;
    private boolean viewunitshealth = false;
    private boolean viewunitseffects = false;
    private boolean viewprogressunit = false;
    private boolean viewprogresbuild  = false;
    private boolean viewEfficiency = false;


    public static int currentfollowmode = 0; // 1 - mine, 2 - build, 3 - heal
    public static int prevfollowmode = 0;
    public static boolean forcesavelogs = false;
    public static boolean rtvWaveKey = false;
    public static boolean rtvKey = false;

    private static final GlyphLayout layout = new GlyphLayout();
    private static final StringBuilder sb = new StringBuilder();
    private static final Color tmpCol = new Color();
    private static final Bits tempBits = new Bits();

    //шизааааааааааааааа
    public static boolean triEnabled = false;
    public static int triUnitCount = 11;
    public static float triSize = 90f;
    public static float triRotSpeed = 0.1f;
    public static int triUnitTypeIndex = 0; // Для слайдера типов

    // Отсортированный список типов юнитов
    public static Seq<UnitType> sortedUnitTypes = new Seq<>();
    public static final Seq<Unit> followers = new Seq<>();
    private static int syncTimer = 0;

    /** Your own unit rebuilds, heals and helps. Right click opens the settings. */
    public static boolean polyAiMode = Core.settings.getBool("polyAiMode", false);
    public static final SelfBuilderAI aiNotPolyAi = new SelfBuilderAI();
    /** Right click on the power button: connect grids once a minute, clean extra links every 5. */
    public static boolean autoFixPower = false;
    private static final Interval fixPowerTimer = new Interval();
    private static int fixPowerRuns;

    public static boolean mineMonos = true;
    public static boolean minePolys = false;
    public static boolean minePulss = true;
    public static boolean mineMegas = true;
    public static boolean mineQuazs = true;
    /** false = AI splits enabled ores by tier; true = table in Trash (e.g. quasar → titanium only). */
    public static boolean manualOreAssign = Core.settings.getBool("fd-manualOreAssign", false);
    public static boolean autoHealMegas = Core.settings.getBool("fd-megaAutoHeal", false);
    public static float autoHealDist = Core.settings.getFloat("fd-megaAutoHealDist", 50f);
    public static int minUnitsPerResource = 1;
    public static int AIMiningUpdateTime = Core.settings.getInt("AIUpTime", 10);
    public static float crisisThreshold = 0.10f;

    public PanelFragment(){ //Основной класс
        // Ensure mining AI listeners are registered even if startInit order changes
        MinersFDAI.init();

        Events.run(Trigger.update, () -> {
            if(!Vars.state.isMenu()) {
//                FDAutoFill.update();
                CustomBuildLogic.update();
                if(autoFixPower && state.isGame() && fixPowerTimer.get(60f * 60f)) fixPowerQuiet();
            }
        });

        Events.run(Trigger.draw, () -> { //Постоянный вызов прорисовки
            if(ui.hudfrag.shown) {
                drawBuildings();
                drawUnits();
                FDAutoShoot.drawTarget();
                FDAutoShoot.drawUnitAim();
                FDAutoShoot.update();
            }
        });

        Events.on(WaveEvent.class, e -> {
            if (net.client() && rtvWaveKey) {
                Timer.schedule(() -> Call.sendChatMessage("/rtv wave"), 10f);
            }
        });

        Events.on(WorldLoadEvent.class, e -> { //Ивент, срабатывающий при загрузке карты
//            FDEnemyWarning.init();
            initTypes();
            // Auto-enable core resources display when joining a game
            if(Core.settings.getBool("autocoreitems", true) && !state.isMenu()){
                Core.settings.put("coreitems", true);
            }
            Timer.schedule(() -> {
                if(net.client() && player.unit() != null && player.unit().id != -1){
                    //Call.sendChatMessage("/vanish 1");
                }
                if (net.client() && rtvKey) {
                    Call.sendChatMessage("/rtv");
                }
            }, 5f);

            rebuild();
            // MinersFDAI resets itself on WorldLoadEvent
            loadMiningPrefs();
            updatemineitems();
            effStorage.clear();

            // Auto-mine on join: arm a pending flag; actual start happens when unit can mine
            // (player.unit() is often null/NullUnit right after WorldLoad, so Timer alone fails)
            pendingAutoMine = Core.settings.getBool("automineonjoin", false);
            if(pendingAutoMine){
                Core.app.post(this::tryAutoMineOnJoin);
            }
        });

        Events.on(MenuReturnEvent.class, e -> pendingAutoMine = false);

        Events.on(UnitControlEvent.class, e -> { //Проверка ресурсов при смене юнита
            updatemineitems();
            tryAutoMineOnJoin();
        });
        Events.on(UnitChangeEvent.class, e -> { //Проверка ресурсов при смене юнита
            updatemineitems();
            tryAutoMineOnJoin();
        });

        Events.run(Trigger.update, () -> { //currentfollowmode // 1 - mine, 2 - build, 3 - heal //переключения в режиме афк
            // Keep retrying auto-mine every frame until unit+core are ready
            if(pendingAutoMine) tryAutoMineOnJoin();
            if(eneblemining && Navigation.currentlyFollowing == null) startmining();

            if (player == null || player.unit() == null) return;
            updateTriControl();
//            FDEnemyWarning.update();
            if(Core.settings.getBool("afkmode")){
                if(Navigation.currentlyFollowing == null){currentfollowmode = 1; startmining();}
                else if(Navigation.currentlyFollowing instanceof BuildPath){
                    if(player.unit().plans.size == 0 && control.input.isBuilding ){ currentfollowmode = 1; if(prevfollowmode == 1){startmining();}else{Navigation.follow(new RepairPath(), true); } }
                } else if(Navigation.currentlyFollowing instanceof MinePath){
                    if(player.unit().plans.size != 0 && control.input.isBuilding ) {prevfollowmode = 1; currentfollowmode = 1; Navigation.follow(new BuildPath("self")); } else return;
                }else if(Navigation.currentlyFollowing instanceof RepairPath){
                    if(player.unit().plans.size != 0 && control.input.isBuilding ) {prevfollowmode = 3; currentfollowmode = 3; Navigation.follow(new BuildPath("self")); } else return;
                }
            }
        });
    }


    /** Automatic power fix: silent when nothing to connect; every fifth run also removes extra links. */
    private static void fixPowerQuiet(){
        fixPowerRuns++;
        ClientVars.clientCommandHandler.handleMessage(fixPowerRuns % 5 == 0 ? "!fixpower c qc" : "!fixpower c q", player);
    }

    public static void startInit() {
        mindustry.client.fallen.ActivityLogger.init();
        mindustry.client.fallen.FdLogicQol.init();
        mindustry.client.fallen.MinersFDAI.init();
        BuilderAssist.init();
        GlobalChat.init();
        AntiAttemPatcher.load();
        Log.info("Start init");
    }

    void rebuild(){         //category does not change on rebuild anymore, only on new world load
        Group group = fdpanel.parent;
        int index = fdpanel.getZIndex();
        fdpanel.remove();
        build(group);
        fdpanel.setZIndex(index);
    }

    private int panelTab = Mathf.clamp(Core.settings.getInt("morj-panel-tab", 0), 0, 4);
    private Table panelPage;
    private static final int panelCols = 4;
    private static final Color panelDim = new Color(1f, 1f, 1f, 0.38f);
    private static Drawable panelOnBg;
    private static Button.ButtonStyle panelBtnStyle;
    public void build(Group parent){
        if(Items.copper != null) loadMiningPrefs();
        parent.fill(full -> {
            fdpanel = full;
            full.touchable = Touchable.childrenOnly;
            full.center().left().visible(() -> ui.hudfrag.shown);
            full.table(Tex.pane, shell -> {
                shell.margin(6f).top();
                shell.defaults().growX();

                shell.table(bars -> {
                    bars.defaults().height(16f).growX().pad(1f);
                    bars.add(new Bar(
                        () -> {
                            Unit u = player == null ? null : player.unit();
                            if(u == null) return "HP";
                            return "HP " + Mathf.round(u.health) + "/" + Mathf.round(u.maxHealth);
                        },
                        () -> Pal.health,
                        () -> player == null || player.unit() == null ? 0f : Mathf.clamp(player.unit().healthf())
                    )).row();
                    bars.add(new Bar(
                        () -> {
                            Unit u = player == null ? null : player.unit();
                            return "Щит " + (u == null ? 0 : Mathf.round(u.shield));
                        },
                        () -> Pal.accent,
                        () -> {
                            Unit u = player == null ? null : player.unit();
                            return u == null ? 0f : Mathf.clamp(u.shield / Math.max(u.maxHealth, 1f));
                        }
                    ));
                }).padBottom(4f).row();

                shell.table(tabs -> {
                    tabs.defaults().height(26f).growX().pad(1f);
                    String[] keys = {"client.morj.tab.mine", "client.morj.tab.build", "client.morj.tab.view", "client.morj.tab.fight", "client.morj.tab.server"};
                    for(int i = 0; i < keys.length; i++){
                        int tab = i;
                        tabs.button(Core.bundle.get(keys[i]), Styles.flatt, () -> showPanelTab(tab))
                            .checked(b -> panelTab == tab)
                            .update(b -> b.getLabel().setFontScale(0.72f));
                    }
                }).padBottom(4f).row();

                panelPage = new Table();
                shell.add(panelPage).growX().left();
                showPanelTab(panelTab);
            }).padTop(Core.settings.getInt("yoffssetfdpamel", -200)).left();
        });
    }

    private void showPanelTab(int tab){
        panelTab = Mathf.clamp(tab, 0, 4);
        settings.put("morj-panel-tab", panelTab);
        if(panelPage == null) return;
        panelPage.clear();
        panelPage.top().left();
        switch(panelTab){
            case 0 -> pageMine(panelPage);
            case 1 -> pageBuild(panelPage);
            case 2 -> pageView(panelPage);
            case 3 -> pageFight(panelPage);
            default -> pageServer(panelPage);
        }
    }

    private void pageMine(Table page){
        Table grid = grid(page);
        int[] col = {0};
        ore(grid, col, Items.copper, "@client.fdpanel.minecopper");
        ore(grid, col, Items.lead, "@client.fdpanel.minelead");
        ore(grid, col, Items.titanium, "@client.fdpanel.minetitan");
        ore(grid, col, Items.sand, "@client.fdpanel.minesand");
        ore(grid, col, Items.coal, "@client.fdpanel.minecoal");
        ore(grid, col, Items.scrap, "@client.fdpanel.minescrap");
        ore(grid, col, Items.beryllium, "@client.fdpanel.mineberyl");
        tile(grid, col, Icon.units, bundle.get("client.morj.btn.polys"), "@client.fdpanel.minepolys",
            () -> minePolys, () -> { minePolys = !minePolys; MinersFDAI.forceReassign(); }, null);
        tile(grid, col, Icon.production, bundle.get("client.morj.btn.automine"), "@client.fdpanel.automine",
            () -> MinersFDAI.autoMiningActive, MinersFDAI::toggle, null);
        tile(grid, col, Icon.terminal, bundle.get("client.morj.btn.mine"), "@client.fdpanel.mine",
            () -> eneblemining, () -> {
                eneblemining = !eneblemining;
                if(eneblemining) startmining();
                else Navigation.stopFollowing();
            }, null);
        tile(grid, col, Icon.download, bundle.get("client.morj.btn.onjoin"), "@client.fdpanel.automineonjoin",
            () -> settings.getBool("automineonjoin", false), () -> {
                boolean on = !settings.getBool("automineonjoin", false);
                settings.put("automineonjoin", on);
                if(on){
                    pendingAutoMine = true;
                    tryAutoMineOnJoin();
                    if(!eneblemining && canStartAutoMine()){
                        eneblemining = true;
                        startmining();
                    }
                }else{
                    pendingAutoMine = false;
                }
            }, null);
        tile(grid, col, Icon.defense, bundle.get("client.morj.btn.safe"), "@client.fdpanel.safemine",
            () -> MinersFDAI.safeMining, () -> MinersFDAI.setSafeMining(!MinersFDAI.safeMining), null);
        tile(grid, col, Icon.pause, bundle.get("client.morj.btn.afk"), "@client.fdpanel.afk",
            () -> settings.getBool("afkmode"), () -> {
                if(!settings.getBool("afkmode")){
                    eneblemining = true;
                    startmining();
                }else{
                    Navigation.stopFollowing();
                }
                settings.put("afkmode", !settings.getBool("afkmode"));
                new Toast(1).add(bundle.get("setting.afkmode.name") + ": " + bundle.get(settings.getBool("afkmode") ? "mod.enabled" : "mod.disabled"));
            }, null);
        tile(grid, col, Icon.add, bundle.get("client.morj.btn.heal"), "@client.fdpanel.heal",
            null, () -> {
                currentfollowmode = 3;
                Navigation.follow(new RepairPath(), true);
            }, null);
        tile(grid, col, Icon.hammer, bundle.get("client.morj.btn.self"), "@client.fdpanel.selfbuild",
            null, () -> {
                currentfollowmode = 2;
                Navigation.follow(new BuildPath("self"));
            }, null);
    }

    private void pageBuild(Table page){
        Table grid = grid(page);
        int[] col = {0};
        assist(grid, col, UnitTypes.poly, bundle.get("client.morj.btn.poly"), "@client.fdpanel.assistpoly",
            () -> MinersFDAI.assistBuildPoly, MinersFDAI::setAssistBuildPoly);
        assist(grid, col, UnitTypes.pulsar, bundle.get("client.morj.btn.pulsar"), "@client.fdpanel.assistpulsar",
            () -> MinersFDAI.assistBuildPulsar, MinersFDAI::setAssistBuildPulsar);
        assist(grid, col, UnitTypes.mega, bundle.get("client.morj.btn.mega"), "@client.fdpanel.assistmega",
            () -> MinersFDAI.assistBuildMega, MinersFDAI::setAssistBuildMega);
        assist(grid, col, UnitTypes.quasar, bundle.get("client.morj.btn.quasar"), "@client.fdpanel.assistquasar",
            () -> MinersFDAI.assistBuildQuasar, MinersFDAI::setAssistBuildQuasar);
        tile(grid, col, Icon.hammer, bundle.get("client.morj.btn.assist"), "@client.fdpanel.assistbuild",
            () -> MinersFDAI.autoAssistBuild, () -> MinersFDAI.setAutoAssistBuild(!MinersFDAI.autoAssistBuild), null);
        tile(grid, col, Icon.diagonal, bundle.get("client.morj.btn.path"), "@client.fdpanel.conveyorpathfind",
            () -> settings.getBool("conveyorpathfinding", true), () -> settings.put("conveyorpathfinding", !settings.getBool("conveyorpathfinding", true)), null);
        tile(grid, col, Icon.link, bundle.get("client.morj.btn.plast"), "@client.fdpanel.plastaniumpathfind",
            () -> settings.getBool("plastaniumcrossbridges", true), () -> settings.put("plastaniumcrossbridges", !settings.getBool("plastaniumcrossbridges", true)), null);
        tile(grid, col, new TextureRegionDrawable(UnitTypes.poly.uiIcon), bundle.get("client.morj.btn.polyai"), "@client.fdpanel.polyai",
            () -> polyAiMode, () -> {
                polyAiMode = !polyAiMode;
                settings.put("polyAiMode", polyAiMode);
                if(!polyAiMode){
                    aiNotPolyAi.stopAfk();
                    aiNotPolyAi.clearAiPlans();
                }
            }, () -> PolySettingsDialog.instance.show());
        tile(grid, col, new TextureRegionDrawable(UnitTypes.nova.uiIcon), bundle.get("client.morj.btn.nova"), "@client.fdpanel.novaassist",
            () -> BuilderAssist.enabled, BuilderAssist::toggle, BuilderAssist::showSettings);
        tile(grid, col, Icon.planet, bundle.get("client.morj.btn.chat"), "@client.fdpanel.glchat",
            null, GlobalChatDialog::showDialog, null);
        tile(grid, col, Icon.cancel, bundle.get("client.morj.btn.schem"), "@client.fdpanel.schemcleanup",
            () -> settings.getBool("placeSchematicWithCleanup"), () -> settings.put("placeSchematicWithCleanup", !settings.getBool("placeSchematicWithCleanup")), null);
    }

    private void pageView(Table page){
        Table grid = grid(page);
        int[] col = {0};
        tile(grid, col, Icon.eyeOff, bundle.get("client.morj.btn.fade"), "@client.fdpanel.smarttransparency",
            () -> settings.getBool("smarttransparency", false), () -> settings.put("smarttransparency", !settings.getBool("smarttransparency", false)), null);
        tile(grid, col, Icon.eyeOff, bundle.get("client.morj.btn.light"), "@client.fdpanel.light",
            () -> enableLight, () -> enableLight = !enableLight, null);
        tile(grid, col, Icon.add, bundle.get("client.morj.btn.hp"), "@client.fdpanel.unitshealth",
            () -> viewunitshealth, () -> viewunitshealth = !viewunitshealth, null);
        tile(grid, col, Icon.units, bundle.get("client.morj.btn.uprogress"), "@client.fdpanel.unitprogress",
            () -> viewprogressunit, () -> viewprogressunit = !viewprogressunit, null);
        tile(grid, col, Icon.crafting, bundle.get("client.morj.btn.bprogress"), "@client.fdpanel.buildprogress",
            () -> viewprogresbuild, () -> viewprogresbuild = !viewprogresbuild, null);
        tile(grid, col, Icon.chartBar, bundle.get("client.morj.btn.eff"), "@client.fdpanel.efficiency",
            () -> viewEfficiency, () -> viewEfficiency = !viewEfficiency, null);
        tile(grid, col, Icon.effect, bundle.get("client.morj.btn.status"), "@client.fdpanel.uniteffects",
            () -> viewunitseffects, () -> viewunitseffects = !viewunitseffects, null);
        tile(grid, col, Icon.box, bundle.get("client.morj.btn.core"), "@client.fdpanel.coreitems",
            () -> settings.getBool("coreitems"), () -> settings.put("coreitems", !settings.getBool("coreitems")), null);
        tile(grid, col, Icon.units, bundle.get("client.morj.btn.scanunits"), "@client.fdpanel.eye.units", null, this::checkunits, null);
        tile(grid, col, Icon.commandRally, bundle.get("client.morj.btn.scancores"), "@client.fdpanel.eye.cores", null, this::checkcores, null);
        tile(grid, col, Icon.modeAttack, bundle.get("client.morj.btn.scanspawn"), "@client.fdpanel.eye.spawns", null, this::checkspawns, null);
        tile(grid, col, Icon.cancel, bundle.get("client.morj.btn.scanvoid"), "@client.fdpanel.eye.voids", null, this::checkvoids, null);
        tile(grid, col, Icon.upload, bundle.get("client.morj.btn.scansource"), "@client.fdpanel.eye.sources", null, this::checksources, null);
        tile(grid, col, Icon.logic, bundle.get("client.morj.btn.scanproc"), "@client.fdpanel.eye.worldproc", null, this::checkworldprocc, null);
        tile(grid, col, Icon.waves, bundle.get("client.morj.btn.wave"), "@client.fdpanel.eye.wave", null, this::checkNextWave, null);
        tile(grid, col, Icon.chat, bundle.get("client.morj.btn.uchat"), "@client.fdpanel.unitatchat",
            () -> settings.getBool("unitatchat"), () -> settings.put("unitatchat", !settings.getBool("unitatchat")), null);
    }

    private void pageFight(Table page){
        Table grid = grid(page);
        int[] col = {0};
        tile(grid, col, Icon.star, bundle.get("client.morj.btn.aim"), "@client.fdpanel.smarttargeting",
            () -> settings.getBool("smarttargeting"), () -> settings.put("smarttargeting", !settings.getBool("smarttargeting")), null);
        tile(grid, col, Icon.cancel, bundle.get("client.morj.btn.ignunit"), "@client.fdpanel.ignoreunit",
            () -> settings.getBool("ignoreunit"), () -> settings.put("ignoreunit", !settings.getBool("ignoreunit")), null);
        tile(grid, col, Icon.cancel, bundle.get("client.morj.btn.ignheal"), "@client.fdpanel.ignoreheal",
            () -> settings.getBool("ignoreheal"), () -> settings.put("ignoreheal", !settings.getBool("ignoreheal")), null);
        tile(grid, col, Icon.line, bundle.get("client.morj.btn.uaim"), "@client.fdpanel.unitaim",
            () -> FDAutoShoot.viewUnitAim, () -> FDAutoShoot.viewUnitAim = !FDAutoShoot.viewUnitAim, null);
        tile(grid, col, new TextureRegionDrawable(UnitTypes.mega.uiIcon), bundle.get("client.morj.btn.ucmega"), "@client.fdpanel.mega",
            null, () -> ClientVars.clientCommandHandler.handleMessage("!uc " + UnitTypes.mega.localizedName, player), null);
    }

    private void pageServer(Table page){
        Table grid = grid(page);
        int[] col = {0};
        tile(grid, col, Icon.refresh, bundle.get("client.morj.btn.sync"), "@client.fdpanel.sync",
            null, () -> Call.sendChatMessage("/sync"), null);
        tile(grid, col, Icon.ok, bundle.get("client.morj.btn.vote"), "@client.fdpanel.vote",
            null, () -> Call.sendChatMessage("/vote y"), null);
        tile(grid, col, Icon.map, bundle.get("client.morj.btn.rtv"), "@client.fdpanel.rtv",
            () -> rtvKey, () -> Call.sendChatMessage("/rtv"), () -> rtvKey = !rtvKey);
        tile(grid, col, Icon.waves, bundle.get("client.morj.btn.rtvwave"), "@client.fdpanel.rtvwave",
            () -> rtvWaveKey, () -> Call.sendChatMessage("/rtv wave"), () -> rtvWaveKey = !rtvWaveKey);
        tile(grid, col, Icon.book, bundle.get("client.morj.btn.history"), "@client.fdpanel.history",
            null, () -> Call.sendChatMessage("/history"), null);
        tile(grid, col, Icon.rotate, bundle.get("client.morj.btn.elite"), "@client.fdpanel.elite",
            null, () -> Call.sendChatMessage("/elite"), null);
        tile(grid, col, Icon.crafting, bundle.get("client.morj.btn.transfer"), "@client.fdpanel.autotransfer",
            () -> settings.getBool("autotransfer"), () -> {
                AutoTransfer.enabled ^= true;
                new Toast(1).add(bundle.get("client.autotransfer") + ": " + bundle.get(AutoTransfer.enabled ? "mod.enabled" : "mod.disabled"));
                settings.put("autotransfer", !settings.getBool("autotransfer"));
            }, null);
        tile(grid, col, Icon.power, bundle.get("client.morj.btn.power"), "@client.fdpanel.fixpower",
            () -> autoFixPower, () -> ClientVars.clientCommandHandler.handleMessage("!fixpower c", player), () -> {
                autoFixPower = !autoFixPower;
                fixPowerTimer.reset(0, 0f);
                fixPowerRuns = 0;
            });
        tile(grid, col, Icon.logic, bundle.get("client.morj.btn.fixcode"), "@client.fdpanel.fixcode",
            null, () -> ClientVars.clientCommandHandler.handleMessage("!fixcode r", player), null);
    }

    private static Table grid(Table page){
        Table grid = new Table();
        grid.left().top();
        page.add(grid).left().growX();
        return grid;
    }

    private void ore(Table grid, int[] col, Item item, String tip){
        tile(grid, col, new TextureRegionDrawable(item.uiIcon), item.localizedName, tip,
            () -> isOreEnabled(item), () -> setOreEnabled(item, !isOreEnabled(item)), null);
    }

    private void assist(Table grid, int[] col, UnitType type, String label, String tip, Boolp enabled, Boolc set){
        tile(grid, col, new TextureRegionDrawable(type.uiIcon), label, tip, enabled, () -> {
            boolean next = !enabled.get();
            set.get(next);
            if(next && !MinersFDAI.autoAssistBuild) MinersFDAI.setAutoAssistBuild(true);
        }, null);
    }

    /** Icon with a short caption. Left click runs the action, right click the optional extra. A null state means a one-shot button. */
    private void tile(Table grid, int[] col, Drawable drawable, String label, String tip, Boolp state, Runnable left, Runnable right){
        if(col[0] == panelCols){
            grid.row();
            col[0] = 0;
        }
        if(panelBtnStyle == null){
            panelOnBg = ((TextureRegionDrawable)Tex.whiteui).tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.32f);
            panelBtnStyle = new Button.ButtonStyle();
            panelBtnStyle.up = Styles.none;
            panelBtnStyle.over = Styles.flatOver;
            panelBtnStyle.down = Styles.flatOver;
            panelBtnStyle.checked = panelOnBg;
        }
        float iconSize = Math.max(18f, settings.getInt("buttonsizefdpamel", 30) * 0.7f);
        Button button = new Button(panelBtnStyle);
        button.top().margin(1f, 2f, 2f, 2f);
        Image image = new Image(drawable);
        image.setScaling(Scaling.fit);
        button.add(image).size(iconSize).padTop(1f).row();
        Label caption = button.add(label).growX().padTop(1f).get();
        caption.setFontScale(0.62f);
        caption.setEllipsis(true);
        caption.setAlignment(arc.util.Align.center);
        String baseTip = tip != null && tip.startsWith("@") ? bundle.get(tip.substring(1)) : tip;
        final String shown = right == null ? baseTip : baseTip + "\n[lightgray]" + bundle.get("client.morj.right");
        button.addListener(new Tooltip(t -> {
            t.background(Styles.black6).margin(4f);
            t.add(shown).wrap().width(280f);
        }));
        button.update(() -> {
            boolean on = state != null && state.get();
            button.setChecked(on);
            image.setColor(state == null || on ? Color.white : panelDim);
        });
        button.addListener(new InputListener(){
            @Override public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode key){
                if(key == KeyCode.mouseRight && right != null){
                    right.run();
                    return true;
                }
                if(key == KeyCode.mouseLeft && left != null){
                    left.run();
                    return true;
                }
                return false;
            }
        });
        grid.add(button).size(iconSize + 30f, iconSize + 24f).pad(1f);
        col[0]++;
    }

    /** Delegates to {@link MinersFDAI} (kept for any external call sites). */
    private void autoAssignMiningUnitsEqually() {
        MinersFDAI.autoAssignMiningUnitsEqually();
    }


    public void updateTriControl() {
        if (!triEnabled || !Vars.state.isGame() || Vars.player.unit() == null) return;
        if(triUnitTypeIndex < 0 || triUnitTypeIndex >= sortedUnitTypes.size) return;

        UnitType currentType = sortedUnitTypes.get(triUnitTypeIndex);

        // 1. Очистка и поиск юнитов
        followers.removeAll(u -> !u.isValid() || u.team != Vars.player.team() || u.type != currentType);

        if (followers.size < triUnitCount) {
            Groups.unit.each(u -> {
                if (followers.size < triUnitCount && u.type == currentType && u.team == Vars.player.team() && !followers.contains(u)) {
                    followers.add(u);
                }
            });
        }
        if (followers.size > triUnitCount) followers.truncate(triUnitCount);

        // 2. Команды (раз в 5 тиков для оптимизации сетевого трафика)
        syncTimer++;
        if (syncTimer % 30 != 0) return;

        float baseRotation = Time.time * triRotSpeed;

        for (int i = 0; i < followers.size; i++) {
            Unit unit = followers.get(i);

            // --- ЛОГИКА АТАКИ ---
            // Ищем ближайшего врага в радиусе обзора юнита (или фиксированном, напр. 250 пикселей)
            float range = unit.range();
            Unit target = Units.closestEnemy(unit.team, unit.x, unit.y, range, u ->
                    // Передаем два аргумента: targetAir и targetGround
                    u.checkTarget(unit.type.targetAir, unit.type.targetGround)
            );

            if (target != null) {
                // Атака цели
                Call.commandUnits(Vars.player, new int[]{unit.id}, null, target, new Vec2(target.x, target.y), false, true);
                continue;
            }

            // --- ЛОГИКА ТРЕУГОЛЬНИКА (если врагов нет) ---
            float progress = (float) i / Math.max(1, followers.size);
            float sideProgress = (progress * 3f) % 1f;
            int side = Mathf.floor(progress * 3f);

            float a1 = baseRotation + (side * 120f);
            float a2 = baseRotation + ((side + 1) * 120f);

            float x1 = Vars.player.x + Mathf.cosDeg(a1) * triSize;
            float y1 = Vars.player.y + Mathf.sinDeg(a1) * triSize;
            float x2 = Vars.player.x + Mathf.cosDeg(a2) * triSize;
            float y2 = Vars.player.y + Mathf.sinDeg(a2) * triSize;

            float tx = Mathf.lerp(x1, x2, sideProgress);
            float ty = Mathf.lerp(y1, y2, sideProgress);

            Call.commandUnits(Vars.player, new int[]{unit.id}, null, null, new Vec2(tx, ty), false, true);
        }
    }


    //Копание всех всем
    private void autoAssignMiningUnits() {
        if (player.unit() == null) return;

        // Списки ID юнитов по типам
        IntSeq t1Ids = new IntSeq();
        IntSeq t2Ids = new IntSeq();
        IntSeq t3Ids = new IntSeq();

        // 1. Собираем всех юнитов в группы по типам
        for (Unit u : Groups.unit) {
            if (u.team != player.team() || !u.isCommandable()) continue;

            if (u.type == UnitTypes.mono) t1Ids.add(u.id);
            else if (u.type == UnitTypes.poly || u.type == UnitTypes.pulsar) t2Ids.add(u.id);
            else if (u.type == UnitTypes.mega || u.type == UnitTypes.quasar) t3Ids.add(u.id);
        }

        // 2. Определяем наборы ресурсов для активации
        Item[] t1Targets = {Items.copper, Items.lead, Items.sand};
        Item[] t2Targets = {Items.copper, Items.lead, Items.sand, Items.coal};
        Item[] t3Targets = {Items.copper, Items.lead, Items.sand, Items.coal, Items.titanium};

        // 3. Отдаем приказы
        assignGroup(t1Ids, t1Targets);
        assignGroup(t2Ids, t2Targets);
        assignGroup(t3Ids, t3Targets);
    }

    private void assignGroup(IntSeq ids, Item[] items) {
        if (ids.isEmpty()) return;
        int[] rawIds = ids.toArray();

        // А. Переводим в режим добычи
        Call.setUnitCommand(player, rawIds, UnitCommand.mineCommand);

        // Б. Выключаем режим "Авто" , так как она мешает выбору конкретных ресурсов
        Call.setUnitStance(player, rawIds, UnitStance.mineAuto, false);

        // В. Включаем КАЖДЫЙ нужный ресурс по очереди
        for (Item item : items) {
            UnitStance stance = ItemUnitStance.getByItem(item);
            if (stance != null) {
                Call.setUnitStance(player, rawIds, stance, true);
            }
        }
    }

    public static void minMinUnitsSet(int min){
        minUnitsPerResource = min;
    }
    private void checkspawns() {
        if(!state.hasSpawns()) return;

        StringBuilder sb = new StringBuilder();
        sb.append(": ");
        int num = 0;

        for(Tile tile: spawner.getSpawns()){
            sb.append("(").append(Mathf.ceil(tile.x)).append(",").append(Mathf.ceil(tile.y)).append(");");
            num++;
        }

        if(sb.length() > 2){
            String uspawns = "Spawns(" + num + ")" + sb.toString();

            if ((max_length != 0) && (uspawns.length() >= max_length)) {
                uspawns = uspawns.substring(0, max_length);
            }

            if(settings.getBool("unitatchat")){
                Call.sendChatMessage(uspawns);
            } else {
                ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(uspawns, null, null, "", uspawns);
                NetClient.findCoords(msg);
            }
        }
    }

    /**
     * Next wave enemy composition (side panel).
     * Same routing as Eye of Sauron: public chat only when "Unit in chat" ({@code unitatchat}) is on.
     */
    private void checkNextWave(){
        if(state.isMenu() || state.rules == null || state.rules.spawns == null) return;

        int displayWave = Math.max(state.wave, 1);
        int internalWave = displayWave - 1;
        int spawnCount = Math.max(spawner.getSpawns() != null ? spawner.getSpawns().size : 0, 1);
        boolean toChat = settings.getBool("unitatchat");

        // Aggregate unit type (+ optional status) → count for this wave
        ObjectMap<String, Integer> counts = new ObjectMap<>();
        ObjectMap<String, UnitType> types = new ObjectMap<>();
        ObjectMap<String, StatusEffect> effects = new ObjectMap<>();
        int totalUnits = 0;
        float totalHp = 0f, totalShield = 0f;

        for(SpawnGroup group : state.rules.spawns){
            if(group == null || group.type == null) continue;
            int amt = group.getSpawned(internalWave);
            if(amt <= 0) continue;

            int finalAmt = amt * (group.spawn == -1 ? spawnCount : 1);
            StatusEffect eff = (group.effect == null || group.effect == StatusEffects.none) ? null : group.effect;
            String key = group.type.name + (eff != null ? ":" + eff.name : "");

            counts.put(key, counts.get(key, 0) + finalAmt);
            types.put(key, group.type);
            if(eff != null) effects.put(key, eff);

            totalUnits += finalAmt;
            totalHp += group.type.health * finalAmt;
            totalShield += group.getShield(internalWave) * finalAmt;
        }

        if(counts.isEmpty()){
            postWaveInfo("W" + displayWave + ": —", toChat);
            return;
        }

        // Sort keys by count desc for readable summary
        Seq<String> keys = counts.keys().toSeq();
        keys.sort((a, b) -> Integer.compare(counts.get(b), counts.get(a)));

        StringBuilder body = new StringBuilder();
        for(String key : keys){
            UnitType type = types.get(key);
            StatusEffect eff = effects.get(key);
            int n = counts.get(key);
            body.append(Fonts.getUnicodeStr(type.name)).append("x").append(n);
            if(eff != null){
                body.append(Fonts.getUnicodeStr(eff.name));
            }
            body.append(" ");
        }

        String hpStr = totalHp >= 1000 ? Strings.fixed(totalHp / 1000f, 1) + "k" : String.valueOf(Math.round(totalHp));
        String shStr = totalShield >= 1000 ? Strings.fixed(totalShield / 1000f, 1) + "k" : String.valueOf(Math.round(totalShield));

        String full = "W" + displayWave + " (" + totalUnits + ") HP:" + hpStr
            + (totalShield > 0 ? " Sh:" + shStr : "")
            + ": " + body.toString().trim();

        // Split into chat-sized chunks when posting publicly
        if(toChat && max_length > 0 && full.length() > max_length){
            String rest = body.toString().trim();
            int pos = 0;
            int part = 0;
            String prefix = "W" + displayWave + ": ";
            while(pos < rest.length()){
                int end = Math.min(pos + Math.max(20, max_length - prefix.length() - 4), rest.length());
                if(end < rest.length()){
                    int sp = rest.lastIndexOf(' ', end);
                    if(sp > pos) end = sp;
                }
                String chunk = (part == 0 ? prefix : "W" + displayWave + "+ ") + rest.substring(pos, end).trim();
                postWaveInfo(chunk, true);
                pos = end;
                while(pos < rest.length() && rest.charAt(pos) == ' ') pos++;
                part++;
            }
        }else{
            postWaveInfo(full, toChat);
        }
    }

    private void postWaveInfo(String message, boolean toPublicChat){
        if(message == null || message.isEmpty()) return;
        if(toPublicChat){
            String msg = message;
            if(max_length > 0 && msg.length() > max_length) msg = msg.substring(0, max_length);
            if(state.rules.pvp) Call.sendChatMessage("/t " + msg);
            else Call.sendChatMessage(msg);
        }else{
            String local = message.length() > 1000 ? message.substring(0, 1000) + "..." : message;
            ui.chatfrag.addMessage(local, null, null, "", local);
        }
    }

    public static void startmining() {
        if(player == null || player.team() == null || player.team().data().core() == null) return;
        if(itemtomine.isEmpty()) loadMiningPrefs();
        // Copy items — MinePath keeps the Seq reference; clear() on join would empty an active path
        Seq<Item> ores = itemtomine.copy();
        if(ores.isEmpty() && player.unit() != null && player.unit().type != null){
            ores.addAll(player.unit().type.mineItems);
        }
        currentfollowmode = 1;
        int cap = player.team().data().core().storageCapacity;
        Navigation.follow(new MinePath(ores, cap), true);
    }

    /** Whether player currently controls a living unit that can mine and has a core. */
    private boolean canStartAutoMine(){
        if(state.isMenu() || player == null || player.dead()) return false;
        var u = player.unit();
        if(u == null || !u.isValid() || u.dead() || u.type == null || u.type.mineTier < 0) return false;
        return player.team() != null && player.team().data().core() != null;
    }

    /**
     * Starts mining when {@link #pendingAutoMine} is set and the player is ready.
     * Retried every frame / unit change because WorldLoad is too early for a real unit.
     */
    private void tryAutoMineOnJoin(){
        if(!pendingAutoMine) return;
        if(!Core.settings.getBool("automineonjoin", false)){
            pendingAutoMine = false;
            return;
        }
        if(state.isMenu()){
            pendingAutoMine = false;
            return;
        }
        if(!canStartAutoMine()) return;

        updatemineitems();
        if(itemtomine.isEmpty()){
            setOreEnabled(Items.copper, true);
            setOreEnabled(Items.lead, true);
            setOreEnabled(Items.titanium, true);
        }

        eneblemining = true;
        startmining();

        if(Navigation.currentlyFollowing instanceof MinePath){
            pendingAutoMine = false;
        }
    }

    private void checkvoids() {
        Threads.daemon(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append(":");

            for(Tile tile : world.tiles) {
                if (tile.block() instanceof PowerVoid) { sb.append("(").append(tile.x).append(",").append(tile.y).append(");"); }
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;

                if(sb.length() > 1) {
                    String ucont = sb.toString();
                    String prefix = "[#fa]Power Voids:[white] ";
                    String fullMessage = prefix + ucont;

                    if ((max_length != 0) && (fullMessage.length() >= max_length)) {
                        fullMessage = fullMessage.substring(0, max_length);
                    }

                    if(Core.settings.getBool("unitatchat")){
                        if(state.rules.pvp) {
                            Call.sendChatMessage("/t " + fullMessage);
                        } else {
                            Call.sendChatMessage(fullMessage);
                        }
                    } else {
                        if (fullMessage.length() > 1000) fullMessage = fullMessage.substring(0, 1000) + "... [gray](truncated)";
                        ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(fullMessage, null, null, "", fullMessage);
                        NetClient.findCoords(msg);
                    }
                }
            });
        });
    }

    private void checksources() {
        Threads.daemon(() -> {
            if (world.tiles == null) return;
            ObjectMap<Team, StringBuilder> teamBuilders = new ObjectMap<>();
            for (Team t : Team.all) teamBuilders.put(t, new StringBuilder().append(":"));

            for (Tile tile : world.tiles) {
                if (tile.build != null && tile.build.tile == tile) {
                    Block block = tile.build.block;
                    if (block instanceof ItemSource || block instanceof PowerSource || block instanceof LiquidSource) {
                        Team team = tile.build.team;
                        StringBuilder sb = teamBuilders.get(team);
                        if (sb != null) sb.append(Fonts.getUnicodeStr(block.name)).append("(").append(tile.x).append(",").append(tile.y).append(");");
                    }
                }
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;
                for (ObjectMap.Entry<Team, StringBuilder> entry : teamBuilders.entries()) {
                    Team cteam = entry.key;
                    StringBuilder sb = entry.value;

                    if (sb.length() > 1) {
                        String content = sb.toString();
                        boolean atChat = Core.settings.getBool("unitatchat");
                        String fullMessage = "[#" + cteam.color + "]" + cteam.name + (atChat ? "[white]" : "[]") + content;

                        if (atChat) {
                            if (max_length != 0 && fullMessage.length() >= max_length) fullMessage = fullMessage.substring(0, max_length);
                            if (state.rules.pvp) Call.sendChatMessage("/t " + fullMessage); else Call.sendChatMessage(fullMessage);
                        } else {
                            if (fullMessage.length() > 1000) fullMessage = fullMessage.substring(0, 1000) + "... [gray](truncated)";
                            ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(fullMessage, null, null, "", fullMessage);
                            NetClient.findCoords(msg);
                        }
                    }
                }
            });
        });
    }

    private void checkworldprocc() {
        Threads.daemon(() -> {
            ObjectMap<Team, StringBuilder> teamBuilders = new ObjectMap<>();
            for (Team t : Team.all) teamBuilders.put(t, new StringBuilder().append(":"));

            for(Tile tile : world.tiles) {
                if(tile.build != null && tile.build.tile == tile && tile.build.block == Blocks.worldProcessor) {
                    Team team = tile.build.team;
                    StringBuilder sb = teamBuilders.get(team);
                    if (sb != null) sb.append(Fonts.getUnicodeStr(Blocks.worldProcessor.name)).append("(").append(tile.x).append(", ").append(tile.y).append("); ");
                }
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;
                for(ObjectMap.Entry<Team, StringBuilder> entry : teamBuilders.entries()) {
                    StringBuilder sb = entry.value;
                    if(sb.length() > 1) {
                        Team cteam = entry.key;
                        boolean atChat = Core.settings.getBool("unitatchat");
                        String fullMessage = "[#" + cteam.color + "]" + cteam.name + (atChat ? "[white]" : "[]") + sb.toString();

                        if (atChat) {
                            if (max_length != 0 && fullMessage.length() >= max_length) fullMessage = fullMessage.substring(0, max_length);
                            if (state.rules.pvp) Call.sendChatMessage("/t " + fullMessage); else Call.sendChatMessage(fullMessage);
                        } else {
                            if (fullMessage.length() > 1000) fullMessage = fullMessage.substring(0, 1000) + "... [gray](truncated)";
                            ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(fullMessage, null, null, "", fullMessage);
                            NetClient.findCoords(msg);
                        }
                    }
                }
            });
        });
    }

    private void checkcores() {
        Threads.daemon(() -> {
            ObjectMap<Team, StringBuilder> teamBuilders = new ObjectMap<>();
            ObjectMap<Team, Integer> teamCounters = new ObjectMap<>();

            for (Team t : Team.all) {
                teamBuilders.put(t, new StringBuilder().append(":"));
                teamCounters.put(t, 0);
            }

            for(Tile tile : world.tiles) {
                if(tile.build instanceof CoreBlock.CoreBuild && tile.build.tile == tile) {
                    Team team = tile.build.team;
                    StringBuilder sb = teamBuilders.get(team);

                    if (sb != null) {
                        sb.append(Fonts.getUnicodeStr(tile.build.block.name))
                                .append("(")
                                .append(tile.x)
                                .append(", ")
                                .append(tile.y)
                                .append("); ");

                        teamCounters.put(team, teamCounters.get(team) + 1);
                    }
                }
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;

                for(ObjectMap.Entry<Team, StringBuilder> entry : teamBuilders.entries()) {
                    StringBuilder sb = entry.value;
                    if(sb.length() > 1) {
                        Team cteam = entry.key;
                        int count = teamCounters.get(cteam);
                        String content = sb.toString();
                        String fullMessage;

                        if(Core.settings.getBool("unitatchat")){
                            fullMessage = "[#" + cteam.color + "]" + cteam.name + "(" + count + ")[white]" + content;

                            if ((max_length != 0) && (fullMessage.length() >= max_length)) {
                                fullMessage = fullMessage.substring(0, max_length);
                            }

                            if(state.rules.pvp) Call.sendChatMessage("/t " + fullMessage);
                            else Call.sendChatMessage(fullMessage);
                        } else {
                            fullMessage = "[#" + cteam.color + "]" + cteam.name + "(" + count + ")[]" + content;
                            if (fullMessage.length() > 1000) fullMessage = fullMessage.substring(0, 1000) + "... [gray](truncated)";
                            ChatFragment.ChatMessage msg = ui.chatfrag.addMessage(fullMessage, null, null, "", fullMessage);
                            NetClient.findCoords(msg);
                        }
                    }
                }
            });
        });
    }

    private void checkunits() {
        Threads.daemon(() -> {
            ObjectMap<Team, ObjectIntMap<UnitType>> teamUnitMap = new ObjectMap<>();

            for (Unit unit : Groups.unit) {
                if (unit == null || unit.type == null) continue;

                Team team = unit.team;
                if (!teamUnitMap.containsKey(team)) {
                    teamUnitMap.put(team, new ObjectIntMap<>());
                }

                ObjectIntMap<UnitType> counts = teamUnitMap.get(team);
                counts.put(unit.type, counts.get(unit.type, 0) + 1);
            }

            Seq<String> messagesToSend = new Seq<>();
            StringBuilder currentBatch = new StringBuilder();

            for (Team team : Team.all) {
                if (!teamUnitMap.containsKey(team)) continue;

                ObjectIntMap<UnitType> counts = teamUnitMap.get(team);
                if (counts.size == 0) continue;

                StringBuilder teamSb = new StringBuilder();
                String color = Core.settings.getBool("unitatchat") ? "[#" + team.color + "]" : "[#" + team.color + "]";
                String reset = Core.settings.getBool("unitatchat") ? "[white]" : "[]";

                teamSb.append(color).append(team.name).append(reset).append(":");

                for (ObjectIntMap.Entry<UnitType> entry : counts.entries()) {
                    teamSb.append(Fonts.getUnicodeStr(entry.key.name))
                            .append(entry.value)
                            .append(";");
                }

                String teamString = teamSb.toString();

                if (currentBatch.length() + teamString.length() + 3 < max_length) {
                    if (currentBatch.length() > 0) currentBatch.append(" | ");
                    currentBatch.append(teamString);
                } else {
                    if (currentBatch.length() > 0) {
                        messagesToSend.add(currentBatch.toString());
                        currentBatch.setLength(0);
                    }

                    if (teamString.length() >= max_length) {
                        messagesToSend.add(teamString.substring(0, Math.min(teamString.length(), max_length)));
                    } else {
                        currentBatch.append(teamString);
                    }
                }
            }

            if (currentBatch.length() > 0) {
                messagesToSend.add(currentBatch.toString());
            }

            Core.app.post(() -> {
                if (player == null || messagesToSend.isEmpty()) return;

                for (int i = 0; i < messagesToSend.size; i++) {
                    String msg = messagesToSend.get(i);

                    Timer.schedule(() -> {
                        if (player == null) return;

                        if (Core.settings.getBool("unitatchat")) {
                            if (state.rules.pvp) Call.sendChatMessage("/t " + msg);
                            else Call.sendChatMessage(msg);
                        } else {
                            ChatFragment.ChatMessage m = ui.chatfrag.addMessage(msg, null, null, "", msg);
                            NetClient.findCoords(m);
                        }
                    }, i * 1.1f);
                }
            });
        });
    }
    private void oldcheckunits() {
        Threads.daemon(() -> {
            Seq<String> messagesToSend = new Seq<>();
            StringBuilder currentBatch = new StringBuilder();

            for(Team team : Team.all) {
                if (team.data().unitCount == 0) continue;

                StringBuilder teamSb = new StringBuilder();
                boolean hasUnits = false;

                if(Core.settings.getBool("unitatchat")){
                    teamSb.append("[#").append(team.color).append("]").append(team.name).append("[white]:");
                } else {
                    teamSb.append("[#").append(team.color).append("]").append(team.name).append("[]:");
                }

                for(UnitType type : content.units()) {
                    int count = team.data().countType(type);
                    if(count > 0) {
                        teamSb.append(Fonts.getUnicodeStr(type.name)).append(count).append(";");
                        hasUnits = true;
                    }
                }

                if(!hasUnits) continue;

                String teamString = teamSb.toString();

                if (currentBatch.length() + teamString.length() < max_length) {
                    if (currentBatch.length() > 0) currentBatch.append(" | ");
                    currentBatch.append(teamString);
                }
                else {
                    if (currentBatch.length() > 0) {
                        messagesToSend.add(currentBatch.toString());
                        currentBatch.setLength(0);
                    }

                    if (teamString.length() >= max_length && max_length != 0) {
                        messagesToSend.add(teamString.substring(0, max_length));
                    } else {
                        currentBatch.append(teamString);
                    }
                }
            }

            if (currentBatch.length() > 0) {
                messagesToSend.add(currentBatch.toString());
            }

            Core.app.post(() -> {
                if (player == null || player.unit() == null) return;
                if (messagesToSend.isEmpty()) return;

                for (int i = 0; i < messagesToSend.size; i++) {
                    String msg = messagesToSend.get(i);

                    Timer.schedule(() -> {
                        if (player == null) return;

                        if(Core.settings.getBool("unitatchat")){
                            if(state.rules.pvp) {
                                Call.sendChatMessage("/t " + msg);
                            } else {
                                Call.sendChatMessage(msg);
                            }
                        } else {
                            ChatFragment.ChatMessage m = ui.chatfrag.addMessage(msg, null, null, "", msg);
                            NetClient.findCoords(m);
                        }
                    }, i * 1.1f);
                }
            });
        });
    }

    private static class EffState {
        float prevValue = 0;
        float currentValue = 0;
        float startTime = 0;
        float lastUpdate = 0;
    }

    private float getSmoothEfficiency(Building build) {
        float timerSeconds = 10f; // Уменьшим окно до 10 сек для отзывчивости
        float windowTicks = timerSeconds * 60f;
        float now = Time.time;

        EffState state = effStorage.get(build.id);

        if (state == null) {
            state = new EffState();
            state.startTime = now;
            state.lastUpdate = now;
            effStorage.put(build.id, state);
            return 0;
        }

        float passedTime = now - state.lastUpdate;
        float elapsedSinceStart = now - state.startTime;

        if (elapsedSinceStart > windowTicks) {
            state.prevValue = state.currentValue / windowTicks;
            state.currentValue = 0;
            state.startTime = now;
        }

        // ГЛАВНОЕ ИЗМЕНЕНИЕ: эффективность умножаем на скорость времени (Overdrive)
        // Для ускорителей берем просто их рабочее состояние
        float points = build.efficiency * build.timeScale();

        // Если это сам ускоритель, его timeScale всегда 1, но нам важно, работает ли он
        if(build instanceof mindustry.world.blocks.defense.OverdriveProjector.OverdriveBuild) {
            points = build.efficiency;
        }

        state.currentValue += passedTime * points;
        state.lastUpdate = now;

        float measurement = Math.min(1f, elapsedSinceStart / windowTicks);
        return (state.currentValue / windowTicks) + (state.prevValue * (1f - measurement));
    }
    public static void initTypes() {
        // Используем select вместо filter
        sortedUnitTypes = Vars.content.units().copy()
                .select(u -> !u.internal)
                .select(u -> !u.hidden)
                .sort(u -> u.health);
    }


    public static Item[] allMineOres(){
        if(Items.copper == null) return ALL_MINE_ORES;
        if(ALL_MINE_ORES.length == 0 || ALL_MINE_ORES[0] == null){
            ALL_MINE_ORES = new Item[]{
                Items.copper, Items.lead, Items.sand, Items.coal, Items.scrap,
                Items.titanium, Items.beryllium
            };
        }
        return ALL_MINE_ORES;
    }

    /** Thorium, graphite and tungsten are not on the panel: support units cannot mine them. */
    static boolean isListedMineOre(Item it){
        if(it == null) return false;
        for(Item ore : allMineOres()) if(ore == it) return true;
        return false;
    }

    public static boolean isOreEnabled(Item it){
        return it != null && enabledOres.contains(it);
    }

    public static boolean typeCanMine(UnitType type, Item it){
        if(type == null || it == null || type.mineTier < 0) return false;
        if(type.mineTier < it.hardness) return false;
        boolean wall = it == Items.beryllium || it == Items.tungsten || it == Items.graphite;
        if(wall) return type.mineWalls;
        return type.mineFloor || type.mineTier > 0;
    }

    public static boolean isMinerTypeOn(UnitType type){
        if(type == UnitTypes.mono) return mineMonos;
        if(type == UnitTypes.poly) return minePolys;
        if(type == UnitTypes.pulsar) return minePulss;
        if(type == UnitTypes.mega) return mineMegas;
        if(type == UnitTypes.quasar) return mineQuazs;
        return false;
    }

    public static void setManualOreAssign(boolean on){
        manualOreAssign = on;
        Core.settings.put("fd-manualOreAssign", on);
        MinersFDAI.forceReassign();
    }

    public static void setMinerTypeOn(UnitType type, boolean on){
        if(type == UnitTypes.mono) mineMonos = on;
        else if(type == UnitTypes.poly) minePolys = on;
        else if(type == UnitTypes.pulsar) minePulss = on;
        else if(type == UnitTypes.mega) mineMegas = on;
        else if(type == UnitTypes.quasar) mineQuazs = on;
        MinersFDAI.forceReassign();
    }

    public static void setOreEnabled(Item it, boolean on){
        setOreEnabled(it, on, true);
    }

    public static void setOreEnabled(Item it, boolean on, boolean save){
        if(!isListedMineOre(it)) return;
        if(on) enabledOres.add(it);
        else enabledOres.remove(it);
        syncLegacyOreFlags();
        if(save) saveMiningPrefs();
        MinersFDAI.forceReassign();
    }

    public static ObjectSet<Item> oresFor(UnitType type){
        if(type == null) return new ObjectSet<>();
        ObjectSet<Item> s = unitMineOres.get(type);
        if(s == null){
            s = new ObjectSet<>();
            unitMineOres.put(type, s);
        }
        return s;
    }

    public static boolean unitAssigned(UnitType type, Item it){
        return oresFor(type).contains(it);
    }

    public static void toggleUnitOre(UnitType type, Item it){
        if(type == null || !isListedMineOre(it)) return;
        ObjectSet<Item> s = oresFor(type);
        if(s.contains(it)) s.remove(it);
        else s.add(it);
        saveMiningPrefs();
        MinersFDAI.forceReassign();
    }

    public static Seq<Item> possibleOresFor(UnitType type, Seq<Item> enabled){
        Seq<Item> assigned = new Seq<>();
        if(enabled == null || enabled.isEmpty()) return assigned;
        if(!manualOreAssign){
            for(Item it : enabled){
                if(it != null && typeCanMine(type, it)) assigned.add(it);
            }
            return assigned;
        }
        ObjectSet<Item> pref = oresFor(type);
        for(Item it : enabled){
            if(it == null || !typeCanMine(type, it)) continue;
            if(pref.contains(it)) assigned.add(it);
        }
        return assigned;
    }

    static void defaultUnitOres(){
        unitMineOres.clear();
        oresFor(UnitTypes.mono).addAll(Items.copper, Items.lead);
        oresFor(UnitTypes.poly).add(Items.sand);
        oresFor(UnitTypes.pulsar).add(Items.coal);
        oresFor(UnitTypes.mega).add(Items.titanium);
        oresFor(UnitTypes.quasar).add(Items.titanium);
    }

    public static void loadMiningPrefs(){
        if(Items.copper == null) return;
        allMineOres();
        enabledOres.clear();
        String raw = Core.settings.getString("fd-ores", "copper,lead,sand,coal,titanium");
        for(String n : raw.split(",")){
            Item it = content.items().find(i -> i.name.equals(n.trim()));
            if(isListedMineOre(it)) enabledOres.add(it);
        }
        if(enabledOres.isEmpty()){
            enabledOres.addAll(Items.copper, Items.lead, Items.sand, Items.coal, Items.titanium);
        }
        defaultUnitOres();
        loadUnitOres(UnitTypes.mono, "fd-ore-mono", "copper,lead");
        loadUnitOres(UnitTypes.poly, "fd-ore-poly", "sand");
        loadUnitOres(UnitTypes.pulsar, "fd-ore-pulsar", "coal");
        loadUnitOres(UnitTypes.mega, "fd-ore-mega", "titanium");
        loadUnitOres(UnitTypes.quasar, "fd-ore-quasar", "titanium");
        manualOreAssign = Core.settings.getBool("fd-manualOreAssign", false);
        syncLegacyOreFlags();
    }

    static void loadUnitOres(UnitType type, String key, String def){
        ObjectSet<Item> s = oresFor(type);
        s.clear();
        String raw = Core.settings.getString(key, def);
        if(raw == null || raw.trim().isEmpty()) raw = def;
        for(String n : raw.split(",")){
            Item it = content.items().find(i -> i.name.equals(n.trim()));
            if(isListedMineOre(it)) s.add(it);
        }
        if(s.isEmpty() && def != null){
            for(String n : def.split(",")){
                Item it = content.items().find(i -> i.name.equals(n.trim()));
                if(isListedMineOre(it)) s.add(it);
            }
        }
    }

    static void saveMiningPrefs(){
        StringBuilder sb = new StringBuilder();
        for(Item it : allMineOres()){
            if(it == null || !enabledOres.contains(it)) continue;
            if(sb.length() > 0) sb.append(',');
            sb.append(it.name);
        }
        Core.settings.put("fd-ores", sb.toString());
        saveUnitOres(UnitTypes.mono, "fd-ore-mono");
        saveUnitOres(UnitTypes.poly, "fd-ore-poly");
        saveUnitOres(UnitTypes.pulsar, "fd-ore-pulsar");
        saveUnitOres(UnitTypes.mega, "fd-ore-mega");
        saveUnitOres(UnitTypes.quasar, "fd-ore-quasar");
    }

    static void saveUnitOres(UnitType type, String key){
        ObjectSet<Item> s = oresFor(type);
        StringBuilder sb = new StringBuilder();
        for(Item it : allMineOres()){
            if(it == null || !s.contains(it)) continue;
            if(sb.length() > 0) sb.append(',');
            sb.append(it.name);
        }
        Core.settings.put(key, sb.toString());
    }

    static void syncLegacyOreFlags(){
        minecopper = enabledOres.contains(Items.copper);
        minelead = enabledOres.contains(Items.lead);
        minetitan = enabledOres.contains(Items.titanium);
        minesand = enabledOres.contains(Items.sand);
        minecoal = enabledOres.contains(Items.coal);
        minescrap = enabledOres.contains(Items.scrap);
        mineBerylliumwall = enabledOres.contains(Items.beryllium);
        itemtomine.clear();
        for(Item it : enabledOres) itemtomine.add(it);
    }

    private void updatemineitems(){
        syncLegacyOreFlags();
    }
    private void drawBuildings() {
        if (!viewprogressunit && !viewprogresbuild && !viewEfficiency) return;

        float fontScale = 0.25f / Scl.scl(1.0f);
        Font font = Fonts.outline;

        font.setUseIntegerPositions(false);
        font.getData().setScale(fontScale);
        font.setColor(Color.white);

        for(Building bui : Groups.build) {
            if(!bui.within(Core.camera.position, Core.graphics.getWidth() / 1.5f)) continue;

            if (viewprogressunit) {             // --- ЛОГИКА ФАБРИК ---
                float prog = 0;
                if (bui instanceof UnitFactory.UnitFactoryBuild build) {
                    prog = build.fraction();
                } else if (bui instanceof Reconstructor.ReconstructorBuild buildr) {
                    prog = buildr.fraction();
                }

                if (prog > 0.0001f) {
                    drawBarAndText(bui.x, bui.y, bui.block.size * 4, bui.team.color, prog, true, font);
                }
            }

            if (viewprogresbuild && bui instanceof ConstructBuild entity) {        // --- ЛОГИКА СТРОИТЕЛЬСТВА  ---
                float prog = entity.progress;
                if (prog > 0.0001f && prog < 1f) {
                    drawTextOnly(bui.x, bui.y, prog, font);
                }
            }

            if (viewEfficiency) {
                boolean isProducer = bui.block.category == Category.production ||
                        bui.block.category == Category.crafting ||
                        bui.block.category == Category.units ||
                        bui instanceof mindustry.world.blocks.distribution.MassDriver.MassDriverBuild  ||
                        bui instanceof mindustry.world.blocks.production.Pump.PumpBuild  ||
                        bui instanceof mindustry.world.blocks.defense.OverdriveProjector.OverdriveBuild  ||
                        bui instanceof mindustry.world.blocks.power.PowerGenerator.GeneratorBuild;

                if (isProducer) {
                    float smoothEff = getSmoothEfficiency(bui);

                    if (smoothEff >= 0f) {
                        // Расчет цвета:
                        if (smoothEff < 0.5f) tmpCol.set(Color.red).lerp(Color.orange, smoothEff * 2f);
                        else if (smoothEff <= 1.02f) tmpCol.set(Color.orange).lerp(Color.green, (smoothEff - 0.5f) * 2f);
                        else tmpCol.set(Color.green).lerp(Color.cyan, Math.min(1f, (smoothEff - 1f) / 1.5f)); // Голубой для > 100%

                        Draw.z(Layer.darkness + 1);
                        sb.setLength(0);

                        // Если эффективность > 100%, подсвечиваем это
                        int val = Math.round(smoothEff * 100);
                        sb.append(val).append("%");

                        layout.setText(font, sb);
                        font.setColor(tmpCol);

                        // Отрисовка в центре блока
                        font.draw(sb, bui.x - layout.width / 2, bui.y + layout.height / 2);
                    }
                }
            }
        }

        font.getData().setScale(1f);
        Draw.reset();
    }

    private void drawUnits() {
        if (!viewunitshealth && !viewunitseffects) return;

        brokenFade = Mathf.lerpDelta(brokenFade, 1f, 0.1f);

        float fontScale = 0.25f / Scl.scl(1.0f);
        Font font = Fonts.outline;

        font.setUseIntegerPositions(false);
        font.getData().setScale(fontScale);

        for(Unit unit : Groups.unit) {
            if(!unit.isAdded() || !unit.within(Core.camera.position, Core.graphics.getWidth() / 1.5f)) continue;

            if (viewunitshealth && unit.health < unit.maxHealth) {
                float prog = unit.health / unit.maxHealth;
                if (prog > 0.0001f) {
                    tmpCol.set(Color.white).lerp(Color.black, 1f - prog);

                    drawBarAndText(unit.x, unit.y - (unit.hitSize * 3) + (unit.hitSize + 2), unit.hitSize, unit.team.color, prog, false, font);
                }
            }
            if (viewunitseffects) {
                Draw.alpha(0.90f * brokenFade);
                tempBits.clear();
                Bits applied = unit.statusBits();

                if(applied != null && !applied.isEmpty()){
                    int i = 0;
                    for(StatusEffect effect : content.statusEffects()){
                        if(applied.get(effect.id) && !effect.isHidden()){
                            Draw.rect(effect.uiIcon, unit.x + i * effect.uiIcon.width / 4f, unit.y);
                            i++;
                        }
                    }
                }
            }
        }

        font.getData().setScale(1f);
        Draw.reset();
    }

    private void drawBarAndText(float x, float y, float width, Color teamColor, float prog, boolean isFactory, Font font) {
        float yOffset = isFactory ? width + 2 : width + 2; // В оригинале было (hw + 2)

        Draw.z(Layer.darkness + 1);

        if(isFactory){
            Draw.color(Pal.darkerGray);
        } else {
            Draw.color(tmpCol);
        }

        Lines.stroke(4);
        Lines.line(x - width, y + yOffset, x - width + width * 2, y + yOffset);

        Draw.color(teamColor);
        Lines.stroke(2);
        Lines.line(x - width, y + yOffset, x - width + width * 2 * prog, y + yOffset);

        sb.setLength(0);
        sb.append((int)(prog * 100)).append("%");

        layout.setText(font, sb);
        font.setColor(Color.white);
        font.draw(sb, x - layout.width / 2, y + yOffset + layout.height / 2 + 6);
        Draw.reset();
    }

    private void drawTextOnly(float x, float y, float prog, Font font) {
        Draw.z(Layer.darkness + 1);

        sb.setLength(0);
        sb.append((int)(prog * 100)).append("%");

        layout.setText(font, sb);
        font.setColor(Color.white);
        font.draw(sb, x - layout.width / 2, y + layout.height / 2);

        Draw.reset();
    }
}
