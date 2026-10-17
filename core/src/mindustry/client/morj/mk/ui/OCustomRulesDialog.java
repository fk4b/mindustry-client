package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.scene.event.EventListener;
import arc.scene.event.VisibilityListener;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextField;
import arc.scene.ui.layout.Cell;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Reflect;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.Util;
import mindustry.client.morj.mk.rules.Gamemode;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.ctype.ContentType;
import mindustry.editor.BannedContentDialog;
import mindustry.editor.MapEditorDialog;
import mindustry.editor.MapInfoDialog;
import mindustry.game.Rules;
import mindustry.gen.Icon;
import mindustry.type.ItemStack;
import mindustry.type.Planet;
import mindustry.type.UnitType;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.CustomRulesDialog;
import mindustry.world.Block;

public class OCustomRulesDialog extends CustomRulesDialog {

    private BannedContentDialog<Block> bannedBlocks = new BannedContentDialog<>("@bannedblocks", ContentType.block, b -> !b.name.startsWith("tmp-"));
    private RevealedContentDialog<Block> revealedBlocks = new RevealedContentDialog<>("@revealedblocks", ContentType.block, b -> !b.name.startsWith("tmp-"));
    private BannedContentDialog<UnitType> bannedUnits = new BannedContentDialog<>("@bannedunits", ContentType.unit, u -> true);
    private EnvDialog envDialog = new EnvDialog();
    private PlanetBackgroundDialog backgroundDialog = new PlanetBackgroundDialog();
    private Table main;
    private RulesWrite writeRoot;

    public OCustomRulesDialog() {
        super();

        {
            Seq<EventListener> listeners = getListeners();
            for (int i = listeners.size - 1; i >= 0; i--) {
                EventListener listener = listeners.get(i);
                if (!(listener instanceof VisibilityListener)) continue;
                removeListener(listener);
                break;
            }
        }

        shown(this::setup);

        Reflect.set(CustomRulesDialog.class, this, "loadoutDialog", null);
        Reflect.set(CustomRulesDialog.class, this, "bannedBlocks", null);
        Reflect.set(CustomRulesDialog.class, this, "bannedUnits", null);
    }

    public static void inject() {
        MapInfoDialog infoDialog = Reflect.get(MapEditorDialog.class, Vars.ui.editor, "infoDialog");
        CustomRulesDialog customRulesDialog = MVars.customRulesDialog = new OCustomRulesDialog();
        Reflect.set(infoDialog, "ruleInfo", customRulesDialog);
    }

    void setup() {
        cont.clear();
        cont.table(t -> {
            t.add("@search").padRight(10);
            TextField field = t.field(ruleSearch, text -> {
                ruleSearch = text.trim().replaceAll(" +", " ").toLowerCase();
                build();
            }).width(200f).pad(8).get();
            field.setCursorPosition(ruleSearch.length());
            Core.scene.setKeyboardFocus(field);
            t.button(Icon.cancel, Styles.emptyi, () -> {
                ruleSearch = "";
                build();
            }).padLeft(10f).size(35f);
        }).fillX().row();
        Cell<ScrollPane> paneCell = cont.pane(m -> main = m).fillX();
        writeRoot = new RulesWrite(main, "");

        build();

        paneCell.scrollX(main.getPrefWidth() + 40f > Core.graphics.getWidth());
    }

    void build() {
        writeRoot.clear(ruleSearch);
        main.add(new Table()).width(500f).row();
        RulesWrite write;

        final Rules rules = Vars.state.rules;

        write = writeRoot.category("waves");
        write.b("rules.waves", () -> rules.waves, b -> rules.waves = b);
        write.b("rules.wavesending", () -> rules.waveSending, b -> rules.waveSending = b).enabled(() -> rules.waves);
        write.b("rules.wavetimer", () -> rules.waveTimer, b -> rules.waveTimer = b).enabled(() -> rules.waves);
        write.b("rules.waitForWaveToEnd", () -> rules.waitEnemies, b -> rules.waitEnemies = b).enabled(() -> rules.waves && rules.waveTimer);
        write.b("rules.randomwaveai", () -> rules.randomWaveAI, b -> rules.randomWaveAI = b).enabled(() -> rules.waves);
        write.b("rules.wavespawnatcores", () -> rules.wavesSpawnAtCores, b -> rules.wavesSpawnAtCores = b).enabled(() -> rules.waves);
        write.b("rules.airUseSpawns", () -> rules.airUseSpawns, b -> rules.airUseSpawns = b).enabled(() -> rules.waves);
        write.i("rules.wavelimit", () -> rules.winWave, i -> rules.winWave = i).min(0).enabled(() -> rules.waves);
        write.f("rules.wavespacing", () -> rules.waveSpacing / 60, f -> rules.waveSpacing = f * 60).min(0).enabled(() -> rules.waves && rules.waveTimer);
        write.f("rules.initialwavespacing", () -> rules.initialWaveSpacing / 60, f -> rules.initialWaveSpacing = f * 60).min(1).enabled(() -> rules.waves && rules.waveTimer);
        write.f("rules.dropzoneradius", () -> rules.dropZoneRadius / Vars.tilesize, f -> rules.dropZoneRadius = f * Vars.tilesize).min(0).enabled(() -> rules.waves);

        write = writeRoot.category("resourcesbuilding");
        write.b("rules.alloweditworldprocessors", () -> rules.allowEditWorldProcessors, b -> rules.allowEditWorldProcessors = b);
        write.b("rules.infiniteresources", () -> rules.infiniteResources, b -> rules.infiniteResources = b);
        write.b("rules.onlydepositcore", () -> rules.onlyDepositCore, b -> rules.onlyDepositCore = b);
        write.b("rules.derelictrepair", () -> rules.derelictRepair, b -> rules.derelictRepair = b);
        write.b("rules.reactorexplosions", () -> rules.reactorExplosions, b -> rules.reactorExplosions = b);
        write.b("rules.schematic", () -> rules.schematicsAllowed, b -> rules.schematicsAllowed = b);
        write.b("rules.coreincinerates", () -> rules.coreIncinerates, b -> rules.coreIncinerates = b);
        write.b("rules.cleanupdeadteams", () -> rules.cleanupDeadTeams, b -> rules.cleanupDeadTeams = b).enabled(() -> rules.pvp);
        write.b("rules.disableworldprocessors", () -> rules.disableWorldProcessors, b -> rules.disableWorldProcessors = b);
        write.f("rules.buildcostmultiplier", () -> rules.buildCostMultiplier, f -> rules.buildCostMultiplier = f).enabled(() -> !rules.infiniteResources);
        write.f("rules.buildspeedmultiplier", () -> rules.buildSpeedMultiplier, f -> rules.buildSpeedMultiplier = f).range(0.001f, 50f);
        write.f("rules.deconstructrefundmultiplier", () -> rules.deconstructRefundMultiplier, f -> rules.deconstructRefundMultiplier = f).range(0f, 1f).enabled(() -> !rules.infiniteResources);
        write.f("rules.blockhealthmultiplier", () -> rules.blockHealthMultiplier, f -> rules.blockHealthMultiplier = f);
        write.f("rules.blockdamagemultiplier", () -> rules.blockDamageMultiplier, f -> rules.blockDamageMultiplier = f);
        write.loadout("configure", 999999, () -> rules.loadout, xx -> true,
                () -> rules.loadout.clear().add(new ItemStack(Items.copper, 100)), () -> {
                }, () -> {
                });
        write.button("bannedblocks", () -> bannedBlocks.show(rules.bannedBlocks));
        write.b("rules.hidebannedblocks", () -> rules.hideBannedBlocks, b -> rules.hideBannedBlocks = b);
        write.b("bannedblocks.whitelist", () -> rules.blockWhitelist, b -> rules.blockWhitelist = b);
        write.button("revealedblocks", () -> revealedBlocks.show(rules.revealedBlocks));

        write = writeRoot.category("unit");

        write.b("rules.instantbuild", () -> rules.instantBuild, b -> rules.instantBuild = b);
        write.b("rules.possessionallowed", () -> rules.possessionAllowed, b -> rules.possessionAllowed = b);
        write.b("rules.unitcapvariable", () -> rules.unitCapVariable, b -> rules.unitCapVariable = b);
        write.b("rules.unitpayloadsexplode", () -> rules.unitPayloadsExplode, b -> rules.unitPayloadsExplode = b);
        write.i("rules.unitcap", () -> rules.unitCap, b -> rules.unitCap = b).range(-999, 999);
        write.f("rules.unitdamagemultiplier", () -> rules.unitDamageMultiplier, f -> rules.unitDamageMultiplier = f);
        write.f("rules.unitcrashdamagemultiplier", () -> rules.unitCrashDamageMultiplier, f -> rules.unitCrashDamageMultiplier = f);
        write.f("rules.unitminespeedmultiplier", () -> rules.unitMineSpeedMultiplier, f -> rules.unitMineSpeedMultiplier = f);
        write.f("rules.unitbuildspeedmultiplier", () -> rules.unitBuildSpeedMultiplier, f -> rules.unitBuildSpeedMultiplier = f);
        write.f("rules.unitcostmultiplier", () -> rules.unitCostMultiplier, f -> rules.unitCostMultiplier = f);
        write.f("rules.unithealthmultiplier", () -> rules.unitHealthMultiplier, f -> rules.unitHealthMultiplier = f);
        write.button("bannedunits", () -> bannedUnits.show(rules.bannedUnits));
        write.b("bannedunits.whitelist", () -> rules.unitWhitelist, b -> rules.unitWhitelist = b);

        write = writeRoot.category("enemy");

        write.b("rules.attack", () -> rules.attackMode, b -> rules.attackMode = b);
        write.b("rules.corecapture", () -> rules.coreCapture, b -> rules.coreCapture = b);
        write.b("rules.placerangecheck", () -> rules.placeRangeCheck, b -> rules.placeRangeCheck = b);
        write.b("rules.polygoncoreprotection", () -> rules.polygonCoreProtection, b -> rules.polygonCoreProtection = b);
        write.f("rules.enemycorebuildradius", () -> rules.enemyCoreBuildRadius / Vars.tilesize, f -> rules.enemyCoreBuildRadius = f * Vars.tilesize).enabled(() -> !rules.polygonCoreProtection);

        write = writeRoot.category("environment");

        write.b("rules.explosions", () -> rules.damageExplosions, b -> rules.damageExplosions = b);
        write.b("rules.fire", () -> rules.fire, b -> rules.fire = b);
        write.b("rules.fog", () -> rules.fog, b -> rules.fog = b);
        write.b("rules.staticfog", () -> rules.staticFog, b -> rules.staticFog = b);
        write.b("rules.lighting", () -> rules.lighting, b -> rules.lighting = b);
        write.spacer();

        write.b("rules.limitarea", () -> rules.limitMapArea, b -> rules.limitMapArea = b).enabled(() -> !Vars.state.isGame());
        write.i("rules.limitarea", () -> rules.limitX, i -> rules.limitX = i).label("x").range(0, 10000).enabled(() -> !Vars.state.isGame());
        write.i("rules.limitarea", () -> rules.limitY, i -> rules.limitY = i).label("y").range(0, 10000).enabled(() -> !Vars.state.isGame());
        write.i("rules.limitarea", () -> rules.limitWidth, i -> rules.limitWidth = i).label("w").range(0, 10000).enabled(() -> !Vars.state.isGame());
        write.i("rules.limitarea", () -> rules.limitHeight, i -> rules.limitHeight = i).label("h").range(0, 10000).enabled(() -> !Vars.state.isGame());
        write.b("rules.borderdarkness", () -> rules.borderDarkness, b -> rules.borderDarkness = b);
        write.spacer();

        write.f("rules.solarmultiplier", () -> rules.solarMultiplier, f -> rules.solarMultiplier = f);
        write.color("rules.ambientlight", () -> rules.ambientLight, rules.ambientLight::set);
        write.color("rules.cloudcolor", () -> rules.cloudColor, rules.cloudColor::set);
        write.spacer();

        write.button("rules.env", () -> envDialog.show());
        write.button("rules.weather", () -> Reflect.invoke(CustomRulesDialog.class, this, "weatherDialog", Util.noargs));
        write.button("rules.planetbackground", () -> backgroundDialog.show());

        write = writeRoot.category("planet");
        write.selection("rules.title.planet", addItem -> {
            for (Planet planet : Vars.content.planets()) {
                if (planet == Planets.sun) continue;
                addItem.add("planet."+planet.name+".name", planet);
            }
            addItem.add("rules.anyenv", Planets.sun);
        }, value -> {
            if (value == Planets.sun) {
                rules.env = Vars.defaultEnv;
            }
            rules.planet = value;
            if (!Core.input.shift()) value.applyRules(rules, true);
        }, rules.planet);

        write = writeRoot.category("teams");
        if (showRuleEditRule)
            write.b("rules.allowedit", () -> rules.allowEditRules, b -> rules.allowEditRules = b);
        write.team("rules.playerteam", t -> rules.defaultTeam = t, () -> rules.defaultTeam);
        write.team("rules.enemyteam", t -> rules.waveTeam = t, () -> rules.waveTeam);
        write.teams("teams", (team, wteam) -> {
            Rules.TeamRule teams = rules.teams.get(team);

            wteam.f("rules.blockhealthmultiplier", () -> teams.blockHealthMultiplier, f -> teams.blockHealthMultiplier = f);
            wteam.f("rules.blockdamagemultiplier", () -> teams.blockDamageMultiplier, f -> teams.blockDamageMultiplier = f);
            wteam.spacer();

            wteam.b("rules.cheat", () -> teams.cheat, b -> teams.cheat = b);
            wteam.b("rules.fillitems", () -> teams.fillItems, b -> teams.fillItems = b);
            wteam.b("rules.protectcores", () -> teams.protectCores, b -> teams.protectCores = b);
            wteam.spacer();

            wteam.b("rules.rtsai", () -> teams.rtsAi, b -> teams.rtsAi = b).enabled(() -> team != rules.defaultTeam);
            wteam.i("rules.rtsminsquadsize", () -> teams.rtsMinSquad, i -> teams.rtsMinSquad = i).range(0, 1000).enabled(() -> team != rules.defaultTeam && teams.rtsAi);
            wteam.i("rules.rtsmaxsquadsize", () -> teams.rtsMaxSquad, i -> teams.rtsMaxSquad = i).range(1, 1000).enabled(() -> team != rules.defaultTeam && teams.rtsAi);
            wteam.f("rules.rtsminattackweight", () -> teams.rtsMinWeight, f -> teams.rtsMinWeight = f).enabled(() -> team != rules.defaultTeam && teams.rtsAi);
            wteam.spacer();

            // Have fun, erekir gamers!
            wteam.b("rules.buildai", () -> teams.buildAi, b -> teams.buildAi = b);
            wteam.f("rules.buildaitier", () -> teams.buildAiTier, f -> teams.buildAiTier = f).enabled(() -> teams.buildAi);
            wteam.b("rules.prebuildai", () -> teams.prebuildAi, b -> teams.prebuildAi = b);
            wteam.spacer();

            wteam.f("rules.extracorebuildradius", () -> teams.extraCoreBuildRadius / Vars.tilesize, f -> teams.extraCoreBuildRadius = f * Vars.tilesize).enabled(() -> !rules.polygonCoreProtection);
            wteam.b("rules.infiniteresources", () -> teams.infiniteResources, b -> teams.infiniteResources = b);
            wteam.f("rules.buildspeedmultiplier", () -> teams.buildSpeedMultiplier, f -> teams.buildSpeedMultiplier = f).min(0.001f);
            wteam.spacer();

            wteam.f("rules.unitdamagemultiplier", () -> teams.unitDamageMultiplier, f -> teams.unitDamageMultiplier = f);
            wteam.f("rules.unitcrashdamagemultiplier", () -> teams.unitCrashDamageMultiplier, f -> teams.unitCrashDamageMultiplier = f);
            wteam.f("rules.unitminespeedmultiplier", () -> teams.unitMineSpeedMultiplier, f -> teams.unitMineSpeedMultiplier = f);
            wteam.f("rules.unitbuildspeedmultiplier", () -> teams.unitBuildSpeedMultiplier, f -> teams.unitBuildSpeedMultiplier = f);
            wteam.f("rules.unitcostmultiplier", () -> teams.unitCostMultiplier, f -> teams.unitCostMultiplier = f);
            wteam.f("rules.unithealthmultiplier", () -> teams.unitHealthMultiplier, f -> teams.unitHealthMultiplier = f);
        }, team -> team.data().cores.size != 0);

        write = writeRoot.category("mindurka");
        MVars.rules.writeRules(write);

        write = writeRoot.category("advanced");
        write.b("rules.cangameover", () -> rules.canGameOver, b -> rules.canGameOver = b);
        write.b("rules.editor", () -> rules.editor, b -> rules.editor = b);
        write.b("rules.alloweditrules", () -> rules.allowEditRules, b -> rules.allowEditRules = b);
        write.b("rules.alloweditworldprocessors", () -> rules.allowEditWorldProcessors, b -> rules.allowEditWorldProcessors = b);
        write.b("rules.allowenvironmentdeconstruct", () -> rules.allowEnvironmentDeconstruct, b -> rules.allowEnvironmentDeconstruct = b);
        write.b("rules.allowlogicdata", () -> rules.allowLogicData, b -> rules.allowLogicData = b);
    }
}
