package mindustry.client.morj.mk.rules;

import arc.struct.OrderedMap;

import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.type.Item;
import mindustry.type.StatusEffect;
import mindustry.type.UnitType;
import mindustry.world.Block;

public class CastleCosts {
    public static OrderedMap<UnitType, UnitData> units;
    public static OrderedMap<StatusEffect, EffectData> effects;

    public static OrderedMap<Block, Integer> turrets;
    public static OrderedMap<Item, ItemData> items;

    public static void load() {
        units = OrderedMap.of(
                UnitTypes.dagger, new UnitData(60, 0, 15),
                UnitTypes.mace, new UnitData(170, 1, 50),
                UnitTypes.fortress, new UnitData(550, 4, 200),
                UnitTypes.scepter, new UnitData(3000, 20, 750),
                UnitTypes.reign, new UnitData(10000, 60, 1500),

                UnitTypes.crawler, new UnitData(50, 0, 10),
                UnitTypes.atrax, new UnitData(180, 1, 60),
                UnitTypes.spiroct, new UnitData(600, 4, 200),
                UnitTypes.arkyid, new UnitData(4300, 20, 1000),
                UnitTypes.toxopid, new UnitData(13000, 50, 1750),

                UnitTypes.nova, new UnitData(75, 0, 15),
                UnitTypes.pulsar, new UnitData(180, 1, 50),
                UnitTypes.quasar, new UnitData(600, 4, 200),
                UnitTypes.vela, new UnitData(3800, 22, 750),
                UnitTypes.corvus, new UnitData(15000, 70, 1500),

                UnitTypes.risso, new UnitData(175, 1, 24),
                UnitTypes.minke, new UnitData(250, 1, 70),
                UnitTypes.bryde, new UnitData(1000, 5, 200),
                UnitTypes.sei, new UnitData(5500, 24, 900),
                UnitTypes.omura, new UnitData(15000, 65, 2000),

                UnitTypes.retusa, new UnitData(130, 0, 50),
                UnitTypes.oxynoe, new UnitData(625, 3, 150),
                UnitTypes.cyerce, new UnitData(1400, 6, 200),
                UnitTypes.aegires, new UnitData(7000, 16, 3000),
                UnitTypes.navanax, new UnitData(13500, 70, 1350),

                UnitTypes.flare, new UnitData(80, 0, 20),
                UnitTypes.horizon, new UnitData(200, 1, 70),
                UnitTypes.zenith, new UnitData(700, 4, 150),
                UnitTypes.antumbra, new UnitData(4100, 23, 850),
                UnitTypes.eclipse, new UnitData(12000, 60, 1250),

                UnitTypes.poly, new UnitData(350, 1, 90),
                UnitTypes.mega, new UnitData(900, 5, 200),
                UnitTypes.quad, new UnitData(5250, 27, 900),
                UnitTypes.oct, new UnitData(13000, 65, 1300),

                UnitTypes.stell, new UnitData(260, 2, 100),
                UnitTypes.locus, new UnitData(800, 4, 250),
                UnitTypes.precept, new UnitData(2000, 12, 600),
                UnitTypes.vanquish, new UnitData(5000, 26, 1000),
                UnitTypes.conquer, new UnitData(10000, 60, 1700),

                UnitTypes.merui, new UnitData(280, 2, 100),
                UnitTypes.cleroi, new UnitData(900, 4, 400),
                UnitTypes.anthicus, new UnitData(2450, 14, 750),
                UnitTypes.tecta, new UnitData(5500, 27, 1100),
                UnitTypes.collaris, new UnitData(11000, 55, 1900),

                UnitTypes.elude, new UnitData(300, 2, 110),
                UnitTypes.avert, new UnitData(900, 4, 300),
                UnitTypes.obviate, new UnitData(2200, 13, 750),
                UnitTypes.quell, new UnitData(4750, 25, 1500),
                UnitTypes.disrupt, new UnitData(11500, 45, 2300),

                UnitTypes.renale, new UnitData(1500, 6, 500),
                UnitTypes.latum, new UnitData(20000, 80, 5000)
        );

        effects = OrderedMap.of(
                StatusEffects.overclock, new EffectData(4000, 20, 20, true),
                StatusEffects.overdrive, new EffectData(12000, 30, 30, true),
                StatusEffects.boss, new EffectData(36000, 40, 40, true),
                StatusEffects.shielded, new EffectData(72000, 10, 10, true),

                StatusEffects.sporeSlowed, new EffectData(12000, 25, 25, false),
                StatusEffects.electrified, new EffectData(24000, 20, 20, false),
                StatusEffects.sapped, new EffectData(36000, 15, 15, false),
                StatusEffects.unmoving, new EffectData(96000, 5, 25, false)
        );

        turrets = OrderedMap.of(
                Blocks.duo, 50,
                Blocks.scatter, 150,
                Blocks.scorch, 200,
                Blocks.hail, 250,
                Blocks.wave, 250,
                Blocks.lancer, 250,
                Blocks.arc, 100,
                Blocks.swarmer, 1450,
                Blocks.salvo, 600,
                Blocks.fuse, 1350,
                Blocks.ripple, 1400,
                Blocks.cyclone, 2000,
                Blocks.foreshadow, 4500,
                Blocks.spectre, 4000,
                Blocks.meltdown, 3500,
                Blocks.segment, 1000,
                Blocks.parallax, 500,
                Blocks.tsunami, 800,

                Blocks.breach, 500,
                Blocks.diffuse, 800,
                Blocks.sublimate, 2500,
                Blocks.titan, 2000,
                Blocks.disperse, 3200,
                Blocks.afflict, 2250,
                Blocks.lustre, 4500,
                Blocks.scathe, 4250,
                Blocks.smite, 5000,
                Blocks.malign, 12500
        );

        items = OrderedMap.of(
                Items.copper, new ItemData(250, 300, 48),
                Items.lead, new ItemData(300, 300, 48),
                Items.metaglass, new ItemData(500, 300, 48),
                Items.graphite, new ItemData(400, 300, 48),
                Items.titanium, new ItemData(750, 300, 48),
                Items.thorium, new ItemData(1000, 300, 48),
                Items.silicon, new ItemData(500, 300, 48),
                Items.plastanium, new ItemData(1200, 300, 48),
                Items.phaseFabric, new ItemData(1500, 300, 48),
                Items.surgeAlloy, new ItemData(1800, 300, 48),

                Items.beryllium, new ItemData(500, 300, 48),
                Items.tungsten, new ItemData(1000, 300, 48),
                Items.oxide, new ItemData(1500, 300, 48),
                Items.carbide, new ItemData(1800, 300, 48)
        );
    }



    public static class UnitData {
        public final int cost, income, drop;
        public UnitData(int cost, int income, int drop) {
            this.cost = cost; this.income = income; this.drop = drop;
        }
        public int cost() { return cost; }
        public int income() { return income; }
        public int drop() { return drop; }
    }


    public static class EffectData {
        public final int cost, duration, delay;
        public final boolean ally;
        public EffectData(int cost, int duration, int delay, boolean ally) {
            this.cost = cost; this.duration = duration; this.delay = delay; this.ally = ally;
        }
        public int cost() { return cost; }
        public int duration() { return duration; }
        public int delay() { return delay; }
        public boolean ally() {return ally;}
    }

    public static class ItemData {
        public final int cost, interval, amount;
        public ItemData(int cost, int interval, int amount) {
            this.cost = cost; this.interval = interval; this.amount = amount;
        }
        public int cost() { return cost; }
        public int interval() { return interval; }
        public int amount() { return amount; }
    }
}
