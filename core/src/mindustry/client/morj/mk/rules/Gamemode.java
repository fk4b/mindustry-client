package mindustry.client.morj.mk.rules;

import arc.struct.OrderedMap;
import arc.struct.Seq;
import arc.util.Nullable;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import mindustry.client.morj.mk.ui.RulesWrite;
import mindustry.game.Rules;
import mindustry.game.Team;

public abstract class Gamemode {
    @RequiredArgsConstructor(access = AccessLevel.PROTECTED)
    public abstract class Impl {
        protected final RulesContext rc;

        public final Gamemode factory() { return Gamemode.this; }

        /**
         * A unique name for this gamemode.
         * <p>
         * This is an internal name, for localized name use {@link arc.Core#bundle}.
         * <p>
         * Internally this calls {@link Gamemode#name}
         */
        public final String name() { return factory().name(); }

        /**
         * Set default rules.
         * <p>
         * This function <i>should</i> be called after setting this gamemode, but
         * it's not absolutely necessary. Generally, this being called <b>must</b>
         * be the default behavior, <i>maybe</i> with an optional opt-out.
         */
        public final void setRules() {
            // Reset potentially modified rules to default.

            final Rules rules = rc.rules;

            for (Team team : Team.all) {
                Rules.TeamRule t = rules.teams.get(team);
                t.cheat = false;
            }
            rules.modeName = "";
            rules.onlyDepositCore = false;
            rules.coreIncinerates = false;
            rules.attackMode = false;
            rules.possessionAllowed = true;
            rules.enemyCoreBuildRadius = 30f;
            rules.schematicsAllowed = true;
            rules.loadout.clear();
            rules.bannedBlocks.clear();
            rules.revealedBlocks.clear();
            rules.hideBannedBlocks = false;
            rules.infiniteResources = false;
            rules.planet.applyRules(rules, true);

            // Content patches apparently must be set separately.

            _setRules();
        }

        /**
         * Remove this gamemode's tags.
         * <p>
         * Should be called before swapping this instance with another.
         */
        abstract void remove();
        /**
         * Obtain the default content patch.
         * <p>
         * Should be called before swapping this instance with another.
         */
        public @Nullable String builtInContentPatch() { return null; }
        /**
         * Set default custom rules for this gamemode.
         * <p>
         * This function is never called directly, instead so via {@link Impl#setRules}.
         */
        protected abstract void _setRules();
        /**
         * Add gamemode rules to a settings dialog.
         */
        public void writeGamemodeRules(RulesWrite write) {}
        public void writeTeamRules(RulesWrite write, Team team) {}
        public void drawEditorGuides() {}
        public void onStart() {}
        public void editingResumed() {}
        public void dataFixer() {}
    }

    public static Gamemode UNKNOWN = new Gamemode() {
        @Override
        public String name() {
            return "unknown";
        }

        @Override
        Impl create(RulesContext rc) {
            return new Impl(rc) {
                @Override void remove() {}
                @Override
                protected void _setRules() {}
            };
        }
    };

    /**
     * A unique name for this gamemode.
     * <p>
     * This is an internal name, for localized name use {@link arc.Core#bundle}.
     */
    public abstract String name();
    /**
     * Create an instance of this gamemode.
     * <p>
     * This method is used by {@link MRules} and is not intended to be called
     * directly.
     */
    abstract Impl create(RulesContext rc);

    /**
     * Whether it is safe to keep vanilla rules.
     * <p>
     * For a vanilla gamemode, {@link Impl#setRules()} will never be called.
     */
    public boolean vanillaGamemode = false;
    /**
     * Whether the gamemode appears in gamemode list.
     */
    public boolean visible = true;
    private static final OrderedMap<String, Gamemode> factories = new OrderedMap<>();

    public static void addGamemode(Gamemode factory) {
        factories.put(factory.name(), factory);
    }

    private static boolean initialized = false;
    public static void init() {
        if (initialized) throw new IllegalStateException("Already initialized");
        initialized = true;

        addGamemode(Gamemodes.attack);
        addGamemode(Gamemodes.hexed);
        addGamemode(Gamemodes.hub);
        addGamemode(Gamemodes.forts);
        addGamemode(Gamemodes.castle);
        addGamemode(Gamemodes.pvp);
        addGamemode(Gamemodes.spvp);
        addGamemode(Gamemodes.survival);
        addGamemode(Gamemodes.miniPvP);
    }
    public static @Nullable Gamemode forName(String name) {
        return factories.get(name);
    }

    public static Seq<String> keys() {
        return factories.orderedKeys();
    }
}
