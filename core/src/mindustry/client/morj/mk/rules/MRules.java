package mindustry.client.morj.mk.rules;

import arc.Core;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Nullable;
import lombok.AllArgsConstructor;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.ui.RulesWrite;
import mindustry.client.morj.mk.util.SerializerSkip;
import mindustry.Vars;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.mod.DataPatcher;

// What I've learned:
// - Fuck saving object allocations.
// - Enforce strong variants.
// - This could literally be a JavaScript file and be just as good in terms of performance, none of this is
//   performance critical.
// - `save()` and `sync()` are useless if point 2 is true. `sync()` is your constructor, and `save()` are your methods.
//   In fact, this actually saves performance cuz we aren't re-saving this entire disaster.

public class MRules {
    public static final String PREFIX = "mdrk";
    public static final String FORMAT = PREFIX+".format";
    // Used
    public static final String PATCH = PREFIX+".patch";
    public static final String FORMAT_VER = "1";
    public static final String GAMEMODE = PREFIX+".gamemode";
    public static final String GAMEMODE_LEGACY = "mindurkaGamemode"; // Does not use `mdrk.*` convention as it's a legacy key.
                                                                     // But it's a great legacy, so we depend on it.
    public static final String OVERDRIVE_IGNORES_CHEAT = PREFIX+".overdriveIgnoresCheat";
    public static final String TEAM_PREFIX = PREFIX+".team";
    public static final String SERVICE_TEAM_HEAD = TEAM_PREFIX+".serviceTeam.";
    public static final String PVP_TEAM_DEATH_REQUIRED_HEAD = TEAM_PREFIX+".pvpTeamDeathRequired.";

    private final Rules rules;
    @SerializerSkip
    private final int mapWidth, mapHeight;
    public final TeamRules[] teams = new TeamRules[256];
    @SerializerSkip
    public final int originalPatchVer;

    private Runnable refreshTeamRules = null;
    public void refreshTeamRules() {
        refreshTeamRules.run();
    }

    public static class TeamRules {
        public final Team team;
        @SerializerSkip
        private final RulesContext rc;

        public TeamRules(RulesContext rc, Team team) {
            this.team = team;
            this.rc = rc;

            reset();
        }

        private boolean serviceTeam;
        public TeamRules serviceTeam(boolean value) {
            serviceTeam = value;
            try (TagWrite write = TagWrite.of(rc.rules)) {
                write.w(SERVICE_TEAM_HEAD+team.id, value);
            }
            return this;
        }
        public boolean serviceTeam() {
            return serviceTeam;
        }

        private boolean pvpTeamDeathRequired;
        public TeamRules pvpTeamDeathRequired(boolean value) {
            pvpTeamDeathRequired = value;
            try (TagWrite write = TagWrite.of(rc.rules)) {
                write.w(PVP_TEAM_DEATH_REQUIRED_HEAD+team.id, value);
            }
            return this;
        }
        public boolean pvpTeamDeathRequired() {
            return pvpTeamDeathRequired;
        }

        public void read() {
            try (TagRead read = TagRead.of(rc.rules)) {
                serviceTeam = read.r(SERVICE_TEAM_HEAD+team.id, team == Team.derelict);
                pvpTeamDeathRequired = read.r(PVP_TEAM_DEATH_REQUIRED_HEAD+team.id, team != Team.derelict);
            }
        }

        public void reset() {
            serviceTeam = team == Team.derelict;
            pvpTeamDeathRequired = team != Team.derelict;
        }
    }

    public MRules(Rules rules) { this(rules, Vars.world.width(), Vars.world.height()); }
    public MRules(Rules rules, int mapWidth, int mapHeight) {
        this.rules = rules;
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        RulesContext rc = newRulesContext();
        for (Team team : Team.all) teams[team.id] = new TeamRules(rc, team);

        {
            @Nullable String format = rules.tags.get(FORMAT);
            if (format == null) {
                legacyServer = true;
                originalPatchVer = 0;
                return;
            }
            if (!format.equals(FORMAT_VER)) {
                Vars.ui.showErrorMessage("MindurkaCompat: Invalid format verison " + FORMAT_VER);
                originalPatchVer = 0;
                return;
            }
        }

        {
            @Nullable String gamemodeName = rules.tags.get(GAMEMODE);
            legacyServer = false;
            if (gamemodeName == null) {
                gamemodeName = rules.tags.get(GAMEMODE_LEGACY);
                rules.tags.put(GAMEMODE, gamemodeName);
            }
            if (gamemodeName == null) {
                legacyServer = true;
                Vars.ui.showErrorMessage("MindurkaCompat: Format version 1 requires gamemode to be specified.");
                originalPatchVer = 0;
                return;
            }
            try (TagRead read = TagRead.of(rc.rules)) { originalPatchVer = read.r(PATCH, 0); }
            @Nullable Gamemode factory = Gamemode.forName(gamemodeName);
            if (factory == null) {
                Log.err("Unknown gamemode '" + gamemodeName + "', some features may not be supported.");
                gamemode = Gamemode.UNKNOWN.create(rc);
            }
            else {
                gamemode = factory.create(rc);
            }
        }

        try (TagRead read = TagRead.of(rc.rules)) {
            overdriveIgnoresCheat = read.r(OVERDRIVE_IGNORES_CHEAT, false);
        }

        for (TeamRules team : teams) team.read();
    }

    private RulesContext newRulesContext() {
        return new RulesContext(this, rules, mapWidth, mapHeight);
    }

    private void remove() {
        rules.tags.each((key, value) -> {
            if (key.startsWith(PREFIX+".")) rules.tags.remove(key);
        });
        if (gamemode != null) {
            gamemode.remove();
            gamemode = null;
        }
        for (TeamRules team : teams) team.reset();
    }

    private boolean legacyServer = false;
    public boolean legacyServer() { return legacyServer; }

    private @Nullable Gamemode.Impl gamemode;
    @SuppressWarnings("unchecked")
    public <G extends Gamemode.Impl> @Nullable G gamemode() { return (G) gamemode; }
    public @Nullable Gamemode gamemodeFactory() { return gamemode == null ? null : gamemode.factory(); }
    public MRules gamemode(@Nullable Gamemode newValue) {
        a: {
            if (Vars.state.patcher.patches.size == 0) break a;
            DataPatcher.PatchSet patches = Vars.state.patcher.patches.first();
            if (!patches.name.equals("Mindurka Default Patch")) break a;
            Vars.state.patcher.patches.remove(0);
        }

        if (gamemode != null && (gamemodeFactory() != newValue || !Core.input.shift())) gamemode.remove();
        if (newValue == null) remove();
        else {
            if (gamemodeFactory() == null || gamemode.factory() != newValue) gamemode = newValue.create(newRulesContext());
            rules.tags.put(FORMAT, FORMAT_VER);
            rules.tags.put(GAMEMODE, newValue.name());
            rules.tags.put(GAMEMODE_LEGACY, newValue.name());
            rules.tags.put(PATCH, MVars.version + "");
            if (!Core.input.shift() && !gamemode.factory().vanillaGamemode) gamemode.setRules();
            gamemode.dataFixer();
        }
        if (MVars.editorDialog.isShown()) MVars.editorDialog.refreshTools();

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

        return this;
    }

    public void writeRules(RulesWrite write) {
        if (Core.settings.getBool("mindurka.devfeatures", false)) {
            write.button("rules.mindurka.virtualfilesystem", MVars.ui.virtualFs::show);
        }
        write.b("rules.mindurka.overdriveignorescheat", this::overdriveIgnoresCheat, this::overdriveIgnoresCheat);

        {
            write.selection("rules.title.mindurka", addItem -> {
                addItem.add("mindurka.gamemode.none", null);
                for (String gamemodeName : Gamemode.keys()) {
                    Gamemode gamemode = Gamemode.forName(gamemodeName);
                    if (!gamemode.visible) continue;
                    addItem.add("mindurka.gamemode." + gamemodeName, gamemode);
                }
            }, value -> {
                MVars.rules.gamemode(value);
                refreshTeamRules.run();
            }, MVars.rules.gamemodeFactory());

            {
                RulesWrite extraWrite = write.table();
                refreshTeamRules = () -> {
                    extraWrite.clear();
                    @Nullable Gamemode.Impl gamemode = MVars.rules.gamemode();
                    if (gamemode != null) {
                        gamemode.writeGamemodeRules(extraWrite);
                        extraWrite.teams("rules.mindurka.teams", (team, rulesWrite) -> {
                            TeamRules teamRules = teams[team.id];
                            rulesWrite.b("rules.mindurka.serviceteam", teamRules::serviceTeam, teamRules::serviceTeam);
                            rulesWrite.b("rules.mindurka.pvpteamdeathrequired", teamRules::pvpTeamDeathRequired, teamRules::pvpTeamDeathRequired);
                            gamemode.writeTeamRules(rulesWrite, team);
                        },team -> team.data().cores.size != 0);
                    }
                };
                refreshTeamRules.run();
            }
        }
    }

    private boolean overdriveIgnoresCheat;
    public boolean overdriveIgnoresCheat() {
        if (gamemode == null) return false;
        return overdriveIgnoresCheat;
    }
    public MRules overdriveIgnoresCheat(boolean value) {
        if (gamemode == null) return this;
        overdriveIgnoresCheat = value;
        try (TagWrite write = TagWrite.of(rules)) { write.w(OVERDRIVE_IGNORES_CHEAT, value); }
        return this;
    }
}
