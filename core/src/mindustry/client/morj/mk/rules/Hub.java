package mindustry.client.morj.mk.rules;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.GlyphLayout;
import arc.graphics.g2d.Lines;
import arc.math.geom.Vec2;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.pooling.Pools;
import arc.util.serialization.Jval;
import mindustry.client.morj.mk.MVars;
import mindustry.Vars;
import mindustry.ui.Fonts;

import java.util.Iterator;

public class Hub extends Gamemode {
    public static final String PREFIX = MRules.PREFIX+".hub";
    public static final String NAME = "hub";

    public static final String SERVERS = PREFIX+".servers";

    @Override
    public String name() { return NAME; }

    @Override
    Impl create(RulesContext rc) {
        return new Impl(rc);
    }

    public class Impl extends Gamemode.Impl {
        protected Impl(RulesContext rc) {
            super(rc);

            try (TagRead read = TagRead.of(rc.rules)) {
                try {
                    Jval.JsonArray array = Jval.read(read.r(SERVERS, "[]")).asArray();
                    array.each(x -> {
                        try {
                            Jval.JsonMap obj = x.asObject();
                            assert obj.get("version").asInt() == 1;
                            String serverName = obj.get("name").asString();
                            int serverX = obj.get("x").asInt();
                            int serverY = obj.get("y").asInt();
                            int serverSize = obj.get("size").asInt();
                            assert serverSize > 0;

                            servers.add(new Server(serverName, serverX, serverY, serverSize));
                        } catch (Exception e) {
                            Log.err("Failed to parse server", e);
                            Vars.ui.showException("Failed to parse server", e);
                        }
                    });
                } catch (Exception e) {
                    Log.err("Failed to parse servers", e);
                    Vars.ui.showException("Failed to parse servers", e);
                }
            }
        }

        private final Seq<Server> servers = new Seq<>(Server.class);
        public Seq<Server> servers() { return servers; }
        public void addServer(Server server) {
            servers.addUnique(server);
            saveServers();
        }
        public void remServer(Server server) {
            servers.remove(server);
            saveServers();
        }
        public void saveServers() {
            Jval.JsonArray array = new Jval.JsonArray();
            for (int i = 0; i < servers.size; i++) {
                Server server = servers.items[i];

                Jval val = Jval.newObject();
                Jval.JsonMap obj = val.asObject();
                obj.put("version", Jval.valueOf(1));
                obj.put("x", Jval.valueOf(server.x));
                obj.put("y", Jval.valueOf(server.y));
                obj.put("size", Jval.valueOf(server.size));
                obj.put("name", Jval.valueOf(server.name));
                array.add(val);
            }
            Vars.state.rules.tags.put(SERVERS, array.toString());
        }

        @Override
        void remove() {
            rc.rules.tags.remove(SERVERS);
        }

        @Override
        protected void _setRules() {
            rc.rules.revealedBlocks.clear();
            rc.rules.bannedBlocks.addAll(Vars.content.blocks().select(x -> !x.isHidden()));
            rc.rules.hideBannedBlocks = true;
            rc.rules.waves = false;
            rc.rules.loadout.clear();
            rc.rules.reactorExplosions = false;
            rc.rules.canGameOver = false;
            rc.rules.modeName = "Hub";
            rc.customRules.overdriveIgnoresCheat(false);
        }

        @Override
        public void drawEditorGuides() {
            float lineWidth = Core.settings.getInt("mindurka.guideslinewidth", 1);
            if (Core.settings.getBool("mindurka.guidesoutline", false)) {
                for (int i = 0; i < servers.size; i++) {
                    Server server = servers.items[i];

                    // Draw.reset();
                    Vec2 v = MVars.mapView.unproject(server.x, server.y);
                    float sx = v.x;
                    float sy = v.y;
                    v = MVars.mapView.unproject(server.x + server.size, server.y + server.size);

                    Lines.stroke(Scl.scl(lineWidth + 2));
                    Draw.color(Color.black);
                    Lines.rect(sx - 1, sy - 1, v.x - sx + 2, v.y - sy + 2);

                    GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
                    // If there's a better way of doing this I may consider it.
                    Fonts.outline.getData().setScale(0.25f * Scl.scl(20) / Vars.renderer.camerascale);
                    layout.setText(Fonts.outline, server.name);

                    float x = (v.x + sx) / 2 + layout.width / 2;
                    float y = (v.y + sy) / 2 + layout.height / 2;

                    Fonts.outline.draw(server.name, x, y, 0, 0, false);

                    Pools.free(layout);
                    Fonts.outline.getData().setScale(1f);
                    Fonts.outline.setColor(Color.white);
                }
                Draw.reset();
            }

            for (int i = 0; i < servers.size; i++) {
                Server server = servers.items[i];

                // Draw.reset();
                Vec2 v = MVars.mapView.unproject(server.x, server.y);
                float sx = v.x;
                float sy = v.y;
                v = MVars.mapView.unproject(server.x + server.size, server.y + server.size);

                Lines.stroke(Scl.scl(lineWidth));
                Draw.color(Color.white);
                Lines.rect(sx, sy, v.x - sx, v.y - sy);

                GlyphLayout layout = Pools.obtain(GlyphLayout.class, GlyphLayout::new);
                // If there's a better way of doing this I may consider it.
                Fonts.outline.getData().setScale(0.25f * Scl.scl(20) / Vars.renderer.camerascale);
                layout.setText(Fonts.outline, server.name);

                float x = (v.x + sx) / 2 + layout.width / 2;
                float y = (v.y + sy) / 2 + layout.height / 2;

                Fonts.outline.draw(server.name, x, y, 0, 0, false);

                Pools.free(layout);
                Fonts.outline.getData().setScale(1f);
                Fonts.outline.setColor(Color.white);
            }
            Draw.reset();
            Lines.stroke(1f);
        }
    }

    public static class Server {
        private final String name;
        private final int x;
        private final int y;
        private final int size;

        public Server(String name, int x, int y, int size) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.size = size;
        }

        public boolean contains(int x, int y) {
            return x >= this.x && x < this.x + size &&
                    y >= this.y && y < this.y + size;
        }
    }
}
