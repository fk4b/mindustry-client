package mindustry.client.morj.mk.rules;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.math.geom.Vec2;
import arc.struct.ObjectIntMap;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import arc.math.geom.Point2;
import arc.util.Log;
import arc.util.pooling.Pools;
import arc.util.serialization.Jval;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.ui.RulesWrite;
import mindustry.client.morj.mk.util.Schematic;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Icon;
import mindustry.type.Item;
import mindustry.type.ItemStack;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.ui.Fonts;
import mindustry.world.Block;
import mindustry.world.blocks.defense.turrets.Turret;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.environment.Prop;
import mindustry.world.blocks.environment.SpawnBlock;
import mindustry.world.meta.Env;

import java.util.Iterator;

public class Castle extends Gamemode {

    public static final String PREFIX = MRules.PREFIX+".castle";
    public static final String BLOCKS = PREFIX+".blocks";
    public static final String MINERS = PREFIX+".miners";
    public static final String UTILS = PREFIX+".utils.";
    public static final String TURRET = PREFIX+".turret.";
    public static final String UNIT = PREFIX+".unit.";
    public static final String ITEM = PREFIX+".item.";
    public static final String STATUS = PREFIX+".status.";
    public static final String NAME = "castle-wars";
    public static final String COST = ".cost";
    public static final String INCOME = ".income";
    public static final String INTERVAL = ".interval";
    public static final String AMOUNT = ".amount";
    public static final String DROP = ".drop";
    public static final String DURATION = ".duration";
    public static final String ALLY = ".ally";
    public static final String DELAY = ".delay";
    public static final String DRILL =  ".drill";

    public static final int PLATFORM_SIZE = 5;

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

            try (TagRead read = TagRead.of(rc.rules)) {
                CastleCosts.load();
                shopFloor = read.r(UTILS+"shopFloor", Blocks.empty);
                groundSpawn = read.rPoints(UTILS+"groundSpawn", new Seq<>());
                navalSpawn = read.rPoints(UTILS+"navalSpawn", new Seq<>());
                airSpawn = read.rPoints(UTILS+"airSpawn", new Seq<>());
                attackUnitCap = read.r(UTILS+"attackUnitCap", -1);
                defenseUnitCap = read.r(UTILS+"defenseUnitCap", 75);
                divideCap = read.r(UTILS+"divideCap", true);
                mirrored = read.r(UTILS+"mirrored", true);
                betterGroundValid = read.r(UTILS+"betterGroundValid", true);
                noPlatform = read.r(UTILS+"noPlatform", false);
                platformSource = new Seq<>();
                int psCount = read.r(UTILS+"platformSource.count", 0);
                for (int i = 0; i < psCount; i++) {
                    platformSource.add(read.r(UTILS+"platformSource."+i, Schematic.EMPTY));
                }
                try {
                    Jval.JsonArray array = Jval.read(read.r(BLOCKS, "[]")).asArray();
                    array.each(x -> {
                        try {
                            Jval.JsonMap obj = x.asObject();
                            int blockX = obj.get("x").asInt();
                            int blockY = obj.get("y").asInt();
                            int cost = obj.get("cost").asInt();
                            boolean invincible = obj.get("invincible").asBool();
                            Block block = Vars.content.block(obj.get("block").asString());
                            if (block == null) return;
                            blocks.add(new Castle.CastleBlock(block, blockX, blockY, cost,invincible));
                        } catch (Exception e) {
                            Log.err("Failed to parse blocks", e);
                            Vars.ui.showException("Failed to parse blocks", e);
                        }
                    });
                } catch (Exception e) {
                    Log.err("Failed to parse blocks", e);
                    Vars.ui.showException("Failed to parse blocks", e);
                }
                try {
                    Jval.JsonArray array = Jval.read(read.r(MINERS, "[]")).asArray();
                    array.each(x -> {
                        try {
                            Jval.JsonMap obj = x.asObject();
                            int blockX = obj.get("x").asInt();
                            int blockY = obj.get("y").asInt();
                            int cost = obj.get("cost").asInt();
                            int amount = obj.get("amount").asInt();
                            int interval = obj.get("interval").asInt();
                            Block block = Vars.content.block(obj.get("block").asString());
                            Item item = Vars.content.item(obj.get("item").asString());
                            if (block == null || item == null) return;
                            miners.add(new Castle.CastleMiner(block, blockX, blockY, cost,amount,interval,item));
                        } catch (Exception e) {
                            Log.err("Failed to parse miners", e);
                            Vars.ui.showException("Failed to parse miners", e);
                        }
                    });
                } catch (Exception e) {
                    Log.err("Failed to parse miners", e);
                    Vars.ui.showException("Failed to parse miners", e);
                }
            }
        }

        @Override
        public void writeGamemodeRules(RulesWrite write) {
            write.tree("@rules.mindurka.castle.unit", unitRoot -> {
                for (UnitType u : mindustry.Vars.content.units()) {
                    unitRoot.tree(u.localizedName + u.emoji(), cfg -> {
                        cfg.i("rules.mindurka.castle.unit.cost",
                                () -> unitCostFor(u),
                                v -> unitCostFor(u, v));
                        cfg.i("rules.mindurka.castle.unit.income",
                                () -> unitIncomeFor(u),
                                v -> unitIncomeFor(u, v));
                        cfg.i("rules.mindurka.castle.unit.drop",
                                () -> unitDropFor(u),
                                v -> unitDropFor(u, v));
                    });
                }
            });
            write.tree("@rules.mindurka.castle.status", statusRoot -> {
                for (StatusEffect s : mindustry.Vars.content.statusEffects()) {
                    statusRoot.tree(s.localizedName + s.emoji(), cfg -> {
                        cfg.i("rules.mindurka.castle.status.cost",
                                () -> statusCostFor(s),
                                v -> statusCostFor(s, v));
                        cfg.i("rules.mindurka.castle.status.delay",
                                () -> statusDelayFor(s),
                                v -> statusDelayFor(s, v));
                        cfg.i("rules.mindurka.castle.status.duration",
                                () -> statusDurationFor(s),
                                v -> statusDurationFor(s, v));
                        cfg.b("rules.mindurka.castle.status.ally",
                                () -> statusAllyFor(s),
                                v -> statusAllyFor(s, v));
                    });
                }
            });

            write.spacer();

            write.points("rules.mindurka.castle.groundSpawn", this::groundSpawn, this::groundSpawn);
            write.points("rules.mindurka.castle.airSpawn",    this::airSpawn,    this::airSpawn);
            write.points("rules.mindurka.castle.navalSpawn",  this::navalSpawn,  this::navalSpawn);
            write.spacer();

            write.i("rules.mindurka.castle.defenseUnitCap", this::defenseUnitCap, this::defenseUnitCap);
            write.i("rules.mindurka.castle.attackUnitCap",  this::attackUnitCap,  this::attackUnitCap);
            write.b("rules.mindurka.castle.divideCap",      this::divideCap,      this::divideCap);
            write.spacer();

            write.platformSources("rules.mindurka.castle.platformSource",
                    this::platformSource,
                    this::addPlatformSource,
                    this::removePlatformSource);
            write.spacer();

            write.b("rules.mindurka.castle.mirrored",       this::mirrored,       this::mirrored);
            write.b("rules.mindurka.castle.noPlatform",       this::noPlatform,       this::noPlatform);
            write.b("rules.mindurka.castle.betterGroundValid", this::betterGroundValid, this::betterGroundValid);
            write.block("rules.mindurka.castle.shopFloor",    this::shopFloor,        this::shopFloor).filter(block -> block instanceof Prop || block instanceof Floor);
        }

        @Override
        void remove() {
            final Rules rules = rc.rules;
            Seq<String> toRemove = new Seq<>();
            rules.tags.each((key, value) -> {
                if (key.startsWith("mdrk.castle.")) toRemove.add(key);
            });
            toRemove.each(rules.tags::remove);
        }

        @Override
        public void drawEditorGuides() {
            for (int i = 0; i < blocks.size; i++) {
                Castle.CastleBlock block = blocks.items[i];

                int offset = block.block.size / 2 * -1;
                Vec2 v1 = MVars.mapView.unproject(block.x + offset, block.y + offset);
                float sx = v1.x;
                float sy = v1.y;
                Vec2 v2 = MVars.mapView.unproject(block.x + offset + block.block.size, block.y + offset + block.block.size);

                Draw.reset();
                TextureRegion region = block.block.uiIcon;
                if (region != null && region.found()) {
                    Draw.rect(region, (sx + v2.x) / 2, (sy + v2.y) / 2, v2.x - sx, v2.y - sy);
                }
                Draw.reset();
                Draw.color(Color.white);
                Lines.rect(sx, sy, v2.x - sx, v2.y - sy);
                Draw.reset();

                GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
                float blockW = v2.x - sx;
                float blockH = v2.y - sy;
                String label = Core.bundle.get("rules.mindurka.castle.block.cost") + ": " + block.cost + "\n" +
                        Core.bundle.get("rules.status.invincible") + ": " + block.invincible;

                Fonts.outline.getData().setScale(1f);
                layout.setText(Fonts.outline, label);
                float scaleX = blockW / layout.width;
                float scaleY = blockH / layout.height;
                float fontScale = Math.min(scaleX, scaleY) * 0.9f;
                Fonts.outline.getData().setScale(fontScale);
                layout.setText(Fonts.outline, label);

                float cx = (sx + v2.x) / 2;
                float cy = (sy + v2.y) / 2;
                float tx = cx + layout.width / 2;
                float ty = cy + layout.height / 2;

                Fonts.outline.draw(label, tx, ty, 0, 0, false);

                Pools.free(layout);
                Fonts.outline.getData().setScale(1f);
                Fonts.outline.setColor(Color.white);
            }
            for (int i = 0; i < miners.size; i++) {
                Castle.CastleMiner miner = miners.items[i];

                int offset = miner.block.size / 2 * -1;
                Vec2 v1 = MVars.mapView.unproject(miner.x + offset, miner.y + offset);
                float sx = v1.x;
                float sy = v1.y;
                Vec2 v2 = MVars.mapView.unproject(miner.x + offset + miner.block.size, miner.y + offset + miner.block.size);

                Draw.reset();
                TextureRegion region = miner.block.uiIcon;
                if (region != null && region.found()) {
                    Draw.rect(region, (sx + v2.x) / 2, (sy + v2.y) / 2, v2.x - sx, v2.y - sy);
                }
                Draw.reset();
                Draw.color(Color.white);
                Lines.rect(sx, sy, v2.x - sx, v2.y - sy);
                Draw.reset();

                GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
                float blockW = v2.x - sx;
                float blockH = v2.y - sy;
                String label =
                    Core.bundle.get("rules.mindurka.castle.item.cost") + ": " + miner.cost + "\n" +
                        Core.bundle.get("rules.mindurka.castle.item.interval") + ": " + miner.interval + "\n" +
                        Core.bundle.get("rules.mindurka.castle.item.amount") + ": " + miner.amount + "\n" +
                        Core.bundle.get("rules.mindurka.castle.item") + ": " + (miner.item != null ? miner.item.emoji() + "(" + miner.item + ")" : "?");

                Fonts.outline.getData().setScale(1f);
                layout.setText(Fonts.outline, label);
                float scaleX = blockW / layout.width;
                float scaleY = blockH / layout.height;
                float fontScale = Math.min(scaleX, scaleY) * 0.9f;
                Fonts.outline.getData().setScale(fontScale);
                layout.setText(Fonts.outline, label);

                float cx = (sx + v2.x) / 2;
                float cy = (sy + v2.y) / 2;
                float tx = cx + layout.width / 2;
                float ty = cy + layout.height / 2;

                Fonts.outline.draw(label, tx, ty, 0, 0, false);

                Pools.free(layout);
                Fonts.outline.getData().setScale(1f);
                Fonts.outline.setColor(Color.white);

            }
            TextureRegion groundUnitIcon = Vars.content.units().find(unit -> !unit.flying && !unit.naval && !unit.isHidden() && !unit.isBanned()).uiIcon;
            TextureRegion flyingUnitIcon = Vars.content.units().find(unit -> unit.flying && !unit.canBoost && !unit.isHidden() && !unit.isBanned()).uiIcon;
            TextureRegion navalUnit = Vars.content.units().find(unit -> unit.naval && !unit.isHidden() && !unit.isBanned()).uiIcon;
            drawDefenseSpawn(groundSpawn,groundUnitIcon);
            drawDefenseSpawn(airSpawn,flyingUnitIcon);
            drawDefenseSpawn(navalSpawn,navalUnit);
            drawDropZones(Icon.units.getRegion());
        }
        private void drawDefenseSpawn(Seq<Point2> spawns, TextureRegion icon){
            float lineScale = Core.settings.getInt("mindurka.guideslinewidth", 1);
            for(Point2 spawn : spawns){
                float borderX = (float) spawn.x;
                float borderY = (float) spawn.y-6;
                float borderX2 = (float) 1+borderX;
                float borderY2 = 12f+borderY;
                Vec2 border = new Vec2(MVars.mapView.unproject(borderX,borderY));
                Vec2 borderParam = MVars.mapView.unproject(borderX2,borderY2);
                Vec2 borderParamNew = new Vec2(borderParam.x - border.x,borderParam.y-border.y);
                Draw.reset();
                Draw.color(Color.blue);
                Lines.stroke(lineScale);
                Lines.rect(border.x,border.y,borderParamNew.x,borderParamNew.y);
                Draw.reset();
                Draw.color(Color.white);
                Vec2 newSpawn = new Vec2(MVars.mapView.unproject(spawn.x+0.5f,spawn.y+0.5f));
                Vec2 v1 = MVars.mapView.unproject((float)spawn.x+1.5f,(float)spawn.y+1.5f);
                Draw.rect(icon,newSpawn.x,newSpawn.y,v1.x-newSpawn.x,v1.y-newSpawn.y,-90);
                Draw.reset();
            }
        }
        private void drawDropZones(TextureRegion icon){
            float lineScale = Core.settings.getInt("mindurka.guideslinewidth", 1);
            Vars.world.tiles.eachTile(tile -> {
                if(tile.overlay() instanceof SpawnBlock){
                    Draw.reset();
                    Draw.color(Color.scarlet);
                    Lines.stroke(lineScale);
                    Vec2 spawn = new Vec2(MVars.mapView.unproject((float)tile.x+0.5f,(float)tile.y+0.5f));
                    Vec2 v3 = new Vec2(MVars.mapView.unproject(tile.x,tile.y - rc.rules.dropZoneRadius / Vars.tilesize));
                    Lines.circle(spawn.x,spawn.y,(spawn.y - v3.y));
                    Draw.reset();
                    Draw.color(Color.white);
                    Vec2 corner1 = new Vec2(MVars.mapView.unproject(tile.x-1.5f,tile.y-1.25f));
                    Vec2 corner2 = MVars.mapView.unproject(tile.x+1.5f,tile.y+1.25f);
                    float w = corner2.x - corner1.x;
                    float h = corner2.y - corner1.y;
                    Draw.rect(icon, spawn.x, spawn.y, w, h);
                }
            });
        }

        private final ObjectIntMap<Block> blockCostMap = new ObjectIntMap<>();

        public int blockCostFor(Block b) {
            if (blockCostMap.containsKey(b)) return blockCostMap.get(b, 0);
            int def;
            try (TagRead read = TagRead.of(rc.rules)) {
                def = read.r(TURRET + b + COST, CastleCosts.turrets.get( b, 0));
            }
            blockCostMap.put(b, def);
            return def;
        }

        public Impl blockCostFor(Block b, int value) {
            blockCostMap.put(b, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(TURRET + b + COST, value); }
            return this;
        }
        private final ObjectMap<UnitType, Integer> unitCostMap   = new ObjectMap<>();
        private final ObjectMap<UnitType, Integer> unitIncomeMap = new ObjectMap<>();
        private final ObjectMap<UnitType, Integer> unitDropMap   = new ObjectMap<>();

        public int unitCostFor(UnitType u) {
            return unitCostMap.get(u, () -> {
                CastleCosts.UnitData d = CastleCosts.units.get(u);
                try (TagRead read = TagRead.of(rc.rules)) {
                    return read.r(UNIT + u + COST, d != null ? d.cost() : 0);
                }
            });
        }
        public Impl unitCostFor(UnitType u, int value) {
            unitCostMap.put(u, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(UNIT + u + COST, value); }
            return this;
        }

        public int unitIncomeFor(UnitType u) {
            return unitIncomeMap.get(u, () -> {
                CastleCosts.UnitData d = CastleCosts.units.get(u);
                try (TagRead read = TagRead.of(rc.rules)) {
                    return read.r(UNIT + u + INCOME, d != null ? d.income() : 0);
                }
            });
        }
        public Impl unitIncomeFor(UnitType u, int value) {
            unitIncomeMap.put(u, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(UNIT + u + INCOME, value); }
            return this;
        }

        public int unitDropFor(UnitType u) {
            return unitDropMap.get(u, () -> {
                CastleCosts.UnitData d = CastleCosts.units.get(u);
                try (TagRead read = TagRead.of(rc.rules)) {
                    return read.r(UNIT + u + DROP, d != null ? d.drop() : 0);
                }
            });
        }
        public Impl unitDropFor(UnitType u, int value) {
            unitDropMap.put(u, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(UNIT + u + DROP, value); }
            return this;
        }

        private final ObjectIntMap<Item>     itemCostMap     = new ObjectIntMap<>();
        private final ObjectIntMap<Item>     itemIntervalMap = new ObjectIntMap<>();
        private final ObjectIntMap<Item>     itemAmountMap   = new ObjectIntMap<>();
        private final ObjectMap<Item, Block> drillMap        = new ObjectMap<>();

        public int itemCostFor(Item i) {
            if (itemCostMap.containsKey(i)) return itemCostMap.get(i, 0);
            CastleCosts.ItemData d = CastleCosts.items.get(i);
            int def;
            try (TagRead read = TagRead.of(rc.rules)) {
                def = read.r(ITEM + i + COST, d != null ? d.cost() : 0);
            }
            itemCostMap.put(i, def);
            return def;
        }
        public Impl itemCostFor(Item i, int value) {
            itemCostMap.put(i, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(ITEM + i + COST, value); }
            return this;
        }

        public int itemIntervalFor(Item i) {
            if (itemIntervalMap.containsKey(i)) return itemIntervalMap.get(i, 0);
            CastleCosts.ItemData d = CastleCosts.items.get(i);
            int def;
            try (TagRead read = TagRead.of(rc.rules)) {
                def = read.r(ITEM + i + INTERVAL, d != null ? d.interval() : 0);
            }
            itemIntervalMap.put(i, def);
            return def;
        }
        public Impl itemIntervalFor(Item i, int value) {
            itemIntervalMap.put(i, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(ITEM + i + INTERVAL, value); }
            return this;
        }

        public int itemAmountFor(Item i) {
            if (itemAmountMap.containsKey(i)) return itemAmountMap.get(i, 0);
            CastleCosts.ItemData d = CastleCosts.items.get(i);
            int def;
            try (TagRead read = TagRead.of(rc.rules)) {
                def = read.r(ITEM + i + AMOUNT, d != null ? d.amount() : 0);
            }
            itemAmountMap.put(i, def);
            return def;
        }
        public Impl itemAmountFor(Item i, int value) {
            itemAmountMap.put(i, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(ITEM + i + AMOUNT, value); }
            return this;
        }

        public Block drillFor(Item i) {
            return drillMap.get(i, () -> {
                try (TagRead read = TagRead.of(rc.rules)) {
                    return read.r(ITEM + i + DRILL, drillGet(i));
                }
            });
        }
        public Impl drillFor(Item i, Block value) {
            drillMap.put(i, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(ITEM + i + DRILL, value); }
            return this;
        }

        private final ObjectIntMap<StatusEffect>       statusDelayMap    = new ObjectIntMap<>();
        private final ObjectIntMap<StatusEffect>       statusDurationMap = new ObjectIntMap<>();
        private final ObjectIntMap<StatusEffect>       statusCostMap     = new ObjectIntMap<>();
        private final ObjectMap<StatusEffect, Boolean> statusAllyMap     = new ObjectMap<>();

        public int statusCostFor(StatusEffect s) {
            if (statusCostMap.containsKey(s)) return statusCostMap.get(s, 0);
            CastleCosts.EffectData d = CastleCosts.effects.get(s);
            int def;
            try (TagRead read = TagRead.of(rc.rules)) {
                def = read.r(STATUS + s + COST, d != null ? d.cost() : 0);
            }
            statusCostMap.put(s, def);
            return def;
        }
        public Impl statusCostFor(StatusEffect s, int value) {
            statusCostMap.put(s, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(STATUS + s + COST, value); }
            return this;
        }

        public int statusDelayFor(StatusEffect s) {
            if (statusDelayMap.containsKey(s)) return statusDelayMap.get(s, 0);
            CastleCosts.EffectData d = CastleCosts.effects.get(s);
            int def;
            try (TagRead read = TagRead.of(rc.rules)) {
                def = read.r(STATUS + s + DELAY, d != null ? d.delay() : 0);
            }
            statusDelayMap.put(s, def);
            return def;
        }
        public Impl statusDelayFor(StatusEffect s, int value) {
            statusDelayMap.put(s, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(STATUS + s + DELAY, value); }
            return this;
        }

        public int statusDurationFor(StatusEffect s) {
            if (statusDurationMap.containsKey(s)) return statusDurationMap.get(s, 0);
            CastleCosts.EffectData d = CastleCosts.effects.get(s);
            int def;
            try (TagRead read = TagRead.of(rc.rules)) {
                def = read.r(STATUS + s + DURATION, d != null ? d.duration() : 0);
            }
            statusDurationMap.put(s, def);
            return def;
        }
        public Impl statusDurationFor(StatusEffect s, int value) {
            statusDurationMap.put(s, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(STATUS + s + DURATION, value); }
            return this;
        }

        public boolean statusAllyFor(StatusEffect s) {
            return statusAllyMap.get(s, () -> {
                CastleCosts.EffectData d = CastleCosts.effects.get(s);
                try (TagRead read = TagRead.of(rc.rules)) {
                    return read.r(STATUS + s + ALLY, d != null && d.ally());
                }
            });
        }
        public Impl statusAllyFor(StatusEffect s, boolean value) {
            statusAllyMap.put(s, value);
            try (TagWrite w = TagWrite.of(rc.rules)) { w.w(STATUS + s + ALLY, value); }
            return this;
        }

        private Block drillGet(Item item) {
            if (item == Items.lead || item == Items.copper || item == Items.titanium
                    || item == Items.metaglass || item == Items.coal || item == Items.scrap || item == Items.plastanium
                    || item == Items.surgeAlloy || item == Items.pyratite || item == Items.blastCompound
                    || item == Items.sporePod) return Blocks.laserDrill;
            if (item == Items.beryllium || item == Items.tungsten || item == Items.oxide
                    || item == Items.carbide || item == Items.fissileMatter || item == Items.dormantCyst) return Blocks.impactDrill;

            return Vars.state.rules.hasEnv(Env.scorching) ? Blocks.impactDrill : Blocks.laserDrill;
        }

        @Override
        protected void _setRules() {
            final Rules rules = rc.rules;

            for (Team team : Team.all) {
                Rules.TeamRule t = rules.teams.get(team);
                t.cheat = true;
                t.infiniteResources = false;
            }
            rules.unitCapVariable = false;
            rules.unitCap = 500;
            rules.waves = false;
            rules.modeName = "Castle Wars";
            rules.onlyDepositCore = false;
            rules.coreIncinerates = true;
            rules.buildCostMultiplier = 1f;
            rules.buildSpeedMultiplier = 0.5f;
            rules.dropZoneRadius = Vars.tilesize*6f;
            rules.deconstructRefundMultiplier = 0.5f;
            rules.blockHealthMultiplier = 1f;
            rules.unitDamageMultiplier = 1f;
            rules.unitCrashDamageMultiplier = 2.5f;
            rules.unitMineSpeedMultiplier = 1f;
            rules.unitHealthMultiplier = 1f;
            rules.attackMode = false;
            rules.possessionAllowed = true;
            rules.polygonCoreProtection = true;
            rules.loadout.clear();
            rules.loadout.add(ItemStack.with(
                    Items.copper, 100,
                    Items.lead, 100
            ));
            rules.hideBannedBlocks = true;
            rc.customRules.overdriveIgnoresCheat(false);
        }

        private Seq<Point2> groundSpawn;
        public Seq<Point2> groundSpawn() { return groundSpawn; }
        public Impl groundSpawn(Seq<Point2> value) {
            groundSpawn = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.wPoints(UTILS+"groundSpawn", value); }
            return this;
        }

        private Seq<Point2> airSpawn;
        public Seq<Point2> airSpawn() { return airSpawn; }
        public Impl airSpawn(Seq<Point2> value) {
            airSpawn = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.wPoints(UTILS+"airSpawn", value); }
            return this;
        }

        private Seq<Point2> navalSpawn;
        public Seq<Point2> navalSpawn() { return navalSpawn; }
        public Impl navalSpawn(Seq<Point2> value) {
            navalSpawn = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.wPoints(UTILS+"navalSpawn", value); }
            return this;
        }

        // ── Unit caps ─────────────────────────────────────────────────────────────

        private int defenseUnitCap;
        public int defenseUnitCap() { return defenseUnitCap; }
        public Impl defenseUnitCap(int value) {
            defenseUnitCap = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(UTILS+"defenseUnitCap", value); }
            return this;
        }

        private int attackUnitCap;
        public int attackUnitCap() { return attackUnitCap; }
        public Impl attackUnitCap(int value) {
            attackUnitCap = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(UTILS+"attackUnitCap", value); }
            return this;
        }

        private boolean divideCap;
        public boolean divideCap() { return divideCap; }
        public Impl divideCap(boolean value) {
            divideCap = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(UTILS+"divideCap", value); }
            return this;
        }

        private boolean betterGroundValid;
        public boolean betterGroundValid() { return betterGroundValid; }
        public Impl betterGroundValid(boolean value) {
            betterGroundValid = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(UTILS+"betterGroundValid", value); }
            return this;
        }

        private Seq<Schematic> platformSource;
        public Seq<Schematic> platformSource() { return platformSource; }

        private void savePlatformSource() {
            try (TagWrite write = TagWrite.of(rc.rules)) {
                write.w(UTILS+"platformSource.count", platformSource.size);
                for (int i = 0; i < platformSource.size; i++) {
                    write.w(UTILS+"platformSource."+i, platformSource.get(i));
                }
            }
        }

        public Impl addPlatformSource(Schematic value) {
            platformSource.add(value);
            savePlatformSource();
            return this;
        }

        public Impl removePlatformSource(int index) {
            if (index < 0 || index >= platformSource.size) return this;
            platformSource.remove(index);
            try (TagWrite write = TagWrite.of(rc.rules)) {
                write.w(UTILS+"platformSource.count", platformSource.size);
                rc.rules.tags.remove(UTILS+"platformSource."+platformSource.size);
                for (int i = 0; i < platformSource.size; i++) {
                    write.w(UTILS+"platformSource."+i, platformSource.get(i));
                }
            }
            return this;
        }

        private boolean noPlatform;
        public boolean noPlatform() { return noPlatform; }
        public Impl noPlatform(boolean value) {
            noPlatform = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(UTILS+"noPlatform", value); }
            return this;
        }

        private boolean mirrored;
        public boolean mirrored() { return mirrored; }
        public Impl mirrored(boolean value) {
            mirrored = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(UTILS+"mirrored", value); }
            return this;
        }

        private Block shopFloor;
        public Block shopFloor() { return shopFloor; }
        public Impl shopFloor(Block value) {
            shopFloor = value;
            try (TagWrite write = TagWrite.of(rc.rules)) { write.w(UTILS+"shopFloor", value); }
            return this;
        }

        private final Seq<Castle.CastleBlock> blocks = new Seq<>(Castle.CastleBlock.class);
        public Seq<Castle.CastleBlock> blocks() { return blocks; }
        public void placeBlock(Castle.CastleBlock block) {
            blocks.addUnique(block);
            saveBlocks();
        }
        public void removeBlock(Castle.CastleBlock block) {
            blocks.remove(block);
            saveBlocks();
        }
        public void saveBlocks() {
            Jval.JsonArray array = new Jval.JsonArray();
            for (int i = 0; i < blocks.size; i++) {
                Castle.CastleBlock block = blocks.items[i];
                if (block.block == null) continue;

                Jval val = Jval.newObject();
                Jval.JsonMap obj = val.asObject();
                obj.put("x", Jval.valueOf(block.x));
                obj.put("y", Jval.valueOf(block.y));
                obj.put("block", Jval.valueOf(block.block.name));
                obj.put("cost", Jval.valueOf(block.cost));
                obj.put("invincible", Jval.valueOf(block.invincible));
                array.add(val);
            }
            Vars.state.rules.tags.put(BLOCKS, array.toString());
        }
        private final Seq<Castle.CastleMiner> miners = new Seq<>(Castle.CastleMiner.class);
        public Seq<Castle.CastleMiner> miners() { return miners; }
        public void addMiner(Castle.CastleMiner miner) {
            miners.addUnique(miner);
            saveMiners();
        }
        public void remMiner(Castle.CastleMiner miner) {
            miners.remove(miner);
            saveMiners();
        }
        public void saveMiners() {
            Jval.JsonArray array = new Jval.JsonArray();
            for (int i = 0; i < miners.size; i++) {
                Castle.CastleMiner miner = miners.items[i];
                if (miner.block == null || miner.item == null) continue;

                Jval val = Jval.newObject();
                Jval.JsonMap obj = val.asObject();
                obj.put("x", Jval.valueOf(miner.x));
                obj.put("y", Jval.valueOf(miner.y));
                obj.put("block", Jval.valueOf(miner.block.name));
                obj.put("cost", Jval.valueOf(miner.cost));
                obj.put("amount", Jval.valueOf(miner.amount));
                obj.put("interval", Jval.valueOf(miner.interval));
                obj.put("item", Jval.valueOf(miner.item.name));
                array.add(val);
            }
            Vars.state.rules.tags.put(MINERS, array.toString());
        }

        private void autoPort() {
            Vars.world.tiles.eachTile(tile -> {
                if (!tile.isCenter()) return;
                if (tile.block() == Blocks.sorter) {
                    if (!(tile.build instanceof Sorter.SorterBuild)) return;
                    Item item = ((Sorter.SorterBuild) tile.build).sortItem;
                    // A sorter with no filter is not a miner. Null item crashed saveMiners on item.name.
                    if (item == null) return;
                    boolean serpulo = Vars.state.rules.planet == Planets.sun ? item.isOnPlanet(Planets.serpulo) : Vars.state.rules.planet == Planets.serpulo;
                    Block drill = serpulo ? Blocks.laserDrill : Blocks.impactDrill;
                    int shift = (drill.size + 1) % 2;
                    miners.add(new CastleMiner(drill, tile.x + shift, tile.y + shift, itemCostFor(item), itemAmountFor(item), itemIntervalFor(item), item));
                    Core.app.post(() -> tile.setNet(Blocks.air));
                } else if (tile.block() instanceof Turret) {
                    Block block = tile.block();
                    int shift = (block.size + 1) % 2;
                    blocks.add(new CastleBlock(block, tile.x + shift, tile.y + shift, blockCostFor(block), true));
                    Core.app.post(() -> tile.setNet(Blocks.air));
                }
            });
            saveBlocks();
            saveMiners();
            // TODO: Add a better mechanism for this.
            if (MVars.mapEditor != null) MVars.mapEditor.clearOp();
        }

        @Override
        public void dataFixer() {
            // Edit In-Game keeps the editor tiles. autoPort would delete every turret and sorter from the map being edited.
            if (Vars.state.rules.editor) return;
            if (MVars.rules.originalPatchVer < 9) autoPort();
        }
    }


    public static class CastleBlock {
        private final int x;
        private final int y;
        private final Block block;
        private final int cost;
        private final boolean invincible;

        public CastleBlock(Block block, int x, int y,int cost, boolean invincible) {
            this.block = block;
            this.x = x;
            this.y = y;
            this.cost = cost;
            this.invincible = invincible;
        }

        public boolean contains(int x, int y) {
            return x >= this.x && x < this.x + this.block.size &&
                    y >= this.y && y < this.y + this.block.size;
        }
        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CastleBlock)) return false;
            CastleBlock other = (CastleBlock) o;
            return x == other.x && y == other.y && block == other.block;
        }

        @Override
        public int hashCode() {
            return x * 31 + y * 17 + block.hashCode();
        }
    }
    public static class CastleMiner {
        private final int x;
        private final int y;
        private final Block block;
        private final int cost;
        private final int amount;
        private final int interval;
        private final Item item;

        public CastleMiner(Block block, int x, int y,int cost,int amount,int interval, Item item) {
            this.block = block;
            this.x = x;
            this.y = y;
            this.cost = cost;
            this.amount = amount;
            this.interval = interval;
            this.item = item;
        }

        public boolean contains(int x, int y) {
            return x >= this.x && x < this.x + this.block.size &&
                    y >= this.y && y < this.y + this.block.size;
        }
    }
}
