package mindustry.client.morj.mk.fs;

import arc.Core;
import arc.graphics.Pixmap;
import arc.graphics.PixmapIO;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.scene.style.Drawable;
import arc.scene.style.TextureRegionDrawable;
import arc.util.Log;
import arc.util.Nullable;
import mindustry.client.morj.mk.MVars;
import mindustry.Vars;
import mindustry.gen.Icon;
import net.jpountz.lz4.LZ4Factory;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Date;

public class File extends Entry {
    private byte[] contents;

    private Drawable computedIcon = Icon.fileSmall;
    private @Nullable Texture attachedTexture = null;
    private @Nullable Pixmap attachedPixmap = null;

    public File(String name, String comment, String icon, Date creationDate, Date accessDate, Date modifiedDate, Directory parent, byte[] contents) {
        super(name, comment, icon, creationDate, accessDate, modifiedDate, parent);
        this.contents = contents;

        recalcIcon();
    }

    @Override
    public void removed() {
        if (attachedTexture != null) {
            attachedTexture.dispose();
            attachedTexture = null;
        }
        if (attachedPixmap != null) {
            attachedPixmap.dispose();
            attachedPixmap = null;
        }
        MVars.atlas.forgetAutorelease(fullPath());
    }

    void recalcIcon() {
        if (attachedTexture != null) {
            attachedTexture.dispose();
            attachedTexture = null;
        }
        if (attachedPixmap != null) {
            attachedPixmap.dispose();
            attachedPixmap = null;
        }

        if (contents.length == 0) {
            computedIcon = Icon.file;
            return;
        }

        if (icon != null) {
            computedIcon = Core.atlas.drawable(icon);
            return;
        }

        try {
            PixmapIO.PngReader reader = new PixmapIO.PngReader();

            ByteBuffer result = reader.read(new ByteArrayInputStream(contents));
            Pixmap pixmap = new Pixmap(result, reader.width, reader.height);
            Texture texture = new Texture(pixmap);
            TextureRegion region = new TextureRegion(texture, reader.width, reader.height);
            computedIcon = new TextureRegionDrawable(region);
            attachedTexture = texture;
            attachedPixmap = pixmap;
            return;
        } catch (Exception e) {
            Log.err(e);
        }

        try {
            new String(contents, Vars.charset);
            computedIcon = Icon.fileText;
            return;
        } catch (Exception ignored) {}

        computedIcon = Icon.file;
    }

    public byte[] contents() {
        accessDate = Date.from(Instant.now());
        return contents;
    }
    public File contents(byte[] value) {
        modifiedDate = accessDate = Date.from(Instant.now());
        recalcIcon();
        MVars.atlas.forgetAutorelease(fullPath());
        return this;
    }

    @Override
    public Drawable icon() {
        return computedIcon;
    }

    public static File read(DataInput stream) throws IOException {
        String name = stream.readUTF();
        String comment = stream.readBoolean() ? stream.readUTF() : null;
        String icon = stream.readBoolean() ? stream.readUTF() : null;
        Date creationDate = new Date(stream.readLong());
        Date accessDate = new Date(stream.readLong());
        Date modifiedDate = new Date(stream.readLong());
        int len = stream.readInt();
        int compressedLen = stream.readInt();
        byte[] contents = new byte[compressedLen];
        byte[] compressed = new byte[len];
        stream.readFully(compressed);
        LZ4Factory.fastestInstance().safeDecompressor().decompress(compressed, contents);
        return new File(name, comment, icon, creationDate, accessDate, modifiedDate, null, contents);
    }

    @Override
    public void write(DataOutput stream) throws IOException {
        super.write(stream);
        byte[] compressed = LZ4Factory.fastestInstance().fastCompressor().compress(contents);
        stream.writeInt(compressed.length);
        stream.writeInt(contents.length);
        stream.write(compressed);
    }
}
