package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.ScissorStack;
import arc.input.GestureDetector;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.event.Touchable;
import arc.scene.ui.layout.Scl;
import arc.struct.IntSet;
import arc.util.Nullable;
import arc.util.Tmp;
import mindustry.client.morj.mk.MVars;
import mindustry.client.morj.mk.rules.Gamemode;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.editor.MapView;
import mindustry.graphics.Pal;
import mindustry.ui.GridImage;

public class OMapView extends MapView {
    private final GridImage image = new GridImage(0, 0);

    private @Nullable PreviewToolContext previewMap = null;

    private @Nullable MouseAction mousea = null;
    public @Nullable SpecialEditorAction editorAction = null;

    private final Rect rect = new Rect();
    private float offsetx, offsety;
    private float zoom = 1f;
    private float mousex, mousey;

    private float startx, starty;
    private float prevx = -999, prevy = -999;
    private boolean dragging;
    public boolean redrawPreview;

    public float zoom(){return zoom;}

    public static class Touch {
        public final int pointer;
        public float x;
        public float y;
        public final IntSet buttons = new IntSet(1);

        public Touch(int pointer) {
            this.pointer = pointer;
            this.x = x;
            this.y = y;
        }

        public Touch xy(float x, float y) {
            this.x = x;
            this.y = y;
            return this;
        }
    }
    public Touch[] activeTouches = new Touch[2];
    public boolean containsTouch(int pointer) {
        for (Touch touch : activeTouches) {
            if (touch == null) continue;
            if (touch.pointer != pointer) continue;
            return true;
        }
        return false;
    }
    public int activeTouches() {
        int num = 0;
        for (Touch touch : activeTouches) if (touch != null) num += 1;
        return num;
    }
    public boolean addTouch(int pointer, KeyCode button, float x, float y) {
        for (int i = 0; i < activeTouches.length; i++) {
            if (activeTouches[i] == null) {
                activeTouches[i] = new Touch(pointer).xy(x, y);
                activeTouches[i].buttons.add(button.ordinal());
                return true;
            } else if (activeTouches[i].pointer == pointer) {
                activeTouches[i].xy(x, y);
                activeTouches[i].buttons.add(button.ordinal());
                return true;
            }
        }
        return false;
    }
    public void removeTouch(int pointer, KeyCode button) {
        for (int i = 0; i < activeTouches.length; i++) {
            if (activeTouches[i] == null) continue;
            activeTouches[i].buttons.remove(button.ordinal());
            if (activeTouches[i].buttons.size == 0) activeTouches[i] = null;
        }
    }
    public Touch touch(int pointer) {
        for (Touch touch : activeTouches) {
            if (touch == null) continue;
            if (touch.pointer != pointer) continue;
            return touch;
        }
        return null;
    }

    private final Vec2 vec = new Vec2();

    public OMapView() {
        MVars.oldMapView.setTool(mindustry.editor.EditorTool.zoom);

        Core.input.getInputProcessors().insert(0, new GestureDetector(20, 0.5f, 2, 0.15f, this));
        touchable = Touchable.enabled;

        { // No need to process Mindustry's events
            @Nullable InputListener listener = (InputListener) getListeners().find(it -> it instanceof InputListener);
            if (listener != null) removeListener(listener);
        }
        addListener(new InputListener() {
            @Override
            public boolean mouseMoved(InputEvent event, float x, float y) {
                mousex = x;
                mousey = y;

                requestScroll();

                return false;
            }

            @Override
            public void enter(InputEvent event, float x, float y, int pointer, Element fromActor) {
                requestScroll();
            }

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (pointer == 0) {
                    mousex = x;
                    mousey = y;
                }

                if (!addTouch(pointer, button, x, y)) return false;

                if (activeTouches() >= 2) {
                    if (!(mousea instanceof MouseAction.TouchDrag)) {
                        MVars.mapEditor.undoCurrentOp();

                        mousea = MouseAction.TouchDrag.begin();
                    }
                }

                if (mousea instanceof MouseAction.TouchDrag) {
                    return true;
                }

                if (mousea != null && !(mousea instanceof MouseAction.Cancelled) && activeTouches[0].buttons.size > 1) {
                    MouseAction prev = mousea;
                    mousea = MouseAction.Cancelled.begin();
                    dragging = false;
                    if (!prev.cancelForgets()) prev.end(x, y);
                    return true;
                }

                // Krita/Voidsprite/iBIS/AnyImageEdtiorInExistence-like binds cuz they are infinitely better than
                // whatever the fuck Anuke cooked, and you're obligated to agree.
                if (Vars.mobile || button == KeyCode.mouseLeft) {
                    if (editorAction != null) {
                        mousea = MouseAction.Draw.Cancelled.begin();
                        mousex = x;
                        mousey = y;
                        dragging = true;
                        return true;
                    } else mousea = MVars.toolOptions.mode.drawAction(x, y);
                } else if (button == KeyCode.mouseRight) {
                    // No, I refuse to use the lastTool bullshit for this.
                    if (editorAction == null) mousea = MVars.toolOptions.mode.eraseAction(x, y);
                    else {
                        editorAction = null;
                        redrawPreview = true;
                        return true;
                    }
                } else if (button == KeyCode.mouseMiddle) {
                    if (Core.input.ctrl())
                        mousea = MouseAction.CtrlZoom.begin(y);
                    else
                        mousea = MouseAction.Drag.begin(x, y);
                } else return true;

                mousex = x;
                mousey = y;
                startx = x;
                starty = y;
                dragging = true;

                mousea.move(mousex, mousey);

                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (!containsTouch(pointer)) return;
                touch(pointer).xy(x, y);

                if (activeTouches[0] != null && activeTouches[0].pointer == pointer) {
                    mousex = x;
                    mousey = y;
                }

                if (mousea != null) {
                    mousea.move(x, y);
                }
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                if (!containsTouch(pointer)) return;

                boolean isMain = activeTouches[0] != null && activeTouches[0].pointer == pointer;
                removeTouch(pointer, button);

                if (mousea != null) {
                    if (mousea instanceof MouseAction.TouchDrag) {
                        if (activeTouches() == 0) mousea = null;
                        return;
                    }
                    if (mousea instanceof MouseAction.Cancelled) {
                        SpecialEditorAction prev = editorAction;
                        editorAction = null;
                        if (prev != null) prev.clicked(OMapView.this, x, y);
                    }

                    if (!isMain) return;

                    mousea.end(x, y);
                    if (mousea instanceof MouseAction.Draw || mousea instanceof MouseAction.Erase) {
                        MVars.mapEditor.flushOp();
                    }
                    mousea = null;
                    dragging = false;
                    redrawPreview = true;
                }
            }

            @Override
            public boolean scrolled(InputEvent event, float x, float y, float amountX, float amountY) {
                logScaleBy(amountY);

                return true;
            }
        });

        update(() -> {
            if (mousea != null) mousea.update();
        });
    }

    @Override
    public mindustry.editor.EditorTool getTool() {
        return null;
    }

    @Override
    public Point2 project(float x, float y){
        float ratio = 1f / ((float)Vars.editor.width() / Vars.editor.height());
        float size = Math.min(width, height);
        float sclwidth = size * zoom;
        float sclheight = size * zoom * ratio;
        x = (x - getWidth() / 2 + sclwidth / 2 - offsetx * zoom) / sclwidth * Vars.editor.width();
        y = (y - getHeight() / 2 + sclheight / 2 - offsety * zoom) / sclheight * Vars.editor.height();

        if(Vars.editor.drawBlock.size % 2 == 0){
            return Tmp.p1.set((int)(x - 0.5f), (int)(y - 0.5f));
        }else{
            return Tmp.p1.set((int)x, (int)y);
        }
    }

    public Vec2 unproject(int x, int y){
        float ratio = 1f / ((float)MVars.mapEditor.width() / MVars.mapEditor.height());
        float size = Math.min(width, height);
        float sclwidth = size * zoom;
        float sclheight = size * zoom * ratio;
        float px = ((float)x / MVars.mapEditor.width()) * sclwidth + offsetx * zoom - sclwidth / 2 + getWidth() / 2;
        float py = ((float)y / MVars.mapEditor.height()) * sclheight
                + offsety * zoom - sclheight / 2 + getHeight() / 2;
        return vec.set(px, py).add(this.x, this.y);
    }

    public Vec2 unproject(float worldX, float worldY) {
        float ratio = 1f / ((float) MVars.mapEditor.width() / MVars.mapEditor.height());
        float size = Math.min(width, height);
        float sclwidth = size * zoom;
        float sclheight = size * zoom * ratio;

        float px = (worldX / MVars.mapEditor.width()) * sclwidth
                + offsetx * zoom
                - sclwidth / 2f
                + getWidth() / 2f;

        float py = (worldY / MVars.mapEditor.height()) * sclheight
                + offsety * zoom
                - sclheight / 2f
                + getHeight() / 2f;

        return vec.set(px, py).add(this.x, this.y);
    }

    public Vec2 unprojectCenter(int tx, int ty) {
        float cx = tx + 0.5f;
        float cy = ty + 0.5f;
        return unproject(cx, cy);
    }

    public void moveByScaled(float dx, float dy) {
        offsetx += dx / zoom;
        offsety += dy / zoom;
    }

    public void logScaleBy(float dy) {
        dy *= Core.settings.getInt("mindurka.zoomsensitivity", 100) / 100f;
        float linearZoom = Mathf.log2(zoom);
        linearZoom -= dy;
        if (linearZoom >= 20f) linearZoom = 20f;
        if (linearZoom < -10) linearZoom = -10;
        zoom = Mathf.pow(2f, linearZoom);
    }

    public void mouseAction(MouseAction newAction) {
        mousea = newAction;
    }

    public float mousex() { return mousex; }
    public float mousey() { return mousey; }

    @Override
    public void draw() {
        float ratio = 1f / ((float) MVars.mapEditor.width() / MVars.mapEditor.height());
        float size = Math.min(width, height);
        float sclwidth = size * zoom;
        float sclheight = size * zoom * ratio;
        float centerx = x + width / 2 + offsetx * zoom;
        float centery = y + height / 2 + offsety * zoom;
        float scaling = zoom * Math.min(width, height) / MVars.mapEditor.width();

        image.setImageSize(MVars.mapEditor.width(), MVars.mapEditor.height());

        if (!ScissorStack.push(rect.set(x + Core.scene.marginLeft, y + Core.scene.marginBottom, width, height))) {
            return;
        }

        Draw.color(Pal.remove);
        Lines.stroke(2f);
        Lines.rect(centerx - sclwidth / 2 - 1, centery - sclheight / 2 - 1, sclwidth + 2, sclheight + 2);
        Vars.editor.renderer.draw(centerx - sclwidth / 2 + Core.scene.marginLeft, centery - sclheight / 2 + Core.scene.marginBottom, sclwidth, sclheight);
        Draw.reset();

        if (isGrid()) {
            Draw.color(Color.gray);
            image.setBounds(centerx - sclwidth / 2, centery - sclheight / 2, sclwidth, sclheight);
            image.draw();

            Lines.stroke(2f);
            Draw.color(Pal.bulletYellowBack);
            Lines.line(centerx - sclwidth/2f, centery - sclheight/4f, centerx + sclwidth/2f, centery - sclheight/4f);
            Lines.line(centerx - sclwidth/4f, centery - sclheight/2f, centerx - sclwidth/4f, centery + sclheight/2f);
            Lines.line(centerx - sclwidth/2f, centery + sclheight/4f, centerx + sclwidth/2f, centery + sclheight/4f);
            Lines.line(centerx + sclwidth/4f, centery - sclheight/2f, centerx + sclwidth/4f, centery + sclheight/2f);

            Lines.stroke(3f);
            Draw.color(Pal.accent);
            Lines.line(centerx - sclwidth/2f, centery, centerx + sclwidth/2f, centery);
            Lines.line(centerx, centery - sclheight/2f, centerx, centery + sclheight/2f);

            Draw.reset();
        }

        {
            Gamemode.Impl gamemode = MVars.rules.gamemode();
            if (gamemode != null) gamemode.drawEditorGuides();
        }

        if (MVars.toolOptions.current.selectedBlock == Blocks.cliff) {
            Vars.world.tiles.eachTile(tile -> {
                if (tile.block() == Blocks.cliff) {
                    Draw.color(Color.valueOf("66887755"));
                } else if (MVars.toolOptions.cliffAuto && MVars.toolOptions.fakeCliffsMap != null && MVars.toolOptions.fakeCliffsMap.toggled(tile.x, tile.y)) {
                    Draw.color(Color.valueOf("aaccbb55"));
                } else return;
                Vec2 v$1 = unproject(tile.x, tile.y);
                float sx = v$1.x, sy = v$1.y;
                Vec2 v = unproject(tile.x + 1, tile.y + 1);
                Fill.rect(sx + (v.x - sx) / 2, sy + (v.y - sy) / 2, v.x - sx, v.y - sy);
                Draw.reset();
            });
        }

        if (editorAction != null) {
            editorAction.preview(this, mousex, mousey);
        } else if (!(mousea instanceof MouseAction.Drag || mousea instanceof MouseAction.CtrlZoom)) {
            boolean rescan = mousex != prevx || mousey != prevy || redrawPreview;

            if (previewMap == null || previewMap.bitmap.width != Vars.world.width() || previewMap.bitmap.height != Vars.world.height()) {
                previewMap = new PreviewToolContext(BitMap.of(Vars.world.width(), Vars.world.height()));
                rescan = true;
            }
            if (rescan) {
                previewMap.zero();
                Point2 s = project(mousex, mousey);
                if (dragging) {
                    int sx = s.x, sy = s.y;
                    s = project(startx, starty);
                    MVars.toolOptions.tool.touchedLine(previewMap, s.x, s.y, sx, sy);
                } else MVars.toolOptions.tool.touched(previewMap, s.x, s.y, s.x, s.y);
            }

            if (previewMap.hasRegion) {
                Draw.color(Pal.accent);
                Lines.stroke(Scl.scl(2f));

                for (int x = previewMap.startx; x <= previewMap.endx + 1; x++) {
                    int runStart = -1;
                    for (int y = previewMap.starty; y <= previewMap.endy + 1; y++) {
                        boolean border = previewMap.bitmap.toggled(x, y) != previewMap.bitmap.toggled(x - 1, y);
                        if (border && runStart == -1) {
                            runStart = y;
                        } else if (!border && runStart != -1) {
                            Vec2 s = unproject(x, runStart);
                            float sx = s.x, sy = s.y;
                            s = unproject(x, y);
                            Lines.line(sx, sy, s.x, s.y);
                            runStart = -1;
                        }
                    }
                    if (runStart != -1) {
                        Vec2 s = unproject(x, runStart);
                        float sx = s.x, sy = s.y;
                        s = unproject(x, previewMap.endy + 2);
                        Lines.line(sx, sy, s.x, s.y);
                    }
                }

                for (int y = previewMap.starty; y <= previewMap.endy + 1; y++) {
                    int runStart = -1;
                    for (int x = previewMap.startx; x <= previewMap.endx + 1; x++) {
                        boolean border = previewMap.bitmap.toggled(x, y) != previewMap.bitmap.toggled(x, y - 1);
                        if (border && runStart == -1) {
                            runStart = x;
                        } else if (!border && runStart != -1) {
                            Vec2 s = unproject(runStart, y);
                            float sx = s.x, sy = s.y;
                            s = unproject(x, y);
                            Lines.line(sx, sy, s.x, s.y);
                            runStart = -1;
                        }
                    }
                    if (runStart != -1) {
                        Vec2 s = unproject(runStart, y);
                        float sx = s.x, sy = s.y;
                        s = unproject(previewMap.endx + 2, y);
                        Lines.line(sx, sy, s.x, s.y);
                    }
                }

                Draw.reset();
            }

            prevx = mousex;
            prevy = mousey;
            redrawPreview = false;
        }

        Draw.color(Pal.accent);
        Lines.stroke(Scl.scl(3f));
        Lines.rect(x, y, width, height);
        Draw.reset();

        ScissorStack.pop();
    }
}
