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
import mindustry.client.morj.*;

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
import mindustry.ui.dialogs.*;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.CheckSetting;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.SliderSetting;
import mindustry.ui.dialogs.SettingsMenuDialog.SettingsTable.TextSetting;
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
                HiddenItems.refresh();
                OreAdsorb.update();
                DuctMarks.refresh();
                ProcessorUse.update();
                WavePath.update();
                WaveHealth.update();
                CoreChart.update();
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
                DamageNumbers.draw();
                TransportScan.draw();
                OverdrivePreview.draw();
                ProjectorRanges.draw();
                ProcessorUse.draw();
                ProcessorStatus.draw();
                WavePath.draw();
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
                boolean canBuild = BuildPath.anyAffordable() && control.input.isBuilding;
                if(Navigation.currentlyFollowing == null){currentfollowmode = 1; startmining();}
                else if(Navigation.currentlyFollowing instanceof BuildPath){
                    boolean noPlans = player.unit().plans.size == 0;
                    if(control.input.isBuilding && (noPlans || !BuildPath.anyAffordable())){
                        currentfollowmode = 1;
                        if(!noPlans || prevfollowmode == 1) startmining();
                        else Navigation.follow(new RepairPath(), true);
                    }
                } else if(Navigation.currentlyFollowing instanceof MinePath){
                    if(canBuild) {prevfollowmode = 1; currentfollowmode = 1; Navigation.follow(new BuildPath("self")); } else return;
                }else if(Navigation.currentlyFollowing instanceof RepairPath){
                    if(canBuild) {prevfollowmode = 3; currentfollowmode = 3; Navigation.follow(new BuildPath("self")); } else return;
                }
            }
        });
    }


    /** Automatic power fix: silent when nothing to connect; every fifth run also removes extra links. */
    private static void fixPowerQuiet(){
        fixPowerRuns++;
        ClientVars.clientCommandHandler.handleMessage(fixPowerRuns % 5 == 0 ? "!fixpower c qc" : "!fixpower c q", player);
    }

    /** {0}/{1} replacement without MessageFormat. The shared formatter crashes on Java 25. */
    private static String fill(String pattern, int a){
        return pattern.replace("{0}", Integer.toString(a));
    }

    private static String fill(String pattern, int a, int b){
        return fill(pattern, a).replace("{1}", Integer.toString(b));
    }

    public static void startInit() {
        mindustry.client.fallen.ActivityLogger.init();
        mindustry.client.fallen.FdLogicQol.init();
        mindustry.client.fallen.MinersFDAI.init();
        BuilderAssist.init();
        AutoDrillPlacer.init();
        ThreatRadar.init();
        AntiAttemPatcher.load();
        Log.info("Start init");
    }

    public void rebuild(){         //category does not change on rebuild anymore, only on new world load
        Group group = fdpanel.parent;
        int index = fdpanel.getZIndex();
        boolean keep = placedPanel && panelShell != null && panelShell.getHeight() > 1f;
        float keepX = keep ? panelShell.x : 0f;
        float keepTop = keep ? panelShell.y + panelShell.getHeight() : 0f;
        fdpanel.remove();
        build(group);
        fdpanel.setZIndex(index);
        if(keep && panelShell != null){
            placedPanel = true;
            panelShell.setPosition(keepX, keepTop - panelShell.getHeight());
            fitShell(true);
            clampPanel();
            savePanel();
        }
    }

    /** Key of the open tab (see {@link PanelLayout#tabs()}). */
    private String panelTab = initialTab();
    private Table panelPage;
    private ScrollPane pagePane;
    private Cell<ScrollPane> pageCell;
    private Table panelShell;
    private TextButton panelTray;
    private Label panelTabLabel;
    private boolean placedPanel;
    private boolean pagesRegistered;
    /** Every panel button, registered once per {@link #reregister()}, in registration order. */
    private final OrderedMap<String, QueuedTile> registry = new OrderedMap<>();
    private boolean extrasRegistered;
    private int tileReg;
    private final Seq<PanelIcon> icons = new Seq<>();
    private final ObjectSet<String> iconIds = new ObjectSet<>();
    private final ObjectMap<String, Runnable> actions = new ObjectMap<>();
    /** Next tile() keeps this id. Used for settings pinned onto the panel. */
    private String forcedId;
    /** Home tab and home group of the buttons being registered. */
    private String regTab = PanelLayout.builtinTabs[0], regGroup;

    public static final class PanelIcon{
        public final String id, label, caption;
        public final Drawable icon;
        /** Right click does something besides the main action. */
        public final boolean extra;
        public final int reg;
        /** Where the button lives unless the player moved it. homeGroup may be null. */
        public final String homeTab, homeGroup;
        public PanelIcon(String id, String label, String caption, Drawable icon, boolean extra, int reg, String homeTab, String homeGroup){
            this.id = id;
            this.label = label;
            this.caption = caption;
            this.icon = icon;
            this.extra = extra;
            this.reg = reg;
            this.homeTab = homeTab;
            this.homeGroup = homeGroup;
        }

        /** Caption shown on the panel: the player's own text or the default. */
        public String shownCaption(){
            String custom = PanelLayout.caption(id);
            return custom != null ? custom : caption;
        }

        /** Icon shown on the panel: the player's choice or the default. */
        public Drawable shownIcon(){
            Drawable custom = PanelLayout.icon(PanelLayout.iconKey(id));
            return custom != null ? custom : icon;
        }

        public String tab(){
            return PanelLayout.tabOf(id, homeTab);
        }

        public String group(){
            return PanelLayout.groupOf(id, homeTab, homeGroup);
        }
    }

    private static final class QueuedTile{
        String id, label, tip, rightTip;
        String tab, group;
        Drawable drawable;
        Boolp state;
        /** Small text in the corner of the button (a count, a value, a timer). Null or empty hides it. */
        Prov<String> badge;
        Runnable left, right;
        int reg;
    }

    private static boolean helpMode;
    private final Vec2 dockTmp = new Vec2();
    private final Interval dockTimer = new Interval();
    private float dockTop = -1f;
    private boolean dockMoved;
    private boolean dockDragging;

    public Seq<PanelIcon> catalog(){
        return icons;
    }

    public String currentTab(){
        return panelTab;
    }

    private static String initialTab(){
        String key = Core.settings.getString("morj-panel-tabkey", null);
        if(key != null && !key.isEmpty()) return key;
        int old = Mathf.clamp(Core.settings.getInt("morj-panel-tab", 0), 0, 4);
        return PanelLayout.builtinTabs[old];
    }

    /** Buttons shown on a tab, in panel order. */
    public Seq<PanelIcon> tabIcons(String tab){
        Seq<PanelIcon> out = new Seq<>();
        for(PanelIcon icon : icons) if(icon.tab().equals(tab)) out.add(icon);
        out.sort(Structs.comparingInt(icon -> {
            int index = PanelLayout.index(icon.id);
            return index >= PanelLayout.unordered ? PanelLayout.unordered + icon.reg : index;
        }));
        return out;
    }

    /** Drops the cached button list so a newly pinned setting is included. */
    public void reregister(){
        pagesRegistered = false;
        registerPages();
    }

    /** Registers every button once, so the list exists before a tab is opened. Tabs are drawn from this list. */
    public void registerPages(){
        if(pagesRegistered || Items.copper == null) return;
        pagesRegistered = true;
        registry.clear();
        icons.clear();
        iconIds.clear();
        actions.clear();
        tileReg = 0;
        pageMine(null);
        pageBuild(null);
        pageView(null);
        pageFight(null);
        pageServer(null);
        registerExtras();
        registerBadges();
        regTab = PanelLayout.builtinTabs[0];
        regGroup = null;
    }
    private static final Color panelDim = new Color(1f, 1f, 1f, 0.38f);
    private static final Color groupLine = new Color(1f, 1f, 1f, 0.12f);
    private static Drawable panelOnBg, panelOffBg, panelOverBg, panelActionBg, panelBadgeBg, panelTabOnBg, panelClearBg;
    private static Button.ButtonStyle panelBtnStyle, panelActionStyle;
    private static ImageButton.ImageButtonStyle panelTabStyle;

    private void ensureStyles(){
        if(panelBtnStyle != null) return;
        panelOffBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.045f);
        panelOnBg = ((TextureRegionDrawable)Tex.whiteui).tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.26f);
        panelOverBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.13f);
        panelActionBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0.085f);
        panelBadgeBg = ((TextureRegionDrawable)Tex.whiteui).tint(0f, 0f, 0f, 0.72f);
        panelTabOnBg = ((TextureRegionDrawable)Tex.whiteui).tint(Pal.accent.r, Pal.accent.g, Pal.accent.b, 0.18f);
        panelClearBg = ((TextureRegionDrawable)Tex.whiteui).tint(1f, 1f, 1f, 0f);
        panelBtnStyle = new Button.ButtonStyle();
        panelBtnStyle.up = panelOffBg;
        panelBtnStyle.over = panelOverBg;
        panelBtnStyle.down = panelOnBg;
        panelBtnStyle.checked = panelOnBg;
        panelActionStyle = new Button.ButtonStyle();
        panelActionStyle.up = panelActionBg;
        panelActionStyle.over = panelOverBg;
        panelActionStyle.down = panelOverBg;
        panelActionStyle.checked = panelActionBg;
        panelTabStyle = new ImageButton.ImageButtonStyle(Styles.clearTogglei);
        panelTabStyle.up = panelClearBg;
        panelTabStyle.over = panelOverBg;
        panelTabStyle.down = panelTabOnBg;
        panelTabStyle.checked = panelTabOnBg;
        panelTabStyle.imageUpColor = Color.lightGray;
        panelTabStyle.imageOverColor = Color.white;
        panelTabStyle.imageDownColor = Color.white;
        panelTabStyle.imageCheckedColor = Color.white;
    }

    private float tileIcon(float scale){
        return Math.max(14f, settings.getInt("buttonsizefdpamel", 30) * 0.52f * scale);
    }

    private float tileWidth(float iconSize){
        return Math.max(iconSize + 12f, 68f);
    }

    private float tileHeight(float iconSize){
        return iconSize + 25f * PanelLayout.titleScale();
    }

    public void build(Group parent){
        if(Items.copper != null) loadMiningPrefs();
        reregister();
        ensureStyles();
        placedPanel = false;
        parent.fill(full -> {
            fdpanel = full;
            full.touchable = Touchable.childrenOnly;
            full.visible(() -> ui.hudfrag.shown);

            panelShell = new Table(Tex.pane);
            panelShell.top();
            panelShell.margin(3f, 4f, 4f, 4f);
            panelShell.defaults().growX();
            panelShell.touchable = Touchable.enabled;

            panelShell.image(Tex.whiteui).color(Pal.accent).height(3f).growX().padBottom(4f).row();

            panelShell.table(head -> {
                head.touchable = Touchable.enabled;
                Image grip = new Image(Icon.moveSmall);
                grip.setColor(Pal.accent);
                head.add(grip).size(16f).padRight(5f);
                Label title = head.add("morj").left().get();
                title.setFontScale(0.95f * PanelLayout.titleScale());
                title.setColor(Pal.accent);
                panelTabLabel = head.add("").left().growX().padLeft(6f).get();
                panelTabLabel.setFontScale(0.78f * PanelLayout.titleScale());
                panelTabLabel.setColor(Color.lightGray);
                panelTabLabel.setEllipsis(true);
                dragPanel(head);
                head.button("?", Styles.cleart, () -> {
                    helpMode = !helpMode;
                    Core.app.post(this::rebuild);
                }).size(26f).update(b -> b.setColor(helpMode ? Pal.accent : Color.white)).tooltip("@client.morj.help");
                head.button(Icon.lock, Styles.clearTogglei, () -> {
                    boolean on = !Core.settings.getBool("morj-panel-dock", false);
                    Core.settings.put("morj-panel-dock", on);
                    dockTop = -1f;
                }).checked(b -> Core.settings.getBool("morj-panel-dock", false)).size(26f).tooltip("@client.morj.dock");
                head.button(Icon.settingsSmall, Styles.cleari, PanelConfigDialog::open).size(26f).tooltip("@client.morj.panelcfg");
                head.button(Icon.leftOpenSmall, Styles.cleari, this::toggleTray).size(26f).tooltip("@client.morj.tray");
            }).growX().padBottom(3f).row();

            if(helpMode){
                Label hint = panelShell.add(bundle.get("client.morj.help.hint")).left().growX().padBottom(2f).get();
                hint.setWrap(true);
                hint.setFontScale(0.72f);
                hint.setColor(Color.lightGray);
                panelShell.row();
            }

            panelShell.table(bars -> {
                bars.defaults().height(14f).growX().pad(1f);
                bars.add(new Bar(
                    () -> {
                        Unit u = player == null ? null : player.unit();
                        if(u == null) return bundle.get("client.morj.hp.empty");
                        return fill(bundle.get("client.morj.hp"), Mathf.round(u.health), Mathf.round(u.maxHealth));
                    },
                    () -> Pal.health,
                    () -> player == null || player.unit() == null ? 0f : Mathf.clamp(player.unit().healthf())
                )).row();
                bars.add(new Bar(
                    () -> {
                        Unit u = player == null ? null : player.unit();
                        int shield = u == null ? 0 : Mathf.round(u.shield);
                        return fill(bundle.get("client.morj.shield"), shield);
                    },
                    () -> Pal.lancerLaser,
                    () -> {
                        Unit u = player == null ? null : player.unit();
                        return u == null ? 0f : Mathf.clamp(u.shield / Math.max(u.maxHealth, 1f));
                    }
                ));
            }).padBottom(4f).row();

            panelShell.table(tabs -> {
                tabs.left();
                int slot = 0;
                for(PanelLayout.Tab tab : PanelLayout.tabs()){
                    String key = tab.key;
                    Table cell = new Table();
                    ImageButton button = new ImageButton(PanelLayout.tabIcon(tab), panelTabStyle);
                    button.resizeImage(18f);
                    button.clicked(() -> showPanelTab(key));
                    button.update(() -> button.setChecked(key.equals(panelTab)));
                    String name = PanelLayout.tabName(tab);
                    button.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add(name)));
                    cell.add(button).size(28f).row();
                    Image line = new Image(Tex.whiteui);
                    line.update(() -> line.setColor(key.equals(panelTab) ? Pal.accent : Color.clear));
                    cell.add(line).height(2f).growX();
                    tabs.add(cell).padRight(1f);
                    if(++slot % 9 == 0) tabs.row();
                }
            }).left().padBottom(2f).row();
            panelShell.image(Tex.whiteui).color(groupLine).height(1f).growX().padBottom(3f).row();

            panelPage = new Table();
            panelPage.top().left();
            pagePane = new ScrollPane(panelPage, Styles.smallPane);
            pagePane.setScrollingDisabled(true, false);
            pagePane.setOverscroll(false, false);
            pagePane.setScrollbarsOnTop(true);
            pagePane.setFadeScrollBars(false);
            pageCell = panelShell.add(pagePane).growX().left();
            full.addChild(panelShell);

            TextButton.TextButtonStyle trayStyle = new TextButton.TextButtonStyle(Styles.flatt);
            trayStyle.up = panelOnBg;
            trayStyle.over = panelOverBg;
            trayStyle.down = panelOnBg;
            panelTray = new TextButton(Core.bundle.get("client.morj.traybtn"), trayStyle);
            panelTray.clicked(this::toggleTray);
            panelTray.setSize(78f, 34f);
            panelTray.addListener(new Tooltip(t -> t.background(Styles.black6).margin(4f).add("@client.morj.tray.show")));
            full.addChild(panelTray);

            full.update(() -> {
                if(panelShell == null) return;
                pollPanelKeys();
                boolean docked = Core.settings.getBool("morj-panel-dock", false);
                if(docked && !dockDragging && (dockTop < 0f || dockTimer.get(15f))) dockTop = panelsBottom();
                if(!dockDragging) fitShell(placedPanel);
                if(!placedPanel && Core.graphics.getHeight() > 0 && panelShell.getHeight() > 1f){
                    if(Core.settings.has("morj-panel-x")){
                        panelShell.setPosition(Core.settings.getFloat("morj-panel-x"), Core.settings.getFloat("morj-panel-y"));
                    }else{
                        float y = Core.graphics.getHeight() / 2f + Core.settings.getInt("yoffssetfdpamel", -200) - panelShell.getHeight() / 2f;
                        panelShell.setPosition(8f, y);
                    }
                    placedPanel = true;
                    fitShell(true);
                }
                if(placedPanel && !dockDragging && docked){
                    panelShell.setPosition(8f, dockTop - panelShell.getHeight() - 4f);
                    clampScene();
                }else{
                    clampPanel();
                }
                boolean tray = Core.settings.getBool("morj-panel-tray", false);
                panelShell.visible = !tray;
                panelTray.visible = tray;
                if(tray){
                    panelTray.setPosition(panelShell.x, panelShell.y + Math.max(0f, panelShell.getHeight() - panelTray.getHeight()));
                }
                releaseWheel();
            });
            showPanelTab(panelTab);
        });
    }

    private void dragPanel(Element handle){
        float[] last = new float[2];
        handle.addListener(new InputListener(){
            @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                if(button != KeyCode.mouseLeft || event.targetActor instanceof Button) return false;
                last[0] = x;
                last[1] = y;
                dockMoved = false;
                dockDragging = true;
                return true;
            }
            @Override public void touchDragged(InputEvent event, float x, float y, int pointer){
                if(Math.abs(x - last[0]) > 3f || Math.abs(y - last[1]) > 3f) dockMoved = true;
                panelShell.moveBy(x - last[0], y - last[1]);
                clampPanel();
            }
            @Override public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                dockDragging = false;
                if(dockMoved && Core.settings.getBool("morj-panel-dock", false)){
                    Core.settings.put("morj-panel-dock", false);
                    dockTop = -1f;
                }
                savePanel();
            }
        });
    }

    /** Bottom edge of the left HUD stack (wave info and similar). The docked panel sits under it. */
    private float panelsBottom(){
        float[] bottom = {Core.scene.getHeight()};
        collectPanels(ui.hudGroup, bottom);
        return bottom[0];
    }

    private void collectPanels(Group group, float[] bottom){
        if(group == null) return;
        for(Element e : group.getChildren()){
            if(e == null || e == fdpanel || e == panelShell || e == panelTray || ours(e)) continue;
            if(!e.visible || e.getWidth() <= 0f || e.getHeight() <= 0f) continue;
            if(e instanceof Table table && table.getBackground() != null){
                Vec2 pos = e.localToStageCoordinates(dockTmp.set(0f, 0f));
                float top = pos.y + e.getHeight();
                boolean leftEdge = pos.x <= 12f;
                boolean small = e.getWidth() < Core.scene.getWidth() * 0.8f || e.getHeight() < Core.scene.getHeight() * 0.8f;
                if(leftEdge && small && top >= Core.scene.getHeight() / 2f){
                    bottom[0] = Math.min(bottom[0], pos.y);
                }
            }
            if(e instanceof Group g) collectPanels(g, bottom);
        }
    }

    private boolean ours(Element e){
        for(Element at = e; at != null; at = at.parent){
            if(at == fdpanel || at == panelShell || at == panelTray) return true;
        }
        return false;
    }

    private void clampScene(){
        if(panelShell == null || panelShell.getWidth() < 1f) return;
        float maxX = Math.max(0f, Core.scene.getWidth() - panelShell.getWidth());
        float maxY = Math.max(0f, Core.scene.getHeight() - panelShell.getHeight());
        panelShell.setPosition(Mathf.clamp(panelShell.x, 0f, maxX), Mathf.clamp(panelShell.y, 0f, maxY));
    }

    private void toggleTray(){
        Core.settings.put("morj-panel-tray", !Core.settings.getBool("morj-panel-tray", false));
    }

    private void clampPanel(){
        if(panelShell == null || panelShell.getWidth() < 1f) return;
        float maxX = Math.max(0f, Core.graphics.getWidth() - panelShell.getWidth());
        float maxY = Math.max(0f, Core.graphics.getHeight() - panelShell.getHeight());
        panelShell.setPosition(Mathf.clamp(panelShell.x, 0f, maxX), Mathf.clamp(panelShell.y, 0f, maxY));
    }

    private void showPanelTab(String tab){
        if(PanelLayout.tab(tab) == null) tab = PanelLayout.firstTab();
        panelTab = tab;
        settings.put("morj-panel-tabkey", panelTab);
        if(!extrasRegistered && ui != null && ui.settings != null && PanelLayout.extras().any()) reregister();
        if(panelTabLabel != null) panelTabLabel.setText("· " + PanelLayout.tabName(PanelLayout.tab(panelTab)));
        if(panelPage == null) return;
        // A taller page keeps the top edge. Past the cap, the page scrolls instead of covering the screen.
        boolean anchor = placedPanel && panelShell != null && panelShell.getHeight() > 1f;
        panelPage.clear();
        panelPage.top().left();
        buildPage(panelPage, panelTab);
        if(panelShell != null){
            fitShell(anchor);
            if(pagePane != null) pagePane.setScrollYForce(0f);
            if(anchor){
                clampPanel();
                savePanel();
            }
        }
    }

    /** Tallest the shell may be. The reserve at the bottom leaves the chat readable. */
    private float maxShellHeight(){
        float sceneH = Core.scene == null ? 0f : Core.scene.getHeight();
        if(sceneH < 1f) sceneH = Core.graphics.getHeight();
        if(sceneH < 1f) return 10000f;
        float margin = 8f;
        boolean docked = Core.settings.getBool("morj-panel-dock", false);
        float anchor;
        if(docked){
            anchor = (dockTop >= 0f ? dockTop : sceneH) - 4f;
        }else if(panelShell != null && panelShell.getHeight() > 1f){
            anchor = panelShell.y + panelShell.getHeight();
        }else{
            anchor = sceneH - margin;
        }
        // Fit the page on screen. View is two columns of sections, so this stays short of the full window.
        return Math.max(Scl.scl(180f), Math.min(anchor - margin, sceneH - Scl.scl(48f)));
    }

    /** The pane grabs the wheel on click. Give it back once the cursor leaves, or the camera cannot zoom. */
    private void releaseWheel(){
        if(panelShell == null || Core.scene == null) return;
        Element focus = Core.scene.getScrollFocus();
        if(focus == null || panelShell.hasMouse()) return;
        if(focus == panelShell || focus.isDescendantOf(panelShell)) Core.scene.setScrollFocus(null);
    }

    /** Sizes the button pane to its content, but not past {@link #maxShellHeight()}. */
    private void fitShell(boolean keepTop){
        if(panelShell == null || pagePane == null || pageCell == null || panelPage == null) return;
        float x = panelShell.x;
        float top = panelShell.y + panelShell.getHeight();
        panelPage.invalidateHierarchy();
        panelShell.invalidateHierarchy();
        float panePref = pagePane.getPrefHeight();
        float shellPref = panelShell.getPrefHeight();
        float capped = pageCell.maxHeight();
        boolean limited = capped > 1f;
        float chrome = Math.max(Scl.scl(36f), shellPref - (limited ? capped : panePref));
        float paneH = Math.min(panePref, Math.max(Scl.scl(72f), maxShellHeight() - chrome));
        paneH = Math.min(paneH, panePref);
        float unit = Math.max(0.001f, Scl.scl(1f));
        if(!limited || Math.abs(capped - paneH) > 0.5f){
            pageCell.height(paneH / unit);
            panelShell.invalidateHierarchy();
            panelShell.pack();
        }else if(panelShell.getHeight() < 1f){
            panelShell.pack();
        }
        if(keepTop && top > 1f){
            panelShell.setPosition(x, top - panelShell.getHeight());
        }
    }

    private void savePanel(){
        if(panelShell == null) return;
        Core.settings.put("morj-panel-x", panelShell.x);
        Core.settings.put("morj-panel-y", panelShell.y);
    }

    private void pageMine(Table page){
        regTab = "mine";
        regGroup = null;
        Table grid = null;
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
        regTab = "build";
        regGroup = null;
        Table grid = null;
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
            }, () -> PolySettingsDialog.instance.show(), "client.morj.right.poly");
        tile(grid, col, new TextureRegionDrawable(UnitTypes.nova.uiIcon), bundle.get("client.morj.btn.nova"), "@client.fdpanel.novaassist",
            () -> BuilderAssist.enabled, BuilderAssist::toggle, BuilderAssist::showSettings, "client.morj.right.nova");
        tile(grid, col, new TextureRegionDrawable(Blocks.mechanicalDrill.uiIcon), bundle.get("client.morj.btn.drill"), "@client.fdpanel.autodrill",
            AutoDrillPlacer::active, AutoDrillPlacer::toggle, AutoDrillPlacer::showSettings, "client.morj.right.drill");
        tile(grid, col, Icon.list, bundle.get("client.morj.btn.recipe"), "@client.fdpanel.recipe",
            null, RecipeDialog::open, null);
        tile(grid, col, Icon.cancel, bundle.get("client.morj.btn.schem"), "@client.fdpanel.schemcleanup",
            () -> settings.getBool("placeSchematicWithCleanup"), () -> settings.put("placeSchematicWithCleanup", !settings.getBool("placeSchematicWithCleanup")), null);
        tile(grid, col, Icon.crafting, bundle.get("client.morj.btn.transfer"), "@client.fdpanel.autotransfer",
            () -> AutoTransfer.enabled, () -> {
                boolean next = !AutoTransfer.enabled;
                AutoTransfer.enabled = next;
                settings.put("autotransfer", next);
                new Toast(1).add(bundle.get("client.autotransfer") + ": " + bundle.get(next ? "mod.enabled" : "mod.disabled"));
            }, AutoFillPriorityDialog::open, "client.morj.right.transfer");
        tile(grid, col, Icon.power, bundle.get("client.morj.btn.power"), "@client.fdpanel.fixpower",
            () -> autoFixPower, () -> ClientVars.clientCommandHandler.handleMessage("!fixpower c", player), () -> {
                autoFixPower = !autoFixPower;
                fixPowerTimer.reset(0, 0f);
                fixPowerRuns = 0;
            }, "client.morj.right.power");
        tile(grid, col, Icon.logic, bundle.get("client.morj.btn.fixcode"), "@client.fdpanel.fixcode",
            null, () -> ClientVars.clientCommandHandler.handleMessage("!fixcode r", player), null);
    }


    private void pageView(Table page){
        regTab = "view";
        regGroup = null;
        regGroup = "sec.map";
        Table map = null;
        int[] col = {0};
        tile(map, col, Icon.map, bundle.get("client.morj.btn.markers"), "@client.fdpanel.mapmarkers",
            () -> settings.getBool("mapmarkers", true), () -> settings.put("mapmarkers", !settings.getBool("mapmarkers", true)), null);
        tile(map, col, Icon.waves, bundle.get("client.morj.btn.winfo"), "@client.fdpanel.waveinfo",
            () -> settings.getBool("waveinfo", true), () -> settings.put("waveinfo", !settings.getBool("waveinfo", true)), null);
        tile(map, col, Icon.info, bundle.get("client.morj.btn.minfo"), "@client.fdpanel.mapinfo",
            () -> settings.getBool("mapinfofrag", true), () -> settings.put("mapinfofrag", !settings.getBool("mapinfofrag", true)), null);
        tile(map, col, new TextureRegionDrawable(Blocks.forceProjector.uiIcon), bundle.get("client.morj.btn.proj"), "@client.fdpanel.projectors",
            () -> settings.getBool("projectors", false), () -> settings.put("projectors", !settings.getBool("projectors", false)), null);
        tile(map, col, Icon.grid, bundle.get("client.morj.btn.mi2map"), "@client.fdpanel.mi2map",
            () -> settings.getBool("mi2map", false), () -> settings.put("mi2map", !settings.getBool("mi2map", false)), null);
        tile(map, col, Icon.diagonal, bundle.get("client.morj.btn.wpath"), "@client.fdpanel.wavepath",
            () -> settings.getBool("wavepath", true), () -> settings.put("wavepath", !settings.getBool("wavepath", true)), null);
        tile(map, col, Icon.warning, bundle.get("client.morj.btn.whp"), "@client.fdpanel.wavehp",
            () -> settings.getBool("wavehp", true), () -> settings.put("wavehp", !settings.getBool("wavehp", true)), null);

        regGroup = "sec.search";
        Table search = null;
        tile(search, col, new TextureRegionDrawable(UnitTypes.dagger.uiIcon), bundle.get("client.morj.btn.scanunits"), "@client.fdpanel.eye.units", null, this::checkunits, null);
        tile(search, col, new TextureRegionDrawable(Blocks.coreShard.uiIcon), bundle.get("client.morj.btn.scancores"), "@client.fdpanel.eye.cores", null, this::checkcores, null);
        tile(search, col, Icon.modeAttack, bundle.get("client.morj.btn.scanspawn"), "@client.fdpanel.eye.spawns", null, this::checkspawns, null);
        tile(search, col, new TextureRegionDrawable(Blocks.itemVoid.uiIcon), bundle.get("client.morj.btn.scanvoid"), "@client.fdpanel.eye.voids", null, this::checkvoids, null);
        tile(search, col, new TextureRegionDrawable(Blocks.itemSource.uiIcon), bundle.get("client.morj.btn.scansource"), "@client.fdpanel.eye.sources", null, this::checksources, null);
        tile(search, col, new TextureRegionDrawable(Blocks.worldProcessor.uiIcon), bundle.get("client.morj.btn.scanproc"), "@client.fdpanel.eye.worldproc", null, this::checkworldprocc, null);
        tile(search, col, new TextureRegionDrawable(UnitTypes.flare.uiIcon), bundle.get("client.morj.btn.wave"), "@client.fdpanel.eye.wave", null, this::checkNextWave, null);

        regGroup = "sec.labels";
        Table labels = null;
        tile(labels, col, Icon.eyeOff, bundle.get("client.morj.btn.fade"), "@client.fdpanel.smarttransparency",
            () -> settings.getBool("smarttransparency", false), () -> settings.put("smarttransparency", !settings.getBool("smarttransparency", false)), null);
        tile(labels, col, new TextureRegionDrawable(Blocks.illuminator.uiIcon), bundle.get("client.morj.btn.light"), "@client.fdpanel.light",
            () -> enableLight, () -> enableLight = !enableLight, null);
        tile(labels, col, Icon.add, bundle.get("client.morj.btn.hp"), "@client.fdpanel.unitshealth",
            () -> viewunitshealth, () -> viewunitshealth = !viewunitshealth, null);
        tile(labels, col, new TextureRegionDrawable(Blocks.groundFactory.uiIcon), bundle.get("client.morj.btn.uprogress"), "@client.fdpanel.unitprogress",
            () -> viewprogressunit, () -> viewprogressunit = !viewprogressunit, null);
        tile(labels, col, Icon.crafting, bundle.get("client.morj.btn.bprogress"), "@client.fdpanel.buildprogress",
            () -> viewprogresbuild, () -> viewprogresbuild = !viewprogresbuild, null);
        tile(labels, col, Icon.chartBar, bundle.get("client.morj.btn.eff"), "@client.fdpanel.efficiency",
            () -> viewEfficiency, () -> viewEfficiency = !viewEfficiency, null);
        tile(labels, col, Icon.effect, bundle.get("client.morj.btn.status"), "@client.fdpanel.uniteffects",
            () -> viewunitseffects, () -> viewunitseffects = !viewunitseffects, null);
        tile(labels, col, new TextureRegionDrawable(Blocks.coreShard.uiIcon), bundle.get("client.morj.btn.core"), "@client.fdpanel.coreitems",
            () -> settings.getBool("coreitems"), () -> settings.put("coreitems", !settings.getBool("coreitems")), null);
        tile(labels, col, Icon.chat, bundle.get("client.morj.btn.uchat"), "@client.fdpanel.unitatchat",
            () -> settings.getBool("unitatchat"), () -> settings.put("unitatchat", !settings.getBool("unitatchat")), null);
        tile(labels, col, Icon.box, bundle.get("client.morj.btn.items"), "@client.fdpanel.hiddenitems",
            () -> settings.getInt("hiddenitemopacity", 70) > 0,
            () -> settings.put("hiddenitemopacity", settings.getInt("hiddenitemopacity", 70) > 0 ? 0 : 70), null);
        tile(labels, col, Icon.modeAttack, bundle.get("client.morj.btn.dmg"), "@client.fdpanel.damagepopups",
            () -> settings.getBool("damagepopups", true), () -> settings.put("damagepopups", !settings.getBool("damagepopups", true)), null);

        regGroup = "sec.nets";
        Table nets = null;
        tile(nets, col, new TextureRegionDrawable(Blocks.massDriver.uiIcon), bundle.get("client.morj.btn.driver"), "@client.fdpanel.massdriverline",
            () -> settings.getBool("massdriverline", true), () -> settings.put("massdriverline", !settings.getBool("massdriverline", true)), null);
        tile(nets, col, Icon.zoom, bundle.get("client.morj.btn.scan"), "@client.fdpanel.transportscan",
            () -> settings.getBool("transportscan", false), () -> settings.put("transportscan", !settings.getBool("transportscan", false)), null);
        tile(nets, col, Icon.hammer, bundle.get("client.morj.btn.ore"), "@client.fdpanel.oreadsorb",
            () -> settings.getBool("oreadsorb", true), () -> settings.put("oreadsorb", !settings.getBool("oreadsorb", true)), null);
        tile(nets, col, Icon.logic, bundle.get("client.morj.btn.logic"), "@client.fdpanel.logicassist",
            () -> settings.getBool("logicassist", true), () -> settings.put("logicassist", !settings.getBool("logicassist", true)), null);
        tile(nets, col, Icon.units, bundle.get("client.morj.btn.rts"), "@client.fdpanel.rtsgroups",
            () -> settings.getBool("rtsgroups", true), () -> settings.put("rtsgroups", !settings.getBool("rtsgroups", true)), null);
        tile(nets, col, Icon.power, bundle.get("client.morj.btn.grid"), "@client.fdpanel.powergrid",
            () -> settings.getBool("powergrid", true), () -> settings.put("powergrid", !settings.getBool("powergrid", true)), null);
        tile(nets, col, new TextureRegionDrawable(Blocks.overdriveProjector.uiIcon), bundle.get("client.morj.btn.boost"), "@client.fdpanel.overdrivepreview",
            () -> settings.getBool("overdrivepreview", true), () -> settings.put("overdrivepreview", !settings.getBool("overdrivepreview", true)), null);
        tile(nets, col, new TextureRegionDrawable(Blocks.conduit.uiIcon), bundle.get("client.morj.btn.pipe"), "@client.fdpanel.ductcolor",
            () -> settings.getBool("ductcolor", false), () -> settings.put("ductcolor", !settings.getBool("ductcolor", false)), null);
        tile(nets, col, Icon.link, bundle.get("client.morj.btn.refs"), "@client.fdpanel.procref",
            () -> settings.getBool("procref", true), () -> settings.put("procref", !settings.getBool("procref", true)), null);
        tile(nets, col, new TextureRegionDrawable(Blocks.microProcessor.uiIcon), bundle.get("client.morj.btn.proc"), "@client.fdpanel.procstatus",
            () -> settings.getBool("procstatus", true), () -> settings.put("procstatus", !settings.getBool("procstatus", true)), null);
        tile(nets, col, Icon.chartBar, bundle.get("client.morj.btn.chart"), "@client.fdpanel.corechart",
            () -> settings.getBool("corechart", true), () -> settings.put("corechart", !settings.getBool("corechart", true)), null);
        regGroup = null;
    }

    private void pageFight(Table page){
        regTab = "fight";
        regGroup = null;
        Table grid = null;
        int[] col = {0};
        tile(grid, col, Icon.star, bundle.get("client.morj.btn.aim"), "@client.fdpanel.smarttargeting",
            FDAutoShoot::enabled, () -> FDAutoShoot.setEnabled(!FDAutoShoot.enabled()), FDAutoShoot::openMenu, "client.morj.right.aim");
        tile(grid, col, Icon.warning, bundle.get("client.morj.btn.radar"), "@client.fdpanel.radar",
            () -> ThreatRadar.enabled, ThreatRadar::toggle, ThreatRadar::showSettings, "client.morj.right.radar");
        tile(grid, col, new TextureRegionDrawable(Blocks.duo.uiIcon), bundle.get("client.morj.btn.builds"), "@client.fdpanel.smartbuildings",
            () -> settings.getBool("smartshoot-buildings", false),
            () -> settings.put("smartshoot-buildings", !settings.getBool("smartshoot-buildings", false)), null);
        tile(grid, col, Icon.cancel, bundle.get("client.morj.btn.ignbuild"), "@client.fdpanel.ignorebuildings",
            () -> settings.getBool("smartshoot-ignorebuildings", false),
            () -> settings.put("smartshoot-ignorebuildings", !settings.getBool("smartshoot-ignorebuildings", false)), null);
        tile(grid, col, Icon.units, bundle.get("client.morj.btn.ignunit"), "@client.fdpanel.ignoreunit",
            () -> settings.getBool("ignoreunit"), () -> settings.put("ignoreunit", !settings.getBool("ignoreunit")), null);
        tile(grid, col, new TextureRegionDrawable(Blocks.mender.uiIcon), bundle.get("client.morj.btn.ignheal"), "@client.fdpanel.ignoreheal",
            () -> settings.getBool("ignoreheal"), () -> settings.put("ignoreheal", !settings.getBool("ignoreheal")), null);
        tile(grid, col, Icon.line, bundle.get("client.morj.btn.uaim"), "@client.fdpanel.unitaim",
            () -> FDAutoShoot.viewUnitAim, () -> FDAutoShoot.viewUnitAim = !FDAutoShoot.viewUnitAim, null);
        tile(grid, col, new TextureRegionDrawable(UnitTypes.mega.uiIcon), bundle.get("client.morj.btn.ucmega"), "@client.fdpanel.mega",
            null, () -> ClientVars.clientCommandHandler.handleMessage("!uc " + UnitTypes.mega.localizedName, player), null);
    }

    private void pageServer(Table page){
        regTab = "server";
        regGroup = null;
        Table grid = null;
        int[] col = {0};
        tile(grid, col, Icon.refresh, bundle.get("client.morj.btn.sync"), "@client.fdpanel.sync",
            null, () -> Call.sendChatMessage("/sync"), null);
        tile(grid, col, Icon.ok, bundle.get("client.morj.btn.vote"), "@client.fdpanel.vote",
            null, () -> Call.sendChatMessage("/vote y"), null);
        tile(grid, col, Icon.map, bundle.get("client.morj.btn.rtv"), "@client.fdpanel.rtv",
            () -> rtvKey, () -> Call.sendChatMessage("/rtv"), () -> rtvKey = !rtvKey, "client.morj.right.auto");
        tile(grid, col, Icon.waves, bundle.get("client.morj.btn.rtvwave"), "@client.fdpanel.rtvwave",
            () -> rtvWaveKey, () -> Call.sendChatMessage("/rtv wave"), () -> rtvWaveKey = !rtvWaveKey, "client.morj.right.auto");
        tile(grid, col, Icon.book, bundle.get("client.morj.btn.history"), "@client.fdpanel.history",
            null, () -> Call.sendChatMessage("/history"), null);
        tile(grid, col, Icon.rotate, bundle.get("client.morj.btn.elite"), "@client.fdpanel.elite",
            null, () -> Call.sendChatMessage("/elite"), null);
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

    /** Queues an icon. Left click runs the action, right click the optional extra. A null state means a one-shot button. */
    private void tile(Table grid, int[] col, Drawable drawable, String label, String tip, Boolp state, Runnable left, Runnable right){
        tile(grid, col, drawable, label, tip, state, left, right, right == null ? null : "client.morj.right");
    }

    /** Registers a button for the current home tab/group. The grid arguments are kept for old call sites and ignored. */
    private void tile(Table grid, int[] col, Drawable drawable, String label, String tip, Boolp state, Runnable left, Runnable right, String rightTip){
        String id = forcedId != null ? forcedId : (tip != null && tip.startsWith("@") ? tip.substring(1) : String.valueOf(label));
        forcedId = null;
        int reg = tileReg++;
        if(registry.containsKey(id)) id = id + "#" + reg;
        String listLabel = id.startsWith("setting:") && tip != null ? tip : (tip != null && tip.startsWith("@") ? bundle.get(tip.substring(1)) : label);
        if(iconIds.add(id)) icons.add(new PanelIcon(id, listLabel, label, drawable, right != null, reg, regTab, regGroup));
        if(left != null) actions.put(id, left);
        QueuedTile queued = new QueuedTile();
        queued.id = id;
        queued.label = label;
        queued.tip = tip;
        queued.drawable = drawable;
        queued.state = state;
        queued.left = left;
        queued.right = right;
        queued.rightTip = rightTip;
        queued.reg = reg;
        queued.tab = regTab;
        queued.group = regGroup;
        registry.put(id, queued);
    }

    /** Registers client settings pinned onto the panel. Each one starts on the tab it was pinned to. */
    private void registerExtras(){
        extrasRegistered = ui != null && ui.settings != null;
        if(!extrasRegistered) return;
        regGroup = null;
        int[] col = {0};
        for(String rec : PanelLayout.extras()){
            int sep = rec.lastIndexOf('\t');
            if(sep <= 0) continue;
            String name = rec.substring(0, sep);
            regTab = rec.substring(sep + 1);
            SettingsMenuDialog.SettingsTable.Setting setting = ui.settings.findSetting(name);
            if(setting == null || setting.title == null) continue;
            String full = setting.title;
            Drawable icon = Icon.settingsSmall;
            String id = "setting:" + name;
            forcedId = id;
            if(setting instanceof CheckSetting check){
                tile(null, col, icon, shortTitle(full), full, check::panelCheck, () -> check.putBool(!check.panelCheck()), () -> dropExtra(name), "client.morj.panel.extraremove");
            }else if(setting instanceof SliderSetting slider){
                tile(null, col, icon, shortTitle(full), full, null, () -> openSlider(slider), () -> dropExtra(name), "client.morj.panel.extraremove");
                QueuedTile queued = registry.get(id);
                if(queued != null) queued.badge = () -> slider.panelText(slider.panelValue());
            }else if(setting instanceof TextSetting text){
                tile(null, col, icon, shortTitle(full), full, null, () -> openText(text), () -> dropExtra(name), "client.morj.panel.extraremove");
            }else{
                tile(null, col, icon, shortTitle(full), full, null, () -> {
                    if(ui.settings != null) ui.settings.show();
                }, () -> dropExtra(name), "client.morj.panel.extraremove");
            }
        }
    }

    // ---- live counters shown in the corner of some buttons ----
    private final ObjectIntMap<Item> mineCounts = new ObjectIntMap<>();
    private int minerCount;
    private final Interval badgeTimer = new Interval();

    private void refreshCounts(){
        if(!badgeTimer.get(30f)) return;
        mineCounts.clear();
        minerCount = 0;
        if(player == null || player.team() == null || !state.isGame()) return;
        for(Unit unit : player.team().data().units){
            Tile tile = unit.mineTile;
            if(tile == null) continue;
            Item drop = tile.drop();
            if(drop == null) drop = tile.wallDrop();
            if(drop == null) continue;
            mineCounts.increment(drop);
            minerCount++;
        }
    }

    private static String count(int value){
        return value > 0 ? String.valueOf(value) : null;
    }

    private int teamCount(UnitType type){
        if(player == null || player.team() == null || type == null || !state.isGame()) return 0;
        return player.team().data().countType(type);
    }

    private void badge(String id, Prov<String> text){
        QueuedTile queued = registry.get(id);
        if(queued != null && queued.badge == null) queued.badge = text;
    }

    /** Hooks counters to built-in buttons: miners per ore, units per assist type, timers and values. */
    private void registerBadges(){
        Object[][] ores = {
            {"client.fdpanel.minecopper", Items.copper}, {"client.fdpanel.minelead", Items.lead},
            {"client.fdpanel.minetitan", Items.titanium}, {"client.fdpanel.minesand", Items.sand},
            {"client.fdpanel.minecoal", Items.coal}, {"client.fdpanel.minescrap", Items.scrap},
            {"client.fdpanel.mineberyl", Items.beryllium}
        };
        for(Object[] pair : ores){
            Item item = (Item)pair[1];
            badge((String)pair[0], () -> {
                refreshCounts();
                return count(mineCounts.get(item, 0));
            });
        }
        badge("client.fdpanel.automine", () -> {
            refreshCounts();
            return count(minerCount);
        });
        badge("client.fdpanel.minepolys", () -> count(teamCount(UnitTypes.poly)));
        badge("client.fdpanel.assistpoly", () -> count(teamCount(UnitTypes.poly)));
        badge("client.fdpanel.assistpulsar", () -> count(teamCount(UnitTypes.pulsar)));
        badge("client.fdpanel.assistmega", () -> count(teamCount(UnitTypes.mega)));
        badge("client.fdpanel.assistquasar", () -> count(teamCount(UnitTypes.quasar)));
        badge("client.fdpanel.mega", () -> count(teamCount(UnitTypes.mega)));
        badge("client.fdpanel.fixpower", () -> {
            if(!autoFixPower) return null;
            float left = Math.max(0f, 60f * 60f - (Time.time - fixPowerTimer.getTime(0)));
            int sec = (int)(left / 60f);
            return (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
        });
        badge("client.fdpanel.hiddenitems", () -> {
            int value = settings.getInt("hiddenitemopacity", 70);
            return value > 0 ? value + "%" : null;
        });
    }

    private void dropExtra(String name){
        Core.app.post(() -> {
            PanelLayout.removeExtra(name);
            reregister();
            rebuild();
        });
    }

    private void openSlider(SliderSetting slider){
        BaseDialog dialog = new BaseDialog(slider.title == null ? "" : slider.title);
        int step = Math.max(1, slider.panelStep());
        Slider bar = new Slider(slider.panelMin(), slider.panelMax(), step, false);
        bar.setValue(slider.panelValue());
        Label value = new Label(slider.panelText(slider.panelValue()));
        bar.changed(() -> {
            int next = (int)bar.getValue();
            slider.putInt(next);
            value.setText(slider.panelText(next));
        });
        dialog.cont.add(bar).width(360f).pad(8f);
        dialog.cont.add(value).padLeft(8f);
        dialog.addCloseButton();
        dialog.show();
    }

    private void openText(TextSetting text){
        BaseDialog dialog = new BaseDialog(text.title == null ? "" : text.title);
        dialog.cont.field(text.panelText(), text::putText).width(360f).pad(8f);
        dialog.addCloseButton();
        dialog.show();
    }

    private static String shortTitle(String title){
        String plain = title.replaceAll("\\[[^\\]]*\\]", "");
        if(plain.length() <= 14) return plain;
        return plain.substring(0, 13) + "…";
    }

    private void pollPanelKeys(){
        if(state == null || !state.isGame() || state.isMenu()) return;
        if(ui == null || ui.chatfrag == null || ui.chatfrag.shown()) return;
        if(Core.scene == null || Core.scene.hasDialog() || Core.scene.hasField()) return;
        ObjectMap<String, String> map = PanelLayout.keyMap();
        if(map.size == 0) return;
        Seq<String> ids = new Seq<>();
        for(String id : map.keys()) ids.add(id);
        for(String id : ids){
            String raw = map.get(id);
            if(raw == null) continue;
            KeyCode code;
            try{
                code = KeyCode.valueOf(raw);
            }catch(IllegalArgumentException ignored){
                continue;
            }
            if(!bindable(code) || !Core.input.keyTap(code)) continue;
            Runnable run = actions.get(id);
            if(run == null) continue;
            run.run();
            return;
        }
    }

    private static boolean bindable(KeyCode code){
        if(code == null || code == KeyCode.unset || code == KeyCode.anyKey) return false;
        String name = code.name();
        return !name.startsWith("mouse") && !name.startsWith("scroll") && !name.startsWith("controller");
    }


    private String describe(QueuedTile queued){
        String base = queued.tip != null && queued.tip.startsWith("@") ? bundle.get(queued.tip.substring(1)) : queued.tip;
        StringBuilder text = new StringBuilder(base == null ? "" : base);
        if(queued.state != null){
            text.append("\n\n").append(bundle.get(queued.state.get() ? "client.morj.on" : "client.morj.off"));
        }
        if(queued.rightTip != null){
            text.append("\n").append(bundle.get(queued.rightTip));
        }
        return text.toString();
    }


    /** Fills a tab: loose buttons first, then the tab's groups (side by side when the tab asks for it). */
    private void buildPage(Table page, String key){
        ensureStyles();
        PanelLayout.Tab tab = PanelLayout.tab(key);
        int columns = tab == null ? 4 : Math.max(1, tab.cols);
        Seq<QueuedTile> all = new Seq<>();
        for(QueuedTile queued : registry.values()) all.add(queued);
        all.sort(Structs.comparingInt(q -> {
            int index = PanelLayout.index(q.id);
            return index >= PanelLayout.unordered ? PanelLayout.unordered + q.reg : index;
        }));
        Seq<String> loose = new Seq<>();
        ObjectMap<String, Seq<String>> byGroup = new ObjectMap<>();
        for(QueuedTile queued : all){
            if(PanelLayout.hidden(queued.id)) continue;
            if(!PanelLayout.tabOf(queued.id, queued.tab).equals(key)) continue;
            String group = PanelLayout.groupOf(queued.id, queued.tab, queued.group);
            if(group.isEmpty()) loose.add(queued.id);
            else byGroup.get(group, Seq::new).add(queued.id);
        }
        boolean any = false;
        if(loose.any()){
            Table grid = new Table();
            grid.left().top();
            layoutGrid(grid, loose, columns);
            page.add(grid).left().row();
            any = true;
        }
        int perRow = tab == null ? 1 : Math.max(1, tab.groupCols);
        int inner = perRow > 1 ? Math.min(columns, 4) : columns;
        Table sections = new Table();
        sections.top().left();
        int placed = 0;
        for(PanelLayout.Group group : PanelLayout.groups(key)){
            Seq<String> ids = byGroup.get(group.id);
            if(ids == null || ids.isEmpty()) continue;
            Table box = new Table();
            box.top().left();
            groupHeader(box, group, ids.size);
            if(!group.collapsed){
                Table grid = new Table();
                grid.left().top();
                layoutGrid(grid, ids, inner);
                box.add(grid).left().row();
            }
            sections.add(box).top().left().growX().padRight(placed % perRow < perRow - 1 ? 6f : 0f).padTop(any || placed >= perRow ? 4f : 0f);
            placed++;
            if(placed % perRow == 0) sections.row();
        }
        if(placed > 0) page.add(sections).left().growX().row();
        if(!any && placed == 0){
            Label empty = page.add(bundle.get("client.morj.panel.emptytab")).pad(8f).get();
            empty.setColor(Color.gray);
            empty.setFontScale(0.7f * PanelLayout.titleScale());
        }
    }

    /** Thin title line of a group. Click folds or unfolds it. */
    private void groupHeader(Table box, PanelLayout.Group group, int size){
        Table head = new Table();
        head.left();
        head.touchable = Touchable.enabled;
        Image arrow = new Image(group.collapsed ? Icon.rightOpenSmall : Icon.downOpenSmall);
        arrow.setColor(Pal.accent);
        head.add(arrow).size(11f).padRight(3f);
        String text = PanelLayout.groupTitle(group) + (group.collapsed ? "  [lightgray]" + size : "");
        Label title = head.add(text).padRight(5f).get();
        title.setColor(Pal.accent);
        title.setFontScale(0.66f * PanelLayout.titleScale());
        head.image(Tex.whiteui).color(groupLine).height(1f).growX();
        head.clicked(() -> {
            PanelLayout.setCollapsed(group.id, !group.collapsed);
            Core.app.post(() -> showPanelTab(panelTab));
        });
        head.addListener(new arc.scene.event.HandCursorListener());
        box.add(head).growX().minWidth(70f).padBottom(2f).row();
    }

    /** Lays buttons on a grid, keeping cells the player placed by hand. */
    private void layoutGrid(Table grid, Seq<String> ids, int columns){
        Seq<PanelLayout.Slot> slots = PanelLayout.resolve(ids, columns);
        if(slots.isEmpty()) return;
        int minR = Integer.MAX_VALUE, minC = Integer.MAX_VALUE, rawMaxC = 0, rawMaxR = 0;
        for(PanelLayout.Slot slot : slots){
            rawMaxC = Math.max(rawMaxC, slot.col);
            rawMaxR = Math.max(rawMaxR, slot.row);
            minR = Math.min(minR, slot.row);
            minC = Math.min(minC, slot.col);
        }
        int rowCount = rawMaxR - minR, maxC = rawMaxC - minC;
        QueuedTile[][] placed = new QueuedTile[rowCount + 1][maxC + 1];
        for(PanelLayout.Slot slot : slots){
            int col = slot.col - minC, row = slot.row - minR;
            if(col < 0 || row < 0 || col > maxC || row > rowCount) continue;
            placed[row][col] = registry.get(slot.id);
        }
        float hole = tileIcon(1f);
        for(int r = 0; r <= rowCount; r++){
            int last = -1;
            for(int c = 0; c <= maxC; c++) if(placed[r][c] != null) last = c;
            if(last < 0) continue;
            if(grid.hasChildren()) grid.row();
            for(int c = 0; c <= last; c++){
                QueuedTile queued = placed[r][c];
                if(queued == null){
                    grid.add().size(tileWidth(hole), tileHeight(hole)).pad(1.5f);
                    continue;
                }
                addTile(grid, queued);
            }
        }
    }

    /** One panel button: icon, caption, state stripe, right-click dot and an optional live counter. */
    private void addTile(Table grid, QueuedTile queued){
        float iconSize = tileIcon(PanelLayout.scale(queued.id));
        boolean action = queued.state == null;
        Drawable custom = PanelLayout.icon(PanelLayout.iconKey(queued.id));
        String customCaption = PanelLayout.caption(queued.id);
        Button button = new Button(action ? panelActionStyle : panelBtnStyle);
        button.top().margin(0f);
        Image image = new Image(custom != null ? custom : queued.drawable);
        image.setScaling(Scaling.fit);
        Stack iconStack = new Stack();
        Table iconWrap = new Table();
        iconWrap.add(image).size(iconSize).grow();
        iconStack.add(iconWrap);
        button.add(iconStack).size(iconSize).padTop(3f).row();
        Label caption = button.add(customCaption != null ? customCaption : queued.label).growX().pad(1f, 2f, 0f, 2f).get();
        caption.setFontScale(0.55f * PanelLayout.titleScale());
        caption.setWrap(true);
        caption.setAlignment(Align.center);
        button.add().growY().row();
        Image stripe = new Image(Tex.whiteui);
        button.add(stripe).height(2f).growX();

        //corner marks sit over the whole button, not just the icon
        Stack whole = new Stack();
        whole.add(button);
        if(queued.right != null){
            Table mark = new Table();
            mark.top().right();
            mark.touchable = Touchable.disabled;
            Image dot = new Image(Tex.whiteui);
            dot.setColor(Pal.accent);
            mark.add(dot).size(4f).pad(3f);
            whole.add(mark);
        }
        Prov<String> badgeText = queued.badge;
        if(badgeText != null){
            Table corner = new Table();
            corner.top().left();
            corner.touchable = Touchable.disabled;
            Table chip = new Table(panelBadgeBg);
            chip.margin(0f, 3f, 1f, 3f);
            Label value = new Label(() -> {
                String text = badgeText.get();
                return text == null ? "" : text;
            });
            value.setFontScale(0.48f * PanelLayout.titleScale());
            value.setColor(Pal.accent);
            chip.add(value);
            chip.visible(() -> {
                String text = badgeText.get();
                return text != null && !text.isEmpty();
            });
            corner.add(chip).pad(2f);
            whole.add(corner);
        }

        Boolp state = queued.state;
        Runnable left = queued.left;
        Runnable right = queued.right;
        String baseTip = queued.tip != null && queued.tip.startsWith("@") ? bundle.get(queued.tip.substring(1)) : queued.tip;
        String rightText = queued.rightTip == null ? null : bundle.get(queued.rightTip);
        String boundKey = PanelLayout.keyOf(queued.id);
        button.addListener(new Tooltip(t -> {
            t.background(Styles.black8).margin(6f);
            t.add(baseTip == null ? "" : baseTip).wrap().width(280f).left();
            if(state != null){
                t.row();
                t.label(() -> state.get() ? "[accent]" + bundle.get("client.morj.on") : "[lightgray]" + bundle.get("client.morj.off")).left().padTop(3f);
            }
            if(rightText != null){
                t.row();
                t.add("[lightgray]" + rightText).wrap().width(280f).left().padTop(2f);
            }
            if(boundKey != null){
                t.row();
                t.add("[gray]" + bundle.get("client.morj.panel.key") + ": [white]" + keyName(boundKey)).left().padTop(2f);
            }
        }));
        button.update(() -> {
            boolean on = state != null && state.get();
            button.setChecked(on);
            image.setColor(state == null || on ? Color.white : panelDim);
            if(state == null){
                caption.setColor(Color.lightGray);
                stripe.setColor(groupLine);
            }else if(on){
                caption.setColor(Color.white);
                stripe.setColor(Pal.accent);
            }else{
                caption.setColor(Color.gray);
                stripe.setColor(Color.clear);
            }
        });
        button.addListener(new InputListener(){
            @Override public boolean touchDown(InputEvent e, float x, float y, int pointer, KeyCode key){
                if(helpMode && (key == KeyCode.mouseLeft || key == KeyCode.mouseRight)){
                    ui.showInfo(describe(queued));
                    return true;
                }
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
        grid.add(whole).size(tileWidth(iconSize), tileHeight(iconSize)).pad(1.5f);
    }

    private static String keyName(String raw){
        try{
            return KeyCode.valueOf(raw).getName();
        }catch(IllegalArgumentException ignored){
            return raw;
        }
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
