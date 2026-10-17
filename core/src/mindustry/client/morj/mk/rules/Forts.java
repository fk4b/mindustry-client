package mindustry.client.morj.mk.rules;

import arc.struct.Bits;
import arc.struct.ObjectIntMap;
import arc.struct.Seq;
import arc.util.io.Streams;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.Util;
import mindustry.client.morj.mk.ui.RulesWrite;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.content.UnitTypes;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.UnitType;
import mindustry.world.Block;

import java.io.IOException;
import java.util.Objects;

public class Forts extends Gamemode {
    public static final String PREFIX = MRules.PREFIX+".forts";
    public static final String NAME = "forts";
    public static final String ENABLE_1VA = PREFIX+".enable_1va";
    public static final String ENABLE_VNW = PREFIX+".enable_wnv";
    public static final String EXPANSION_BLOCK = PREFIX+".expansion_block";
    public static final String MIN_HEALTH = PREFIX+".min_health";
    public static final String APPLY_DAMAGE_EFFECTS_HEAD = PREFIX+".apply_damage_effects.";
    public static final String PASSIVE_ITEMS_HEAD = PREFIX+".passive_items.";

    public static final String THOR_PREFIX = PREFIX+".thor";
    public static final String THOR_ENABLED = THOR_PREFIX+".enabled";
    public static final String THOR_DELAY = THOR_PREFIX+".delay";
    public static final String THOR_COOLDOWN = THOR_PREFIX+".cooldown";
    public static final String THOR_DAMAGE_MULTIPLIER = THOR_PREFIX+".damage_multiplier";
    public static final String THOR_RADIUS_MULTIPLIER = THOR_PREFIX+".radius_multiplier";
    public static final String THOR_BLOCK = THOR_PREFIX+".block";

    public static final String IMPACT_PREFIX = PREFIX+".impact";
    public static final String IMPACT_ENABLED = IMPACT_PREFIX+".enabled";
    public static final String IMPACT_DELAY = IMPACT_PREFIX+".delay";
    public static final String IMPACT_COOLDOWN = IMPACT_PREFIX+".cooldown";
    public static final String IMPACT_DURATION = IMPACT_PREFIX+".duration";
    public static final String IMPACT_EXPLOSION_DAMAGE = IMPACT_PREFIX+".explosion_damage";
    public static final String IMPACT_EXPLOSION_RADIUS = IMPACT_PREFIX+".explosion_radius";
    public static final String IMPACT_INSTAKILL = IMPACT_PREFIX+".instakill";
    public static final String IMPACT_BLOCK = IMPACT_PREFIX+".block";

    public static final String NEOPLASIA_PREFIX = PREFIX+".neoplasia";
    public static final String NEOPLASIA_ENABLED = NEOPLASIA_PREFIX+".enabled";
    public static final String NEOPLASIA_DELAY = NEOPLASIA_PREFIX+".delay";
    public static final String NEOPLASIA_COOLDOWN = NEOPLASIA_PREFIX+".cooldown";
    public static final String NEOPLASIA_LENGTH = NEOPLASIA_PREFIX+".length";
    public static final String NEOPLASIA_PROGRESS_SPEED = NEOPLASIA_PREFIX+".progress_speed";
    public static final String NEOPLASIA_DAMAGE = NEOPLASIA_PREFIX+".damage";
    public static final String NEOPLASIA_BLOCK = NEOPLASIA_PREFIX+".block";

    private static final String builtInContentPatch;
    static {
        try {
            builtInContentPatch = arc.Core.files.internal("mindurka/forts.hjson").readString();
        } catch (RuntimeException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    Impl create(RulesContext rc) {
        return new Impl(rc);
    }

    public class Impl extends Gamemode.Impl {
        private Impl(RulesContext rc) {
            super(rc);

            final Rules rules = rc.rules;

            try (TagRead read = TagRead.of(rules)) {
                {
                    String plotKindS = read.r(FortsPlotKind.PREFIX, "square");
                    FortsPlotKind factory = FortsPlotKind.forName(plotKindS);
                    if (factory == null) factory = FortsPlotKind.forName("square");
                    assert factory != null;
                    plotKind = factory.create(rc);
                }

                enable1va = read.r(ENABLE_1VA, true);
                enableVnw = read.r(ENABLE_VNW, false);
                expansionBlock = read.r(EXPANSION_BLOCK, Blocks.impulsePump);
                minHealth = read.r(MIN_HEALTH, 450f);

                thorEnabled = read.r(THOR_ENABLED, true);
                thorDelay = read.r(THOR_DELAY, 0.5f);
                thorCooldown = read.r(THOR_COOLDOWN, 0.75f);
                thorDamageMultiplier = read.r(THOR_DAMAGE_MULTIPLIER, 1f);
                thorRadiusMultiplier = read.r(THOR_RADIUS_MULTIPLIER, 1f);
                thorBlock = read.r(THOR_BLOCK, Blocks.thoriumReactor);

                impactEnabled = read.r(IMPACT_ENABLED, true);
                impactDelay = read.r(IMPACT_DELAY, 0f);
                impactCooldown = read.r(IMPACT_COOLDOWN, 5f);
                impactDuration = read.r(IMPACT_DURATION, 0.25f);
                impactExplosionDamage = read.r(IMPACT_EXPLOSION_DAMAGE, 2000f);
                impactExplosionRadius = read.r(IMPACT_EXPLOSION_RADIUS, 4f);
                impactInstakill = read.r(IMPACT_INSTAKILL, false);
                impactBlock = read.r(IMPACT_BLOCK, Blocks.impactReactor);
                if (impactBlock.size != 4) impactBlock = Blocks.impactReactor;

                neoplasiaEnabled = read.r(NEOPLASIA_ENABLED, true);
                neoplasiaDelay = read.r(NEOPLASIA_DELAY, 1.25f);
                neoplasiaCooldown = read.r(NEOPLASIA_COOLDOWN, 0.25f);
                neoplasiaLength = read.r(NEOPLASIA_LENGTH, 40);
                neoplasiaProgressSpeed = read.r(NEOPLASIA_PROGRESS_SPEED, 80f);
                neoplasiaDamage = read.r(NEOPLASIA_DAMAGE, 1125f);
                neoplasiaBlock = read.r(NEOPLASIA_BLOCK, Blocks.neoplasiaReactor);
                if (!neoplasiaBlock.rotate) impactBlock = Blocks.neoplasiaReactor;

                for (Item item : Vars.content.items()) {
                    passiveItems.add(new ItemStack(item, read.r(PASSIVE_ITEMS_HEAD+item.name,
                            item == Items.copper || item == Items.lead
                                    || item == Items.silicon || item == Items.graphite ? 50 : 0)));
                }
            }

            applyDamageEffects.set(0, 256);

            rc.rules.tags.each((key, value) -> {
                if (Util.keyHasHeadByte(key, APPLY_DAMAGE_EFFECTS_HEAD)) {
                    applyDamageEffects.set(Util.keyHeadByte(key, APPLY_DAMAGE_EFFECTS_HEAD), value.equals("true"));
                }
            });
        }

        @Override
        public void writeGamemodeRules(RulesWrite write) {
            write.b("rules.mindurka.enable_1va", this::enable1va, this::enable1va);
            write.b("rules.mindurka.enable_vnw", this::enableVnw, this::enableVnw);
            write.block("rules.mindurka.expansion_block", this::expansionBlock, this::expansionBlock);
            write.f("rules.mindurka.forts.min_health", this::minHealth, this::minHealth);
            write.loadout("rules.mindurka.passive_items", 999999, () -> passiveItems, i -> true, () -> {
                passiveItems.each(stack -> stack.amount = stack.item == Items.copper || stack.item == Items.lead
                        || stack.item == Items.silicon || stack.item == Items.graphite ? 50 : 0);
                passiveItems.each(stack -> rc.rules.tags.remove(PASSIVE_ITEMS_HEAD + stack.item.name));
            }, () -> {
                passiveItems.each(stack -> rc.rules.tags.put(PASSIVE_ITEMS_HEAD+stack.item.name, Integer.toString(stack.amount)));
            }, () -> {});
            write.spacer();

            write.b("rules.mindurka.thor.enabled", this::thorEnabled, this::thorEnabled);
            write.f("rules.mindurka.thor.delay", this::thorDelay, this::thorDelay).enabled(this::thorEnabled).min(0);
            write.f("rules.mindurka.thor.cooldown", this::thorCooldown, this::thorCooldown).enabled(this::thorEnabled).min(0);
            write.f("rules.mindurka.thor.damageMultiplier", this::thorDamageMultiplier, this::thorDamageMultiplier).enabled(this::thorEnabled).min(0);
            write.f("rules.mindurka.thor.radiusMultiplier", this::thorRadiusMultiplier, this::thorRadiusMultiplier).enabled(this::thorEnabled).min(0);
            write.block("rules.mindurka.thor.block", this::thorBlock, this::thorBlock).enabled(this::thorEnabled).filter(block -> !block.isFloor());
            write.spacer();

            write.b("rules.mindurka.impact.enabled", this::impactEnabled, this::impactEnabled);
            write.f("rules.mindurka.impact.delay", this::impactDelay, this::impactDelay).enabled(this::impactEnabled).min(0);
            write.f("rules.mindurka.impact.cooldown", this::impactCooldown, this::impactCooldown).enabled(this::impactEnabled).min(0);
            write.f("rules.mindurka.impact.duration", this::impactDuration, this::impactDuration).enabled(this::impactEnabled).min(0);
            write.f("rules.mindurka.impact.explosionDamage", this::impactExplosionDamage, this::impactExplosionDamage).enabled(this::impactEnabled).min(0);
            write.f("rules.mindurka.impact.explosionRadius", this::impactExplosionRadius, this::impactExplosionRadius).enabled(this::impactEnabled).min(0);
            write.b("rules.mindurka.impact.instakill", this::impactInstakill, this::impactInstakill).enabled(this::impactEnabled);
            write.block("rules.mindurka.impact.block", this::impactBlock, this::impactBlock).enabled(this::impactEnabled).filter(block -> block.size == 4 && !block.isFloor());
            write.spacer();

            write.b("rules.mindurka.neoplasia.enabled", this::neoplasiaEnabled, this::neoplasiaEnabled);
            write.f("rules.mindurka.neoplasia.delay", this::neoplasiaDelay, this::neoplasiaDelay).enabled(this::neoplasiaEnabled).min(0);
            write.f("rules.mindurka.neoplasia.cooldown", this::neoplasiaCooldown, this::neoplasiaCooldown).enabled(this::neoplasiaEnabled).min(0);
            write.f("rules.mindurka.neoplasia.progressSpeed", this::neoplasiaProgressSpeed, this::neoplasiaProgressSpeed).enabled(this::neoplasiaEnabled).min(0);
            write.i("rules.mindurka.neoplasia.length", this::neoplasiaLength, this::neoplasiaLength).enabled(this::neoplasiaEnabled).min(0);
            write.f("rules.mindurka.neoplasia.damage", this::neoplasiaDamage, this::neoplasiaDamage).enabled(this::neoplasiaEnabled).min(0);
            write.block("rules.mindurka.neoplasia.block", this::neoplasiaBlock, this::neoplasiaBlock).enabled(this::neoplasiaEnabled).filter(block -> block.rotate && !block.isFloor());
            write.spacer();

            Runnable[] refreshPlotKindRules = new Runnable[1];

            write.selection("editor.mindurka.forts.plot", addItem -> {
                for (FortsPlotKind kind : FortsPlotKind.values()) {
                    addItem.add("rules.mindurka.forts.plotKind." + kind.name(), kind);
                }
            }, value -> {
                plotKind(value);
                refreshPlotKindRules[0].run();
            }, plotKind.factory());

            {
                RulesWrite pwrite = write.table();
                boolean[] safetyTrigger = new boolean[] { true };
                refreshPlotKindRules[0] = () -> {
                    pwrite.clear();
                    plotKind().writeRules(pwrite);

                    safetyTrigger[0] = !safetyTrigger[0];
                    if (safetyTrigger[0]) MVars.rules.refreshTeamRules();

                    // pwrite.teams("rules.teams", this::writeTeamRules, team -> !applyDamageEffects(team));
                };
                refreshPlotKindRules[0].run();
            }
        }

        @Override
        public void writeTeamRules(RulesWrite write, Team team) {
            write.b("rules.mindurka.forts.apply_damage_effects", () -> applyDamageEffects(team), v -> applyDamageEffects(team, v));
            plotKind().writeTeamRules(team, write);
        }

        @Override
        public void onStart() {
            plotKind().onStart();
        }

        @Override
        public void editingResumed() {
            plotKind().editingResumed();
        }

        @Override
        public void drawEditorGuides() {
            plotKind().drawEditorGuides();
        }

        private FortsPlotKind.Impl plotKind;
        public FortsPlotKind.Impl plotKind() { return plotKind; };
        public Impl plotKind(FortsPlotKind value) {
            plotKind.remove();
            plotKind = value.create(rc);
            if (MVars.editorDialog.isShown()) MVars.editorDialog.refreshTools();
            return this;
        }

        private boolean enable1va;
        public boolean enable1va() { return enable1va; }
        public Impl enable1va(boolean value) {
            enable1va = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(ENABLE_1VA, value); }
            return this;
        }

        private boolean enableVnw;
        public boolean enableVnw() { return enableVnw; }
        public Impl enableVnw(boolean value) {
            enableVnw = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(ENABLE_VNW, value); }
            return this;
        }

        private Block expansionBlock;
        public Block expansionBlock() { return expansionBlock; }
        public Impl expansionBlock(Block value) {
            expansionBlock = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(EXPANSION_BLOCK, value); }
            return this;
        }

        private float minHealth;
        public float minHealth() { return minHealth; }
        public Impl minHealth(float value) {
            minHealth = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(MIN_HEALTH, value); }
            return this;
        }

        private boolean thorEnabled;
        public boolean thorEnabled() { return thorEnabled; }
        public Impl thorEnabled(boolean value) {
            thorEnabled = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(THOR_ENABLED, value); }
            return this;
        }

        private float thorDelay;
        public float thorDelay() { return thorDelay; }
        public Impl thorDelay(float value) {
            thorDelay = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(THOR_DELAY, value); }
            return this;
        }

        private float thorCooldown;
        public float thorCooldown() { return thorCooldown; }
        public Impl thorCooldown(float value) {
            thorCooldown = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(THOR_COOLDOWN, value); }
            return this;
        }

        private float thorDamageMultiplier;
        public float thorDamageMultiplier() { return thorDamageMultiplier; }
        public Impl thorDamageMultiplier(float value) {
            thorDamageMultiplier = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(THOR_DAMAGE_MULTIPLIER, value); }
            return this;
        }

        private float thorRadiusMultiplier;
        public float thorRadiusMultiplier() { return thorRadiusMultiplier; }
        public Impl thorRadiusMultiplier(float value) {
            thorRadiusMultiplier = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(THOR_RADIUS_MULTIPLIER, value); }
            return this;
        }

        private Block thorBlock;
        public Block thorBlock() { return thorBlock; }
        public Impl thorBlock(Block value) {
            thorBlock = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(THOR_BLOCK, value); }
            return this;
        }

        public boolean impactEnabled;
        public boolean impactEnabled() { return impactEnabled; }
        public Impl impactEnabled(boolean value) {
            impactEnabled = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(IMPACT_ENABLED, value); }
            return this;
        }

        private float impactDelay;
        public float impactDelay() { return impactDelay; }
        public Impl impactDelay(float value) {
            impactDelay = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(IMPACT_DELAY, value); }
            return this;
        }

        private float impactCooldown;
        public float impactCooldown() { return impactCooldown; }
        public Impl impactCooldown(float value) {
            impactCooldown = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(IMPACT_COOLDOWN, value); }
            return this;
        }

        private float impactDuration;
        public float impactDuration() { return impactDuration; }
        public Impl impactDuration(float value) {
            impactDuration = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(IMPACT_DURATION, value); }
            return this;
        }

        private float impactExplosionDamage;
        public float impactExplosionDamage() { return impactExplosionDamage; }
        public Impl impactExplosionDamage(float value) {
            impactExplosionDamage = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(IMPACT_EXPLOSION_DAMAGE, value); }
            return this;
        }

        private float impactExplosionRadius;
        public float impactExplosionRadius() { return impactExplosionRadius; }
        public Impl impactExplosionRadius(float value) {
            impactExplosionRadius = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(IMPACT_EXPLOSION_RADIUS, value); }
            return this;
        }

        public boolean impactInstakill;
        public boolean impactInstakill() { return impactInstakill; }
        public Impl impactInstakill(boolean value) {
            impactInstakill = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(IMPACT_INSTAKILL, value); }
            return this;
        }

        private Block impactBlock;
        public Block impactBlock() { return impactBlock; }
        public Impl impactBlock(Block value) {
            impactBlock = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(IMPACT_BLOCK, value); }
            return this;
        }

        private boolean neoplasiaEnabled;
        public boolean neoplasiaEnabled() { return neoplasiaEnabled; }
        public Impl neoplasiaEnabled(boolean value) {
            neoplasiaEnabled = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(NEOPLASIA_ENABLED, value); }
            return this;
        }

        private float neoplasiaDelay;
        public float neoplasiaDelay() { return neoplasiaDelay; }
        public Impl neoplasiaDelay(float value) {
            neoplasiaDelay = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(NEOPLASIA_DELAY, value); }
            return this;
        }

        private float neoplasiaCooldown;
        public float neoplasiaCooldown() { return neoplasiaCooldown; }
        public Impl neoplasiaCooldown(float value) {
            neoplasiaCooldown = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(NEOPLASIA_COOLDOWN, value); }
            return this;
        }

        private int neoplasiaLength;
        public int neoplasiaLength() { return neoplasiaLength; }
        public Impl neoplasiaLength(int value) {
            neoplasiaLength = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(NEOPLASIA_LENGTH, value); }
            return this;
        }

        private float neoplasiaProgressSpeed;
        public float neoplasiaProgressSpeed() { return neoplasiaProgressSpeed; }
        public Impl neoplasiaProgressSpeed(float value) {
            neoplasiaProgressSpeed = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(NEOPLASIA_PROGRESS_SPEED, value); }
            return this;
        }

        private float neoplasiaDamage;
        public float neoplasiaDamage() { return neoplasiaDamage; }
        public Impl neoplasiaDamage(float value) {
            neoplasiaDamage = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(NEOPLASIA_DAMAGE, value); }
            return this;
        }

        private Block neoplasiaBlock;
        public Block neoplasiaBlock() { return neoplasiaBlock; }
        public Impl neoplasiaBlock(Block value) {
            neoplasiaBlock = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(NEOPLASIA_BLOCK, value); }
            return this;
        }

        private final Bits applyDamageEffects = new Bits(256);
        public boolean applyDamageEffects(Team team) {
            return applyDamageEffects.get(team.id);
        }
        public Impl applyDamageEffects(Team team, boolean value) {
            applyDamageEffects.set(team.id, value);
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(APPLY_DAMAGE_EFFECTS_HEAD+team.id, value); }
            return this;
        }

        private final Seq<ItemStack> passiveItems = new Seq<>(ItemStack.class);
        public int passiveItems(Item item) {
            return passiveItems.find(x -> x.item == item).amount;
        }
        public Impl passiveItems(Item item, int value) {
            passiveItems.find(x -> x.item == item).amount = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(PASSIVE_ITEMS_HEAD+item.name, value); }
            return this;
        }

        @Override
        public void dataFixer() {
            if (MVars.rules.originalPatchVer < 7) {
                Vars.state.rules.bannedUnits.remove(UnitTypes.retusa);
                Vars.state.rules.bannedBlocks.remove(Blocks.overflowGate);
                Vars.state.rules.unitPayloadUpdate = true;
            }
        }

        @Override
        void remove() {
            final Rules rules = rc.rules;

            rules.tags.remove(THOR_ENABLED);
            rules.tags.remove(THOR_DELAY);
            rules.tags.remove(THOR_COOLDOWN);
            rules.tags.remove(THOR_DAMAGE_MULTIPLIER);
            rules.tags.remove(THOR_RADIUS_MULTIPLIER);
            rules.tags.remove(THOR_BLOCK);

            rules.tags.remove(IMPACT_ENABLED);
            rules.tags.remove(IMPACT_DELAY);
            rules.tags.remove(IMPACT_COOLDOWN);
            rules.tags.remove(IMPACT_DURATION);
            rules.tags.remove(IMPACT_EXPLOSION_RADIUS);
            rules.tags.remove(IMPACT_EXPLOSION_DAMAGE);
            rules.tags.remove(IMPACT_INSTAKILL);
            rules.tags.remove(IMPACT_BLOCK);

            rules.tags.remove(NEOPLASIA_ENABLED);
            rules.tags.remove(NEOPLASIA_DELAY);
            rules.tags.remove(NEOPLASIA_COOLDOWN);
            rules.tags.remove(NEOPLASIA_LENGTH);
            rules.tags.remove(NEOPLASIA_PROGRESS_SPEED);
            rules.tags.remove(NEOPLASIA_DAMAGE);
            rules.tags.remove(NEOPLASIA_BLOCK);

            rules.tags.remove(ENABLE_1VA);
            rules.tags.remove(ENABLE_VNW);
            rules.tags.remove(EXPANSION_BLOCK);
            rules.tags.remove(MIN_HEALTH);

            Seq<String> removeAll = new Seq<>(String.class);
            rules.tags.each((key, value) -> {
                if (Util.keyHasHeadByte(key, APPLY_DAMAGE_EFFECTS_HEAD)) removeAll.add(key);
                else if (Util.keyHasHeadByte(key, PASSIVE_ITEMS_HEAD)) removeAll.add(key);
            });
            removeAll.each(rules.tags::remove);

            plotKind.remove();
        }

        @Override
        protected void _setRules() {
            final Rules rules = rc.rules;

            for (Team team : Team.all) {
                Rules.TeamRule t = rules.teams.get(team);
                t.cheat = true;
                // Apparently Mapping Utilities was fucking this one up or smth?
                // idk but I'll set it anyway just in case.
                t.infiniteResources = false;
            }
            rules.planet = Planets.sun;
            rules.env = Vars.defaultEnv;
            rules.unitCapVariable = false;
            rules.unitCap = 75;
            rules.waves = false;
            rules.modeName = "Forts";
            rules.onlyDepositCore = false;
            rules.coreIncinerates = true;
            rules.buildCostMultiplier = 1.5f;
            rules.buildSpeedMultiplier = 4f;
            rules.deconstructRefundMultiplier = 0.5f;
            rules.blockHealthMultiplier = 0.66f;
            rules.unitDamageMultiplier = 1.4f;
            rules.unitCrashDamageMultiplier = 3.5f;
            rules.unitMineSpeedMultiplier = 1f;
            rules.unitHealthMultiplier = 1f;
            rules.attackMode = false;
            rules.possessionAllowed = false;
            rules.enemyCoreBuildRadius = 30f * Vars.tilesize;
            rules.schematicsAllowed = false;
            rules.loadout.clear();
            rules.loadout.add(ItemStack.with(
                    Items.copper, 1000,
                    Items.lead, 1000,
                    Items.metaglass, 300,
                    Items.graphite, 300,
                    Items.titanium, 300,
                    Items.thorium, 300,
                    Items.scrap, 300,
                    Items.silicon, 300,
                    Items.beryllium, 300
            ));
            rules.revealedBlocks.addAll(
                    Blocks.slagCentrifuge,
                    Blocks.heatReactor
            );
            rules.bannedBlocks.addAll(
                    Blocks.diode,
                    Blocks.cryofluidMixer,
                    Blocks.surgeWall,
                    Blocks.surgeWallLarge,
                    Blocks.scrapWall,
                    Blocks.mendProjector,
                    Blocks.overdriveDome,
                    Blocks.shockMine,
                    Blocks.ductUnloader,
                    Blocks.reinforcedConduit,
                    Blocks.reinforcedLiquidJunction,
                    Blocks.reinforcedBridgeConduit,
                    Blocks.reinforcedLiquidRouter,
                    Blocks.reinforcedLiquidContainer,
                    Blocks.reinforcedLiquidTank,
                    Blocks.powerNode,
                    Blocks.powerNodeLarge,
                    Blocks.surgeTower,
                    Blocks.battery,
                    Blocks.batteryLarge,
                    Blocks.combustionGenerator,
                    Blocks.thermalGenerator,
                    Blocks.steamGenerator,
                    Blocks.differentialGenerator,
                    Blocks.rtgGenerator,
                    Blocks.solarPanel,
                    Blocks.largeSolarPanel,
                    Blocks.beamNode,
                    Blocks.beamLink,
                    Blocks.beamTower,
                    Blocks.turbineCondenser,
                    Blocks.chemicalCombustionChamber,
                    Blocks.fluxReactor, // I'll put a note in that one
                    Blocks.waterExtractor,
                    Blocks.oilExtractor,
                    Blocks.ventCondenser,
                    Blocks.cliffCrusher,
                    Blocks.largeCliffCrusher,
                    Blocks.impactDrill,
                    Blocks.eruptionDrill,
                    Blocks.unloader,
                    Blocks.reinforcedContainer,
                    Blocks.reinforcedVault,
                    Blocks.groundFactory,
                    Blocks.tetrativeReconstructor,
                    Blocks.tankFabricator,
                    Blocks.tankRefabricator,
                    Blocks.tankAssembler,
                    Blocks.reinforcedPayloadConveyor,
                    Blocks.reinforcedPayloadRouter,
                    Blocks.smallDeconstructor,
                    Blocks.largeConstructor,
                    Blocks.switchBlock,
                    Blocks.microProcessor,
                    Blocks.logicProcessor,
                    Blocks.hyperProcessor,
                    Blocks.memoryCell,
                    Blocks.memoryBank,
                    Blocks.logicDisplay,
                    Blocks.largeLogicDisplay,
                    Blocks.reinforcedMessage,
                    Blocks.tileLogicDisplay
            );
            rules.hideBannedBlocks = true;
            rules.unitPayloadUpdate = true;
            rules.bannedUnits.clear();
            rc.customRules.overdriveIgnoresCheat(true);
        }

        @Override
        public String builtInContentPatch() {
            return builtInContentPatch;
        }
    }
}
