package mindustry.client.tool;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.Texture.*;
import arc.graphics.g2d.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.game.*;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.*;

import static mindustry.Vars.*;

/** HTTP + auth for mindustry-tool.com (schematics and global chat). */
public final class ToolApi{
    public static final String API = "https://api.mindustry-tool.com/api/v4";
    public static final String WEB = "https://mindustry-tool.com";

    static final String KEY_ACCESS = "mindustrytool.auth.access-token";
    static final String KEY_REFRESH = "mindustrytool.auth.refresh-token";
    static final String KEY_MID = "fd-tool-mid";

    private static final ObjectMap<String, Texture> imageCache = new ObjectMap<>();
    private static final ObjectMap<String, Seq<Cons<TextureRegion>>> imageWaiters = new ObjectMap<>();

    private ToolApi(){}

    public static String mid(){
        String mid = Core.settings.getString(KEY_MID, "");
        if(mid == null || mid.isEmpty()){
            mid = "fd:" + UUID.randomUUID();
            Core.settings.put(KEY_MID, mid);
        }
        return mid;
    }

    public static boolean loggedIn(){
        String a = Core.settings.getString(KEY_ACCESS, "");
        String r = Core.settings.getString(KEY_REFRESH, "");
        return a != null && !a.isEmpty() && r != null && !r.isEmpty();
    }

    public static String accessToken(){
        return Core.settings.getString(KEY_ACCESS, "");
    }

    public static void saveTokens(String access, String refresh){
        Core.settings.put(KEY_ACCESS, access);
        Core.settings.put(KEY_REFRESH, refresh);
        Core.settings.forceSave();
    }

    public static void clearTokens(){
        Core.settings.remove(KEY_ACCESS);
        Core.settings.remove(KEY_REFRESH);
        Core.settings.forceSave();
    }

    static void applyHeaders(Http.HttpRequest req, boolean auth){
        req.header("mid", mid());
        req.header("User-Agent", "Mindustry-morj/" + Version.buildString());
        req.header("Accept", "application/json");
        if(auth){
            String token = accessToken();
            if(token != null && !token.isEmpty()){
                req.header("Authorization", "Bearer " + token);
            }
        }
    }

    static void get(String path, boolean auth, Cons<String> ok, Cons<Throwable> err){
        Http.HttpRequest req = Http.get(API + path).timeout(20000);
        applyHeaders(req, auth);
        req.error(e -> Core.app.post(() -> err.get(e)));
        req.submit(res -> {
            try{
                String body = res.getResultAsString();
                Core.app.post(() -> ok.get(body));
            }catch(Throwable t){
                Core.app.post(() -> err.get(t));
            }
        });
    }

    static void post(String path, String json, boolean auth, Cons<String> ok, Cons<Throwable> err){
        Http.HttpRequest req = Http.post(API + path, json).timeout(20000);
        applyHeaders(req, auth);
        req.header("Content-Type", "application/json");
        req.error(e -> Core.app.post(() -> err.get(e)));
        req.submit(res -> {
            try{
                String body = res.getResultAsString();
                Core.app.post(() -> ok.get(body));
            }catch(Throwable t){
                Core.app.post(() -> err.get(t));
            }
        });
    }

    static void getBytes(String path, Cons<byte[]> ok, Cons<Throwable> err){
        Http.HttpRequest req = Http.get(API + path).timeout(60000);
        applyHeaders(req, false);
        req.header("Accept", "*/*");
        req.error(e -> Core.app.post(() -> err.get(e)));
        req.submit(res -> {
            try{
                byte[] bytes = res.getResult();
                Core.app.post(() -> ok.get(bytes));
            }catch(Throwable t){
                Core.app.post(() -> err.get(t));
            }
        });
    }

    public static String enc(String s){
        try{
            return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
        }catch(Exception e){
            return s == null ? "" : s;
        }
    }

    public static String jsonEscape(String s){
        if(s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    public static String jsonObject(String... kv){
        StringBuilder sb = new StringBuilder("{");
        for(int i = 0; i < kv.length; i += 2){
            if(i > 0) sb.append(',');
            sb.append('"').append(jsonEscape(kv[i])).append("\":");
            String v = kv[i + 1];
            if(v == null) sb.append("null");
            else sb.append('"').append(jsonEscape(v)).append('"');
        }
        return sb.append('}').toString();
    }

    public static void searchSchematics(int page, int size, String sort, String name, Seq<String> tags, String verification, Cons<Seq<SchematicItem>> ok, Cons<String> err){
        StringBuilder u = new StringBuilder("/schematics?page=").append(page)
            .append("&size=").append(Math.min(size, 100))
            .append("&sort=").append(enc(sort == null ? "time_desc" : sort));
        if(name != null && !name.isEmpty()) u.append("&name=").append(enc(name));
        if(verification != null && !verification.isEmpty()) u.append("&verification=").append(enc(verification));
        if(tags != null){
            for(String t : tags){
                if(t != null && !t.isEmpty()) u.append("&tags=").append(enc(t));
            }
        }
        get(u.toString(), false, body -> {
            try{
                Seq<SchematicItem> out = new Seq<>();
                Jval arr = Jval.read(body);
                if(arr.isArray()){
                    for(Jval it : arr.asArray()){
                        SchematicItem s = new SchematicItem();
                        s.id = it.getString("id", "");
                        s.itemId = it.getString("itemId", s.id);
                        s.name = it.getString("name", "");
                        s.likes = it.getLong("likes", 0);
                        s.downloads = it.getLong("downloads", 0);
                        s.comments = it.getLong("comments", 0);
                        out.add(s);
                    }
                }
                ok.get(out);
            }catch(Throwable t){
                err.get(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }, e -> err.get(e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    public static void findSchematic(String itemId, Cons<SchematicDetail> ok, Cons<String> err){
        get("/schematics/" + enc(itemId), false, body -> {
            try{
                ok.get(SchematicDetail.parse(Jval.read(body)));
            }catch(Throwable t){
                err.get(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }, e -> err.get(e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    public static void downloadSchematic(String itemId, Cons<Schematic> ok, Cons<String> err){
        getBytes("/schematics/" + enc(itemId) + "/data", bytes -> {
            try{
                ok.get(parseSchematic(bytes));
            }catch(Exception e){
                err.get(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }, e -> err.get(e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    public static Schematic parseSchematic(byte[] bytes) throws Exception{
        if(bytes == null || bytes.length == 0) throw new IOException("empty schematic");
        if(bytes.length >= 4 && bytes[0] == 'm' && bytes[1] == 's' && bytes[2] == 'c' && bytes[3] == 'h'){
            try(InputStream in = new ByteArrayInputStream(bytes)){
                return Schematics.read(in);
            }
        }
        String text = new String(bytes, StandardCharsets.UTF_8).trim();
        if(text.startsWith("bXNja") || text.startsWith("msch")){
            return Schematics.readBase64(text);
        }
        return Schematics.readBase64(new String(arc.util.serialization.Base64Coder.encode(bytes)));
    }

    public static String previewUrl(String itemId){
        return API + "/schematics/" + itemId + "/image.png?variant=preview";
    }

    public static String imageUrl(String itemId){
        return API + "/schematics/" + itemId + "/image.png";
    }

    public static void loadImage(String url, Cons<TextureRegion> ok){
        Texture cached = imageCache.get(url);
        if(cached != null){
            ok.get(new TextureRegion(cached));
            return;
        }
        Seq<Cons<TextureRegion>> wait = imageWaiters.get(url);
        if(wait != null){
            wait.add(ok);
            return;
        }
        wait = new Seq<>();
        wait.add(ok);
        imageWaiters.put(url, wait);

        Http.HttpRequest req = Http.get(url).timeout(30000);
        applyHeaders(req, false);
        req.header("Accept", "image/*");
        req.error(e -> Core.app.post(() -> {
            imageWaiters.remove(url);
        }));
        req.submit(res -> {
            try{
                byte[] bytes = res.getResult();
                Core.app.post(() -> {
                    Seq<Cons<TextureRegion>> listeners = imageWaiters.remove(url);
                    try{
                        Pixmap pix = new Pixmap(bytes);
                        Texture tex = new Texture(pix);
                        tex.setFilter(TextureFilter.linear);
                        pix.dispose();
                        imageCache.put(url, tex);
                        TextureRegion region = new TextureRegion(tex);
                        if(listeners != null) listeners.each(c -> c.get(region));
                    }catch(Throwable t){
                        Log.debug("tool image load failed: @", t.toString());
                    }
                });
            }catch(Throwable t){
                Core.app.post(() -> imageWaiters.remove(url));
            }
        });
    }

    public static void getTags(Cons<Seq<TagCat>> ok){
        get("/tags?group=schematics", false, body -> {
            Seq<TagCat> out = new Seq<>();
            try{
                Jval arr = Jval.read(body);
                if(arr.isArray()){
                    for(Jval cat : arr.asArray()){
                        TagCat c = new TagCat();
                        c.name = cat.getString("name", "");
                        c.id = cat.getString("id", "");
                        Jval tags = cat.get("tags");
                        if(tags != null && tags.isArray()){
                            for(Jval t : tags.asArray()){
                                TagItem item = new TagItem();
                                item.name = t.getString("name", "");
                                item.fullTag = t.getString("fullTag", item.name);
                                item.color = t.getString("color", "ffffff");
                                item.count = t.getInt("count", 0);
                                c.tags.add(item);
                            }
                        }
                        out.add(c);
                    }
                }
            }catch(Throwable ignored){}
            ok.get(out);
        }, e -> ok.get(new Seq<>()));
    }

    public static void loginUri(Cons<String[]> ok, Cons<String> err){
        get("/auth/app/login-uri", false, body -> {
            try{
                Jval v = Jval.read(body);
                ok.get(new String[]{v.getString("loginUrl", ""), v.getString("loginId", "")});
            }catch(Throwable t){
                err.get(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }, e -> err.get(e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    public static void pollLoginToken(String loginId, Cons<Boolean> ok, Cons<String> err){
        Http.HttpRequest req = Http.get(API + "/auth/app/login-token?loginId=" + enc(loginId)).timeout(70000);
        applyHeaders(req, false);
        req.error(e -> Core.app.post(() -> {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if(msg.contains("timed out") || msg.contains("timeout") || e instanceof SocketTimeoutException){
                ok.get(false);
            }else{
                err.get(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        }));
        req.submit(res -> {
            try{
                String body = res.getResultAsString();
                Core.app.post(() -> {
                    try{
                        Jval v = Jval.read(body);
                        String access = v.getString("accessToken", "");
                        String refresh = v.getString("refreshToken", "");
                        if(!access.isEmpty() && !refresh.isEmpty()){
                            saveTokens(access, refresh);
                            ok.get(true);
                        }else{
                            ok.get(false);
                        }
                    }catch(Throwable t){
                        err.get(t.getMessage() == null ? t.toString() : t.getMessage());
                    }
                });
            }catch(Throwable t){
                Core.app.post(() -> err.get(t.getMessage() == null ? t.toString() : t.getMessage()));
            }
        });
    }

    public static void refreshToken(Runnable done){
        String refresh = Core.settings.getString(KEY_REFRESH, "");
        if(refresh == null || refresh.isEmpty()){
            done.run();
            return;
        }
        post("/auth/app/refresh", jsonObject("refreshToken", refresh), false, body -> {
            try{
                Jval v = Jval.read(body);
                String access = v.getString("accessToken", "");
                String next = v.getString("refreshToken", "");
                if(!access.isEmpty() && !next.isEmpty()) saveTokens(access, next);
            }catch(Throwable ignored){}
            done.run();
        }, e -> done.run());
    }

    public static void fetchSession(Cons<User> ok){
        if(!loggedIn()){
            ok.get(null);
            return;
        }
        get("/auth/session", true, body -> {
            if(body == null || body.isEmpty() || "null".equals(body)){
                ok.get(null);
                return;
            }
            try{
                Jval v = Jval.read(body);
                User u = new User();
                u.id = v.getString("id", "");
                u.name = v.getString("name", "");
                u.imageUrl = v.getString("imageUrl", "");
                ok.get(u);
            }catch(Throwable t){
                ok.get(null);
            }
        }, e -> ok.get(null));
    }

    public static void logout(){
        String access = accessToken();
        String refresh = Core.settings.getString(KEY_REFRESH, "");
        if(access != null && !access.isEmpty()){
            post("/auth/app/logout", jsonObject("accessToken", access, "refreshToken", refresh), true, b -> {}, e -> {});
        }
        clearTokens();
    }

    public static void getChannels(Cons<Seq<Channel>> ok, Cons<String> err){
        get("/chats/channels", true, body -> {
            Seq<Channel> out = new Seq<>();
            try{
                Jval arr = Jval.read(body);
                if(arr.isArray()){
                    for(Jval it : arr.asArray()){
                        Channel c = new Channel();
                        c.id = it.getString("id", "");
                        c.name = it.getString("name", "");
                        c.lastMessageId = it.getString("lastMessageId", "");
                        out.add(c);
                    }
                }
            }catch(Throwable t){
                err.get(t.getMessage() == null ? t.toString() : t.getMessage());
                return;
            }
            ok.get(out);
        }, e -> err.get(e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    public static void getMessages(String channelId, String cursor, Cons<Seq<Message>> ok, Cons<String> err){
        String u = "/chats?channelId=" + enc(channelId);
        if(cursor != null && !cursor.isEmpty()) u += "&cursor=" + enc(cursor);
        get(u, true, body -> {
            Seq<Message> out = new Seq<>();
            try{
                Jval arr = Jval.read(body);
                if(arr.isArray()){
                    for(Jval it : arr.asArray()){
                        out.add(Message.parse(it));
                    }
                }
            }catch(Throwable t){
                err.get(t.getMessage() == null ? t.toString() : t.getMessage());
                return;
            }
            ok.get(out);
        }, e -> err.get(e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    public static void sendMessage(String channelId, String content, Cons<Message> ok, Cons<String> err){
        String json = jsonObject("content", content, "channelId", channelId);
        post("/chats/text", json, true, body -> {
            try{
                ok.get(Message.parse(Jval.read(body)));
            }catch(Throwable t){
                err.get(t.getMessage() == null ? t.toString() : t.getMessage());
            }
        }, e -> err.get(e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    public static void getUserBatch(Seq<String> ids, Cons<ObjectMap<String, User>> ok){
        if(ids == null || ids.isEmpty()){
            ok.get(new ObjectMap<>());
            return;
        }
        StringBuilder sb = new StringBuilder("{\"ids\":[");
        for(int i = 0; i < ids.size; i++){
            if(i > 0) sb.append(',');
            sb.append('"').append(jsonEscape(ids.get(i))).append('"');
        }
        sb.append("]}");
        post("/users/batches", sb.toString(), false, body -> {
            ObjectMap<String, User> map = new ObjectMap<>();
            try{
                Jval arr = Jval.read(body);
                if(arr.isArray()){
                    for(Jval it : arr.asArray()){
                        User u = new User();
                        u.id = it.getString("id", "");
                        u.name = it.getString("name", u.id);
                        u.imageUrl = it.getString("imageUrl", "");
                        if(!u.id.isEmpty()) map.put(u.id, u);
                    }
                }
            }catch(Throwable ignored){}
            ok.get(map);
        }, e -> ok.get(new ObjectMap<>()));
    }

    /** Long-lived SSE. Callbacks may run off the GL thread. */
    public static Thread startChatStream(String chatId, Cons<String> line, Runnable onEnd){
        Thread t = new Thread(() -> {
            HttpURLConnection conn = null;
            try{
                URL url = new URL(API + "/chats/stream");
                conn = (HttpURLConnection)url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("mid", mid());
                conn.setRequestProperty("Accept", "text/event-stream");
                conn.setRequestProperty("x-chat-id", chatId);
                conn.setRequestProperty("User-Agent", "Mindustry-morj/" + Version.buildString());
                String token = accessToken();
                if(token != null && !token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(0);
                conn.setDoInput(true);
                try(BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))){
                    String l;
                    while((l = reader.readLine()) != null){
                        line.get(l);
                    }
                }
            }catch(Throwable ignored){
            }finally{
                if(conn != null) conn.disconnect();
                onEnd.run();
            }
        }, "ToolChatSSE");
        t.setDaemon(true);
        t.start();
        return t;
    }

    public static class SchematicItem{
        public String id = "";
        public String itemId = "";
        public String name = "";
        public long likes, downloads, comments;
    }

    public static class SchematicDetail{
        public String itemId = "";
        public String name = "";
        public String description = "";
        public String createdBy = "";
        public int width, height;
        public long likes, downloads, comments;
        public Seq<String> tags = new Seq<>();

        static SchematicDetail parse(Jval v){
            SchematicDetail d = new SchematicDetail();
            d.itemId = v.getString("itemId", v.getString("id", ""));
            d.name = v.getString("name", "");
            d.description = v.getString("description", "");
            d.createdBy = v.getString("createdBy", "");
            d.width = v.getInt("width", 0);
            d.height = v.getInt("height", 0);
            d.likes = v.getLong("likes", 0);
            d.downloads = v.getLong("downloads", 0);
            d.comments = v.getLong("comments", 0);
            Jval tags = v.get("tags");
            if(tags != null && tags.isArray()){
                for(Jval t : tags.asArray()){
                    String n = t.getString("name", "");
                    if(!n.isEmpty()) d.tags.add(n);
                }
            }
            return d;
        }
    }

    public static class TagCat{
        public String id = "", name = "";
        public Seq<TagItem> tags = new Seq<>();
    }

    public static class TagItem{
        public String name = "", fullTag = "", color = "ffffff";
        public int count;
    }

    public static class User{
        public String id = "", name = "", imageUrl = "";
    }

    public static class Channel{
        public String id = "", name = "", lastMessageId = "";
    }

    public static class Message{
        public String id = "", createdBy = "", createdAt = "", content = "", channelId = "", replyTo = "";

        public static Message parse(Jval v){
            Message m = new Message();
            m.id = v.getString("id", "");
            m.createdBy = v.getString("createdBy", "");
            m.createdAt = v.getString("createdAt", "");
            m.content = v.getString("content", "");
            m.channelId = v.getString("channelId", "");
            m.replyTo = v.getString("replyTo", "");
            return m;
        }
    }
}
