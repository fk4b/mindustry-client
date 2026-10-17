package mindustry.client.morj.mk;

import arc.graphics.Pixmap;
import arc.graphics.PixmapIO;
import arc.graphics.Texture;
import arc.graphics.g2d.NinePatch;
import arc.graphics.g2d.PixmapRegion;
import arc.graphics.g2d.TextureAtlas;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.struct.ObjectMap;
import arc.struct.OrderedSet;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Reflect;
import mindustry.client.morj.mk.fs.Entry;
import mindustry.client.morj.mk.fs.File;

import java.io.ByteArrayInputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;

// TODO: Store drawables in the atlas. Not in yet cuz Java being unable to manage resources drives me mad.
public class OTextureAtlas extends TextureAtlas {
    private static class CacheEntry {
        public String name;
        public final WeakReference<Drawable> ref;
        public final Texture autorelease;
        public final Pixmap pixmap;

        public CacheEntry(String name, WeakReference<Drawable> ref, Texture autorelease, Pixmap pixmap) {
            this.name = name;
            this.ref = ref;
            this.autorelease = autorelease;
            this.pixmap = pixmap;
        }

        /**
         * Try to create an atlas region.
         * <p>
         * If drawable is gone, returns {@code null}. This {@link arc.graphics.g2d.TextureAtlas.AtlasRegion} object
         * will hold the inner drawable.
         */
        public @Nullable AtlasRegion region() {
            Drawable lock = ref.get();
            if (lock == null) return null;
            return new AtlasRegion(new TextureRegion(autorelease, autorelease.width, autorelease.height)) {
                final Drawable keepalive = lock;

                {
                    pixmapRegion = new PixmapRegion(pixmap);
                    splits = new int[] { 0, 0, 0, 0 };
                    pads = new int[] { 0, 0, 0, 0 };
                }
            };
        }
    }

    private final Seq<CacheEntry> autoreleaseCache = new Seq<>(CacheEntry.class);

    // I fucking love Java.
    private void gc() {
        autoreleaseCache.retainAll(x -> {
            @Nullable Drawable drawable = x.ref.get();
            if (drawable == null) {
                x.autorelease.dispose();
                getTextures().remove(x.autorelease);
            }
            if (drawable == null && !x.pixmap.isDisposed()) x.pixmap.dispose();
            return drawable != null;
        });
    }

    private CacheEntry autoreleaseAndReturn(String name, Drawable drawable, Texture texture, Pixmap pixmap) {
        getTextures().add(texture);
        CacheEntry entry = new CacheEntry(name, new WeakReference<>(drawable), texture, pixmap);
        autoreleaseCache.add(entry);
        return entry;
    }
    public void autorelease(String name, Drawable drawable, Texture texture, Pixmap pixmap) {
        autoreleaseAndReturn(name, drawable, texture, pixmap);
    }
    public void forgetAutorelease(String name) {
        autoreleaseCache.each(x -> x.name.equals(name), x -> x.name = null);
    }

    private final TextureAtlas ogAtlas;

    public OTextureAtlas(TextureAtlas ogAtlas) {
        this.ogAtlas = ogAtlas;
        error = Reflect.get(TextureAtlas.class, ogAtlas, "error");
    }

    @Override
    public void setDrawableScale(float scale) { gc(); ogAtlas.setDrawableScale(scale); }

    @Override
    public PixmapRegion getPixmap(AtlasRegion region) { gc(); return ogAtlas.getPixmap(region); }
    @Override
    public ObjectMap<Texture, Pixmap> getPixmaps() { gc(); return ogAtlas.getPixmaps(); }
    @Override
    public void disposePixmap(Texture texture) { gc(); ogAtlas.disposePixmap(texture); }

    @Override
    public AtlasRegion addRegion(String name, Texture texture, int x, int y, int width, int height) {
        gc();
        return ogAtlas.addRegion(name, texture, x, y, width, height);
    }

    @Override
    public Seq<AtlasRegion> getRegions() { gc(); return ogAtlas.getRegions(); }

    @Override
    public ObjectMap<String, AtlasRegion> getRegionMap() { gc(); return ogAtlas.getRegionMap(); }

    @Override
    public AtlasRegion white() { return ogAtlas.white(); }

    @Override
    public boolean setErrorRegion(String name) {
        ogAtlas.setErrorRegion(name);
        return super.setErrorRegion(name);
    }

    @Override
    public boolean isFound(TextureRegion region) {
        return super.isFound(region) && ogAtlas.isFound(region);
    }

    @Override
    public AtlasRegion find(String name) {
        gc();

        if (name.startsWith("md-fs/")) {
            @Nullable CacheEntry cache = autoreleaseCache.find(x -> x.name.equals(name));
            if (cache != null) {
                @Nullable AtlasRegion region = cache.region();
                if (region != null) return region;
            }

            return createByName(name);
        }

        return ogAtlas.find(name);
    }
    @Override
    public TextureRegion find(String name, TextureRegion def) { gc(); return ogAtlas.find(name, def); }

    /**
     * Create atlas region.
     * <p>
     * It is assumed that {@code name} starts with {@code "md-fs/"}
     */
    private AtlasRegion createByName(String name) {
        if (MVars.fileSystem == null) return null;
        Entry entry = MVars.fileSystem.get(name.substring(5));
        if (!(entry instanceof File)) return null;
        byte[] contents = ((File) entry).contents();
        if (contents.length == 0) return null;
        try {
            PixmapIO.PngReader reader = new PixmapIO.PngReader();
            ByteBuffer buf = reader.read(new ByteArrayInputStream(contents));
            Pixmap pixmap = new Pixmap(buf, reader.width, reader.height);
            Texture texture = new Texture(pixmap);
            TextureRegionDrawable drawable = new TextureRegionDrawable(new TextureRegion(texture));
            CacheEntry cache = autoreleaseAndReturn(name, drawable, texture, pixmap);
            // This should still exist by this point.
            return cache.region();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean has(String s) { gc(); return ogAtlas.has(s); }

    @Override
    public Drawable drawable(String name) {
        gc();

        for (CacheEntry entry : autoreleaseCache) {
            if (!entry.name.equals(name)) continue;
            Drawable drawable = entry.ref.get();
            if (drawable == null) break;
            autoreleaseCache.remove(entry);
            return drawable;
        }

        if (name.startsWith("md-fs/")) {
            if (MVars.fileSystem == null) return new TextureRegionDrawable(error);
            Entry entry = MVars.fileSystem.get(name.substring(5));
            if (!(entry instanceof File)) return new TextureRegionDrawable(error);
            byte[] contents = ((File) entry).contents();
            if (contents.length == 0) return new TextureRegionDrawable(error);
            try {
                PixmapIO.PngReader reader = new PixmapIO.PngReader();
                ByteBuffer buf = reader.read(new ByteArrayInputStream(contents));
                Pixmap pixmap = new Pixmap(buf, reader.width, reader.height);
                Texture texture = new Texture(pixmap);
                TextureRegionDrawable drawable = new TextureRegionDrawable(new TextureRegion(texture));
                autorelease(name, drawable, texture, pixmap);
                return drawable;
            } catch (Exception e) {
                return new TextureRegionDrawable(error);
            }
        }
        return ogAtlas.drawable(name);
    }

    @Override
    public NinePatch createPatch(String name) { gc(); return ogAtlas.createPatch(name); }

    @Override
    public OrderedSet<Texture> getTextures() { return ogAtlas.getTextures(); }
    // Literally why would you ever need this.
    @Override
    public Texture texture() { gc(); return ogAtlas.texture(); }

    @Override
    public void dispose() {
        autoreleaseCache.each(x -> x.autorelease.dispose());
        ogAtlas.dispose();
    }
}
