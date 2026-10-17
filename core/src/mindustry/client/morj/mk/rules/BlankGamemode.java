package mindustry.client.morj.mk.rules;

public class BlankGamemode extends Gamemode {
    private final String name;

    public BlankGamemode(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    Impl create(RulesContext rc) {
        return new Impl(rc);
    }

    class Impl extends Gamemode.Impl {
        protected Impl(RulesContext rc) {
            super(rc);
        }

        @Override
        void remove() {}
        @Override
        protected void _setRules() {}
    }
}
