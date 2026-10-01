package mindustry.client.morj;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.world.*;

import static mindustry.Vars.*;

/**
 * Floating map in the style of MI2: its own zoom, player names, spawns,
 * a camera rectangle, and a click that moves the view. Closing it turns the feature off
 * and the ordinary corner map comes back.
 */
public class Mi2Map extends Table{
    private static final float baseSize = 16f;
    private float zoom = 4f;
    private float mapSize = 220f;
    private boolean placed;
    private final Rect view = new Rect();
    private Element canvas;

    public Mi2Map(){
        background(Tex.pane);
        touchable = Touchable.enabled;
        margin(4f);
        mapSize = Mathf.clamp(Core.settings.getFloat("mi2map-size", 220f), 140f, 520f);
        zoom = Mathf.clamp(Core.settings.getFloat("mi2map-zoom", 4f), 1f, 12f);

        table(head -> {
            head.touchable = Touchable.enabled;
            ImageButton drag = head.button(Icon.move, Styles.cleari, () -> {}).size(26f).get();
            float[] last = new float[2];
            drag.addListener(new InputListener(){
                @Override public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                    last[0] = x;
                    last[1] = y;
                    return true;
                }
                @Override public void touchDragged(InputEvent event, float x, float y, int pointer){
                    moveBy(x - last[0], y - last[1]);
                    clamp();
                }
                @Override public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                    Core.settings.put("mi2map-x", Mi2Map.this.x);
                    Core.settings.put("mi2map-y", Mi2Map.this.y);
                }
            });
            head.label(() -> Core.bundle.get("client.mi2map.title")).growX().left().get().setFontScale(0.8f);
            head.button(Icon.players, Styles.clearTogglei, () -> Core.settings.put("mi2map-names", !Core.settings.getBool("mi2map-names", true)))
                .size(26f).checked(b -> Core.settings.getBool("mi2map-names", true))
                .tooltip("@client.mi2map.names");
            head.button(Icon.units, Styles.clearTogglei, () -> Core.settings.put("mi2map-spawns", !Core.settings.getBool("mi2map-spawns", true)))
                .size(26f).checked(b -> Core.settings.getBool("mi2map-spawns", true))
                .tooltip("@client.mi2map.spawns");
            head.button(Icon.upOpen, Styles.cleari, () -> setMapSize(mapSize + 40f)).size(26f).tooltip("@client.mi2map.bigger");
            head.button(Icon.downOpen, Styles.cleari, () -> setMapSize(mapSize - 40f)).size(26f).tooltip("@client.mi2map.smaller");
            head.button(Icon.map, Styles.cleari, () -> ui.minimapfrag.toggle()).size(26f).tooltip("@client.mi2map.full");
            head.button(Icon.cancel, Styles.cleari, () -> Core.settings.put("mi2map", false)).size(26f);
        }).growX().row();

        canvas = new Element(){
            @Override
            public void draw(){
                if(player == null || renderer.minimap.getTexture() == null || world.width() <= 0) return;
                if(!clipBegin()) return;
                float prev = renderer.minimap.getZoom();
                renderer.minimap.setZoom(zoom);
                setView();
                Draw.color();
                Draw.rect(renderer.minimap.getRegion(), x + width / 2f, y + height / 2f, width, height);
                renderer.minimap.drawEntities(x, y, width, height, false);
                renderer.minimap.setZoom(prev);
                drawOverlay();
                clipEnd();
            }
        };
        add(canvas).size(mapSize).padTop(2f);

        canvas.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                pan(x, y);
                return true;
            }
            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer){
                pan(x, y);
            }
            @Override
            public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY){
                zoom = Mathf.clamp(zoom + amountY * Math.max(0.15f, zoom * 0.12f), 1f, maxZoom());
                Core.settings.put("mi2map-zoom", zoom);
                return true;
            }
        });

        visible(() -> ui.hudfrag.shown && state.isGame() && Core.settings.getBool("mi2map", false));
        update(() -> {
            if(!placed && Core.graphics.getWidth() > 0){
                pack();
                if(Core.settings.has("mi2map-x")){
                    setPosition(Core.settings.getFloat("mi2map-x"), Core.settings.getFloat("mi2map-y"));
                }else{
                    setPosition(Core.graphics.getWidth() - getWidth() - 8f, Core.graphics.getHeight() - getHeight() - 8f);
                }
                placed = true;
            }
            clamp();
            Element hover = Core.scene.getHoverElement();
            if(hover != null && hover.isDescendantOf(this)) requestScroll();
        });
    }

    public static void build(Group parent){
        parent.addChild(new Mi2Map());
    }

    private void setMapSize(float size){
        mapSize = Mathf.clamp(size, 140f, 520f);
        Core.settings.put("mi2map-size", mapSize);
        getCell(canvas).size(mapSize);
        invalidateHierarchy();
        pack();
    }

    private float maxZoom(){
        if(world.width() <= 0) return 8f;
        return Math.max(1f, Math.min(world.width(), world.height()) / baseSize / 2f);
    }

    private void setView(){
        float sz = baseSize * zoom;
        float dx = Mathf.clamp(Core.camera.position.x / tilesize, sz, Math.max(sz, world.width() - sz));
        float dy = Mathf.clamp(Core.camera.position.y / tilesize, sz, Math.max(sz, world.height() - sz));
        view.set((dx - sz) * tilesize, (dy - sz) * tilesize, sz * 2f * tilesize, sz * 2f * tilesize);
    }

    private void pan(float x, float y){
        if(view.width < 1f) setView();
        float wx = view.x + (x / Math.max(canvas.getWidth(), 1f)) * view.width;
        float wy = view.y + (y / Math.max(canvas.getHeight(), 1f)) * view.height;
        control.input.panCamera(Tmp.v1.set(wx, wy).clamp(-tilesize / 2f, -tilesize / 2f, world.unitWidth() + tilesize / 2f, world.unitHeight() + tilesize / 2f));
    }

    private void drawOverlay(){
        float w = canvas.getWidth();
        float h = canvas.getHeight();
        if(view.width < 1f || w < 1f) return;

        if(Core.settings.getBool("mi2map-spawns", true) && state.hasSpawns() && state.rules.waves && !state.rules.hideSpawns){
            TextureRegion icon = Icon.units.getRegion();
            Draw.color(state.rules.waveTeam.color);
            for(Tile tile : spawner.getSpawns()){
                float sx = canvas.x + (tile.worldx() - view.x) / view.width * w;
                float sy = canvas.y + (tile.worldy() - view.y) / view.height * h;
                Draw.rect(icon, sx, sy, 14f, 14f);
            }
            Draw.color();
        }

        Rect cam = Core.camera.bounds(Tmp.r1);
        float x1 = canvas.x + (cam.x - view.x) / view.width * w;
        float y1 = canvas.y + (cam.y - view.y) / view.height * h;
        Lines.stroke(1.25f);
        Draw.color(Pal.accent);
        Lines.rect(x1, y1, cam.width / view.width * w, cam.height / view.height * h);
        Draw.reset();

        if(Core.settings.getBool("mi2map-names", true)){
            Font font = Fonts.outline;
            boolean ints = font.usesIntegerPositions();
            font.setUseIntegerPositions(false);
            font.getData().setScale(0.55f);
            for(Player other : Groups.player){
                if(other.dead()) continue;
                float sx = canvas.x + (other.x - view.x) / view.width * w;
                float sy = canvas.y + (other.y - view.y) / view.height * h;
                if(sx < canvas.x || sy < canvas.y || sx > canvas.x + w || sy > canvas.y + h) continue;
                font.setColor(other.team().color);
                font.draw(other.name, sx, sy + 10f, Align.center);
            }
            font.getData().setScale(1f);
            font.setUseIntegerPositions(ints);
            font.setColor(Color.white);
        }
    }

    private void clamp(){
        if(getWidth() < 1f) return;
        float maxX = Math.max(0f, Core.graphics.getWidth() - getWidth());
        float maxY = Math.max(0f, Core.graphics.getHeight() - getHeight());
        setPosition(Mathf.clamp(x, 0f, maxX), Mathf.clamp(y, 0f, maxY));
    }
}
