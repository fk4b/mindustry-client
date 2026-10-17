package mindustry.client.morj.mk.ui;

import arc.Core;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.Pixmap;
import arc.graphics.Texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Vec3;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.TextureRegionDrawable;
import arc.scene.ui.ButtonGroup;
import arc.scene.ui.Dialog;
import arc.scene.ui.ImageButton;
import arc.scene.ui.ScrollPane;
import arc.scene.ui.TextButton;
import arc.scene.ui.layout.Scl;
import arc.scene.ui.layout.Table;
import arc.struct.Seq;
import mindustry.client.morj.mk.MVars;
import mindustry.gen.Tex;
import mindustry.graphics.Pal;
import mindustry.graphics.g3d.PlanetParams;
import mindustry.gen.Icon;
import mindustry.type.Planet;
import mindustry.ui.Fonts;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;

import static mindustry.Vars.*;

public class PlanetBackgroundDialog extends BaseDialog {
    private Table main;
    private PlanetParams params;

    private float rotX = 90f;
    private float rotY = 90f;
    private float zoom = 1f;

    private boolean textureMode = false;
    private Table descriptionRow;
    private String savedTexture;
    private Texture previewTexture;

    private static final float DRAG_SPEED  = 0.1f;
    private static final float SCROLL_SPEED = 0.25f;

    public PlanetBackgroundDialog() {
        super("@rules.planetbackground", new DialogStyle() {{
            stageBackground = Styles.none;
            titleFont      = Fonts.def;
            titleFontColor = Pal.accent;
        }});

        titleTable.row();
        descriptionRow = new Table();
        descriptionRow.add("@rules.background.description").color(Color.white).padBottom(20f);
        titleTable.add(descriptionRow).colspan(10);
        titleTable.row();
        titleTable.add().growX();
        titleTable.table(modeTable -> {
            modeTable.defaults().width(140f).height(45f);
            modeTable.button("@rules.background.mode.planet", Styles.flatTogglet, () -> {
                if (!textureMode) return;
                addPlanetBackground();
            }).update(b -> b.setChecked(!textureMode)).row();
            modeTable.button("@rules.background.mode.texture", Styles.flatTogglet, () -> {
                if (textureMode) return;
                addTextureBackground();
            }).update(b -> b.setChecked(textureMode)).row();
        }).padRight(10f);

        addCloseButton();
        buttons.button("", Icon.planet, () -> {
            if (textureMode) textureDialog();
            else planetDialog();
        }).size(210f, 64f).update(b -> {
            b.setText(textureMode
                ? Core.bundle.get("rules.background.selecttexture")
                : Core.bundle.get("rules.background.selectplanet"));
        });
        buttons.button("@rules.background.removebackground", Icon.trash, () -> {
            if (hasBackground()) {
                ui.showConfirm("@rules.background.removalwarning", this::removeBackground);
            }
        }).size(210f, 64f);

        addListener(new InputListener() {
            private float lastX, lastY;
            private boolean dragging = false;

            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (!hasBackground() || button != KeyCode.mouseRight && button != KeyCode.mouseLeft) return false;
                lastX = x;
                lastY = y;
                dragging = true;
                return true;
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                dragging = false;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (!dragging || !hasBackground()) return;
                float dx = x - lastX;
                float dy = y - lastY;
                lastX = x;
                lastY = y;
                if (!textureMode) {
                    rotX = Mathf.mod(rotX + dx * DRAG_SPEED, 360f);
                    rotY = Mathf.clamp(rotY + dy * DRAG_SPEED*zoom/2, 1f, 179f);
                }
                updateParams();
            }
        });

        shown(() -> {
            savedTexture = state.rules.backgroundTexture;
            textureMode = state.rules.planetBackground == null && state.rules.backgroundTexture != null;
            setup();
        });
    }

    private void addPlanetBackground() {
        savedTexture = state.rules.backgroundTexture;
        state.rules.backgroundTexture = null;
        if (state.rules.planetBackground == null) {
            rotX = 90f;
            rotY = 90f;
            zoom = 1f;
            state.rules.planetBackground = new PlanetParams();
        }
        params = state.rules.planetBackground;
        textureMode = false;
        setup();
    }

    private void addTextureBackground() {
        if (state.rules.planetBackground == null) {
            state.rules.planetBackground = new PlanetParams();
        }
        state.rules.backgroundTexture = savedTexture != null && !savedTexture.isEmpty() ? savedTexture : "";
        params = state.rules.planetBackground;
        zoom = params.zoom > 0f ? params.zoom : 1f;
        textureMode = true;
        PlanetBackgroundDrawer.update();
        setup();
    }

    private void removeBackground() {
        state.rules.planetBackground = null;
        state.rules.backgroundTexture = null;
        savedTexture = null;
        params = null;
        textureMode = false;
        PlanetBackgroundDrawer.update();
        setup();
    }

    private void addPlanetButton(Planet planet, Table tb, BaseDialog dialog) {
        tb.button(planet.localizedName, Icon.planet, Styles.togglet, () -> {
            if (params == null) addPlanetBackground();
            params.planet = planet;
            updateParams();
            dialog.hide();
        }).marginLeft(14f).padBottom(5f).width(220f).height(55f)
          .checked(params != null && params.planet == planet)
          .update(b -> b.setChecked(params != null && params.planet == planet))
          .get().getChildren().get(1).setColor(planet.iconColor);
    }

    private void planetDialog() {
        // if (params == null) addPlanetBackground();

        BaseDialog dialog = new BaseDialog("@rules.background.selectplanet");
        dialog.cont.pane(p -> p.table(t -> {
            int i = 0;
            for (Planet planet : content.planets()) {
                addPlanetButton(planet, t, dialog);
                if (++i % 3 == 0) t.row();
            }
        }));
        dialog.addCloseButton();
        dialog.show();
    }

    private static final float CELL_SIZE = 160f;

    private enum TextureTab { SPRITES, CUSTOM }

    private static class TextureEntry {
        final String path;
        final String name;
        TextureRegion region;

        TextureEntry(String path, String name, TextureRegion region) {
            this.path = path;
            this.name = name;
            this.region = region;
        }
    }

    private Seq<TextureEntry> cachedSprites, cachedCustom;

    private Seq<TextureEntry> getSprites() {
        if (cachedSprites == null) {
            cachedSprites = new Seq<>();
            for (String name : Core.assets.getAssetNames()) {
                if (!name.startsWith("sprites/") || !name.endsWith(".png")) continue;
                if (!Texture.class.equals(Core.assets.getAssetType(name))) continue;

                Texture tex = Core.assets.get(name, Texture.class);
                int slash = name.lastIndexOf('/') + 1;
                cachedSprites.add(new TextureEntry(name, name.substring(slash, name.length() - 4), new TextureRegion(tex)));
            }
            cachedSprites.sort((a, b) -> a.name.compareTo(b.name));
        }
        return cachedSprites;
    }

    private Seq<TextureEntry> getCustomTextures() {
        if (cachedCustom == null) {
            cachedCustom = new Seq<>();
            Fi dir = dataDirectory.child("backgrounds");
            if (dir.exists()) {
                for (Fi file : dir.list()) {
                    if (!file.extEquals("png")) continue;
                    TextureEntry entry = loadCustomEntry(file);
                    if (entry != null) cachedCustom.add(entry);
                }
            }
            cachedCustom.sort((a, b) -> a.name.compareTo(b.name));
        }
        return cachedCustom;
    }

    private Pixmap loadPixmap(byte[] bytes) {
        try {
            return new Pixmap(bytes);
        } catch (Exception ignored) {}

        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) return null;

            int w = image.getWidth(), h = image.getHeight();
            Pixmap pixmap = new Pixmap(w, h);
            int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = pixmap.pixels;
            for (int pixel : pixels) {
                buf.put((byte)((pixel >> 16) & 0xFF));
                buf.put((byte)((pixel >> 8) & 0xFF));
                buf.put((byte)(pixel & 0xFF));
                buf.put((byte)((pixel >> 24) & 0xFF));
            }
            buf.flip();
            return pixmap;
        } catch (Exception ignored) {}

        return null;
    }

    private TextureEntry loadCustomEntry(Fi file) {
        try {
            Pixmap pixmap = loadPixmap(file.readBytes());
            if (pixmap == null) return null;
            Texture tex = new Texture(pixmap);
            tex.setFilter(Texture.TextureFilter.linear);
            pixmap.dispose();
            return new TextureEntry(file.absolutePath(), file.nameWithoutExtension(), new TextureRegion(tex));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void saveCustomTexture(Pixmap pixmap, Fi dest, BaseDialog dialog, Runnable rebuild) {
        dest.writePng(pixmap);
        pixmap.dispose();

        TextureEntry existing = cachedCustom != null ? cachedCustom.find(e -> e.path.equals(dest.absolutePath())) : null;
        if (existing != null) {
            if (existing.region != null && existing.region.texture != null) {
                existing.region.texture.dispose();
            }
            cachedCustom.remove(existing);
        }

        TextureEntry entry = loadCustomEntry(dest);
        if (entry == null) return;

        if (cachedCustom == null) cachedCustom = new Seq<>();
        cachedCustom.add(entry);
        cachedCustom.sort((a, b) -> a.name.compareTo(b.name));
        rebuild.run();
    }

    private void importCustomTexture(BaseDialog dialog, Runnable rebuild) {
        platform.showMultiFileChooser(file -> {
            Fi dir = dataDirectory.child("backgrounds");
            dir.mkdirs();

            byte[] bytes = file.readBytes();
            Pixmap pixmap = loadPixmap(bytes);
            if (pixmap == null) {
                ui.showErrorMessage("@rules.background.importerror");
                return;
            }

            String baseName = file.nameWithoutExtension();
            Fi dest = dir.child(baseName + ".png");

            if (cachedCustom != null && cachedCustom.contains(e -> e.name.equals(baseName))) {
                BaseDialog confirm = new BaseDialog("@rules.background.duplicateerror");
                confirm.cont.add("@rules.background.duplicateerror").pad(20f);
                confirm.addCloseButton();
                confirm.buttons.button("@rules.background.overwrite", Icon.save, () -> {
                    saveCustomTexture(pixmap, dest, dialog, rebuild);
                    confirm.hide();
                }).size(210f, 64f);
                confirm.keyDown(KeyCode.escape, confirm::hide);
                confirm.show();
                return;
            }

            saveCustomTexture(pixmap, dest, dialog, rebuild);
        }, "png", "jpg", "jpeg", "bmp", "gif", "tga", "tiff", "tif", "webp");
    }

    private void textureDialog() {
        BaseDialog dialog = new BaseDialog("@rules.background.selecttexture");
        Table grid = new Table();
        String[] filter = {""};
        float[] lastWidth = {0f};
        String currentTex = state.rules.backgroundTexture;
        boolean isCustom = currentTex != null && !currentTex.isEmpty() && !currentTex.startsWith("sprites/");
        TextureTab[] tab = {isCustom ? TextureTab.CUSTOM : TextureTab.SPRITES};
        String[] selected = {currentTex};

        Runnable rebuild = () -> rebuildTextureGrid(grid, filter[0], lastWidth[0], tab[0], selected, dialog);

        dialog.cont.table(t -> {
            t.table(top -> {
                top.table(tabs -> {
                    ButtonGroup<TextButton> group = new ButtonGroup<>();
                    tabs.defaults().width(120f).height(40f);
                    tabs.button("Sprites", Styles.flatTogglet, () -> { tab[0] = TextureTab.SPRITES; rebuild.run(); })
                        .group(group).checked(!isCustom).row();
                    tabs.button("Custom", Styles.flatTogglet, () -> { tab[0] = TextureTab.CUSTOM; rebuild.run(); })
                        .group(group).checked(isCustom).row();
                }).left().top().padRight(10f);

                top.field("", text -> {
                    filter[0] = text;
                    rebuild.run();
                }).grow();
            }).growX().row();

            ScrollPane scroll = new ScrollPane(grid);
            scroll.update(() -> {
                float w = scroll.getWidth();
                if (w > 0f && Math.abs(w - lastWidth[0]) > 10f) {
                    lastWidth[0] = w;
                    rebuild.run();
                }
            });
            t.add(scroll).grow();
        }).grow();

        rebuild.run();
        dialog.addCloseButton();
        dialog.buttons.button("@rules.background.apply", Icon.ok, () -> {
            if (selected[0] != null && !selected[0].isEmpty()) {
                state.rules.backgroundTexture = selected[0];
                savedTexture = selected[0];
                PlanetBackgroundDrawer.update();
                dialog.hide();
                setup();
            }
        }).size(210f, 64f);
        dialog.buttons.button("@rules.background.importcustom", Icon.upload,
            () -> importCustomTexture(dialog, rebuild)).size(210f, 64f);
        dialog.buttons.button("@rules.background.deletetexture", Icon.trash, () -> {
            if (tab[0] != TextureTab.CUSTOM || selected[0] == null) return;
            if (cachedCustom == null) return;

            TextureEntry found = cachedCustom.find(e -> e.path.equals(selected[0]));
            if (found == null) return;

            ui.showConfirm("@rules.background.deleteconfirm", () -> {
                new Fi(found.path).delete();
                cachedCustom.remove(found);

                if (selected[0].equals(found.path)) selected[0] = null;
                if (found.path.equals(state.rules.backgroundTexture)) {
                    state.rules.backgroundTexture = "";
                    setup();
                }
                rebuild.run();
            });
        }).size(210f, 64f).update(b -> b.setDisabled(tab[0] != TextureTab.CUSTOM));
        dialog.keyDown(KeyCode.escape, dialog::hide);
        dialog.show();
    }

    private Seq<TextureEntry> getEntriesForTab(TextureTab tab) {
        switch (tab) {
            case SPRITES: return getSprites();
            case CUSTOM: return getCustomTextures();
            default: return new Seq<>();
        }
    }

    private void rebuildTextureGrid(Table grid, String filter, float rawWidth, TextureTab tab, String[] selected, BaseDialog dialog) {
        grid.clear();

        float availWidth = rawWidth <= 0f ? Core.graphics.getWidth() / Scl.scl() - 100f : rawWidth;
        int cols = Math.max(2, (int)(availWidth / CELL_SIZE));
        int i = 0;

        for (TextureEntry entry : getEntriesForTab(tab)) {
            if (!filter.isEmpty() && !entry.name.contains(filter)) continue;
            if (entry.region == null) continue;

            boolean isSel = entry.path.equals(selected[0]);

            ImageButton.ImageButtonStyle style = new ImageButton.ImageButtonStyle(Styles.clearNoneTogglei);
            style.imageUp = new TextureRegionDrawable(entry.region);
            ImageButton button = new ImageButton(style);
            button.resizeImage(128f);
            button.setChecked(isSel);
            button.clicked(() -> {
                selected[0] = entry.path;
                rebuildTextureGrid(grid, filter, availWidth, tab, selected, dialog);
            });

            grid.table(cell -> {
                cell.add(button).size(140f).row();
                cell.add(entry.name).width(CELL_SIZE - 10f).fontScale(0.7f).wrap().center()
                    .color(isSel ? Pal.accent : Color.lightGray);
            }).pad(4f).top().width(CELL_SIZE);

            if (++i >= cols) {
                i = 0;
                grid.row();
            }
        }
    }

    private TextureRegion getTexturePreview(String path) {
        if (path == null || path.isEmpty()) return null;

        if (previewTexture != null) {
            previewTexture.dispose();
            previewTexture = null;
        }

        if (path.startsWith("sprites/") && Core.assets.isLoaded(path, Texture.class)) {
            return new TextureRegion(Core.assets.get(path, Texture.class));
        }

        Fi file = new Fi(path);
        if (file.exists()) {
            Pixmap pixmap = loadPixmap(file.readBytes());
            if (pixmap != null) {
                previewTexture = new Texture(pixmap);
                previewTexture.setFilter(Texture.TextureFilter.linear);
                pixmap.dispose();
                return new TextureRegion(previewTexture);
            }
        }

        return null;
    }

    private boolean hasBackground() {
        return state.rules.planetBackground != null ||
               (state.rules.backgroundTexture != null && !state.rules.backgroundTexture.isEmpty());
    }

    private void setup() {
        if(!textureMode) params = state.rules.planetBackground;
        state.rules.planetBackground = textureMode ? null : params;

        if (params != null) {
            zoom = params.zoom > 0f ? params.zoom : 1f;
            if (!textureMode && params.camPos != null && !params.camPos.isZero()) {
                Vec3 dir = params.camPos.cpy().nor();
                rotY = (float)Math.acos(dir.y) * Mathf.radDeg;
                rotX = Mathf.mod(Mathf.atan2(dir.z, dir.x) * Mathf.radDeg, 360f);
            }
        }

        descriptionRow.visible = !textureMode;

        cont.clear();
        cont.table(t -> main = t);

        if (!hasBackground() && !textureMode) {
            main.add("@rules.background.nobackground").color(Color.white).padBottom(20f).row();
        }

        if (textureMode) {
            String current = state.rules.backgroundTexture;
            if (current != null && !current.isEmpty()) {
                TextureRegion preview = getTexturePreview(current);
                if (preview != null) {
                    main.add(new arc.scene.ui.Image(preview)).size(256f).pad(10f).row();
                }
                main.add(current).color(Color.lightGray).fontScale(0.8f).padTop(5f).row();
            }
        }

        updateParams();
    }

    private void updateParams() {
        if (!textureMode) {
            PlanetParams p = state.rules.planetBackground;
            if (p != null) p.camPos = new Vec3(
                Mathf.cosDeg(rotX) * Mathf.sinDeg(rotY),
                Mathf.cosDeg(rotY),
                Mathf.sinDeg(rotX) * Mathf.sinDeg(rotY)
            );
        }
        PlanetBackgroundDrawer.update();
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (!hasBackground() || textureMode) return;
        float scroll = Core.input.axis(KeyCode.scroll);
        if (scroll != 0f) {
            zoom = Mathf.clamp(zoom * (1f - scroll * SCROLL_SPEED), 0.1f, 10f);
            updateParams();
        }
    }

    @Override
    public void draw() {
        Texture planetTex = (!textureMode && params != null) ? PlanetBackgroundDrawer.draw() : null;

        if (planetTex != null) {
            float drawSize = Math.max(Core.graphics.getWidth(), Core.graphics.getHeight());
            Draw.rect(
                Draw.wrap(planetTex),
                Core.graphics.getWidth() / 2f,
                Core.graphics.getHeight() / 2f,
                drawSize, drawSize
            );
            Draw.flush();
        } else {
            Draw.color(color.r, color.g, color.b, color.a * parentAlpha);
            Styles.black9.draw(x, y, width, height);
        }

        super.draw();
    }
}
