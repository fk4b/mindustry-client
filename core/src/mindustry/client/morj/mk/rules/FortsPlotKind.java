package mindustry.client.morj.mk.rules;

import arc.util.Nullable;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import mindustry.client.morj.mk.ui.RulesWrite;
import mindustry.game.Team;

public abstract class FortsPlotKind {
    public static final String PREFIX = Forts.PREFIX+".plot";

    @RequiredArgsConstructor(access = AccessLevel.PROTECTED)
    public abstract class Impl {
        protected final RulesContext rc;

        public final FortsPlotKind factory() { return FortsPlotKind.this; }
        public final String name() { return factory().name(); }

        abstract void remove();

        public abstract void writeRules(RulesWrite write);
        public abstract void writeTeamRules(Team team, RulesWrite write);
        public abstract void drawEditorGuides();
        public abstract void setPlotInfo(int x, int y, FortsPlotState state, Team team);
        public abstract void onStart();
        public abstract void editingResumed();
        public abstract void save();
    }

    public abstract String name();
    abstract Impl create(RulesContext rc);

    private static FortsPlotKind[] plotKinds;

    private static boolean initialized = false;
    public static void init() {
        if (initialized) throw new IllegalStateException("Already initialized");
        initialized = true;

        plotKinds = new FortsPlotKind[] {
                new FortsPlotKindSquare(),
                new FortsPlotKind() {
                    @Override
                    public String name() {
                        return "none";
                    }

                    @Override
                    Impl create(RulesContext rc) {
                        return new Impl(rc) {
                            @Override void remove() {}
                            @Override public void writeRules(RulesWrite write) {}
                            @Override public void writeTeamRules(Team team, RulesWrite write) {}
                            @Override public void drawEditorGuides() {}
                            @Override public void setPlotInfo(int x, int y, FortsPlotState state, Team team) {}
                            @Override public void onStart() {}
                            @Override public void editingResumed() {}
                            @Override public void save() {}
                        };
                    }
                },
        };
    }

    public static @Nullable FortsPlotKind forName(String name) {
        for (FortsPlotKind factory : plotKinds) if (factory.name().equals(name)) return factory;
        return null;
    }

    public static FortsPlotKind[] values() {
        return plotKinds;
    }
}
