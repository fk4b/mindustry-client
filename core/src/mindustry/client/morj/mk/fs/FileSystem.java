package mindustry.client.morj.mk.fs;

import arc.Core;
import arc.Events;
import arc.struct.ObjectMap;
import arc.util.Log;
import mindustry.client.morj.mk.MVars;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.io.SaveFileReader;
import mindustry.io.SaveVersion;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;

public class FileSystem extends Directory {
    public FileSystem() {
        super("", null, null, Date.from(Instant.EPOCH), Date.from(Instant.EPOCH), Date.from(Instant.EPOCH), null);
    }

    public static void init() {
        Events.on(EventType.WorldLoadBeginEvent.class, event -> {
            if (MVars.fileSystem != null) MVars.fileSystem.removed();
            MVars.fileSystem = null;
        });

        SaveVersion.addCustomChunk("mindurka.filesystem", new SaveFileReader.CustomChunk() {
            @Override
            public void write(DataOutput stream) throws IOException {
                if (MVars.fileSystem != null) MVars.fileSystem.write(stream);
                else stream.writeInt(0);
            }

            @Override
            public void read(DataInput stream) throws IOException {
                try {
                    MVars.fileSystem = FileSystem.read(stream);
                    // Log.info(Debug.print(MVars.fileSystem));
                } catch (IOException e) {
                    // It may take a while but oh well.
                    try { while (true) stream.readByte(); } catch (IOException ignored) {}

                    Core.app.post(() -> {
                        Vars.ui.showException("Failed to read virtual filesystem", e);
                    });
                    Log.err(e);
                }
            }
        });
    }

    public static FileSystem read(DataInput stream) throws IOException {
        int entriesCount = stream.readInt();
        ObjectMap<String, Entry> entries = new ObjectMap<>();

        for (int i = 0; i < entriesCount; i++) {
            byte kind = stream.readByte();
            switch (kind) {
                case 0: {
                    File file = File.read(stream);
                    if (entries.put(file.name, file) != null) throw new IOException("Duplicate file name " + file.name);
                } break;
                case 1: {
                    Directory dir = Directory.read(stream);
                    if (entries.put(dir.name, dir) != null) throw new IOException("Duplicate file name " + dir.name);
                } break;
            }
        }

        FileSystem self = new FileSystem();
        self.contents = entries;

        entries.each((k, v) -> v.parent = self);
        return self;
    }

    @Override
    public void write(DataOutput stream) throws IOException {
        // Write no metadata.

        stream.writeInt(contents.size);
        for (ObjectMap.Entry<String, Entry> x : contents) {
            if (x.value instanceof File) stream.writeByte(0);
            else if (x.value instanceof Directory) stream.writeByte(1);
            else throw new RuntimeException("Invalid subclass of 'Entry'");

            x.value.write(stream);
        }
    }

    @Override
    public String fullPath() {
        return "/";
    }
}
