package mindustry.client.tool;

import arc.*;
import arc.graphics.*;
import arc.input.*;
import arc.math.*;
import arc.scene.*;
import arc.scene.event.*;
import arc.scene.ui.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.client.tool.ToolApi.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.ui.*;
import mindustry.ui.dialogs.*;

import java.util.UUID;
import java.util.regex.*;

import static mindustry.Vars.*;

/** Global mindustry-tool.com chat overlay. */
public class ToolChatOverlay extends Table{
    private static final Pattern SCHEM_LINK = Pattern.compile("https?://[^\\s]*/schematics/([a-zA-Z0-9_-]+)");
    private static final int PAGE = 50;

    private boolean collapsed = true;
    private boolean running;
    private boolean connected;
    private boolean loginBusy;
    private volatile boolean streamRunning;
    private Thread streamThread;
    private String chatId = UUID.randomUUID().toString();

    private User session;
    private final Seq<Channel> channels = new Seq<>();
    private String activeId = "";
    private final ObjectMap<String, Seq<Message>> messages = new ObjectMap<>();
    private final ObjectMap<String, User> users = new ObjectMap<>();
    private final ObjectIntMap<String> unread = new ObjectIntMap<>();
    private int totalUnread;

    private Table window;
    private Table messagePane;
    private ScrollPane scroll;
    private TextField input;
    private Label headerLabel;
    private float dragX, dragY;
    private boolean loginCancel;

    public void build(){
        setFillParent(true);
        touchable = Touchable.childrenOnly;
        visible(() -> Core.settings.getBool("toolglobalchat", true));
        Core.scene.add(this);
        toFront();

        float x = Core.settings.getFloat("toolchat-x", -1f);
        float y = Core.settings.getFloat("toolchat-y", -1f);
        window = new Table();
        addChild(window);
        if(x >= 0 && y >= 0){
            window.setPosition(x, y);
        }else{
            window.setPosition(Core.graphics.getWidth() - Scl.scl(70f), Scl.scl(80f));
        }

        rebuildWindow();

        Events.run(mindustry.game.EventType.Trigger.update, () -> {
            if(!Core.settings.getBool("toolglobalchat", true)) return;
            if(Core.scene.hasDialog() || Core.scene.hasField()) return;
            if(Core.input.keyTap(mindustry.input.Binding.toolGlobalChat)){
                collapsed = !collapsed;
                if(!collapsed && activeId != null) unread.put(activeId, 0);
                rebuildUnread();
                rebuildWindow();
            }
        });
    }

    public void setEnabled(boolean enabled){
        Core.settings.put("toolglobalchat", enabled);
        if(enabled){
            visible = true;
            start();
        }else{
            stop();
        }
        rebuildWindow();
    }

    public void start(){
        running = true;
        if(ToolApi.loggedIn()){
            ToolApi.refreshToken(() -> ToolApi.fetchSession(u -> {
                session = u;
                refreshChannels();
                connectStream();
                rebuildWindow();
            }));
        }else{
            rebuildWindow();
        }
    }

    public void stop(){
        running = false;
        streamRunning = false;
        connected = false;
        if(streamThread != null){
            streamThread.interrupt();
            streamThread = null;
        }
    }

    void rebuildWindow(){
        if(window == null) return;
        window.clear();
        window.touchable = Touchable.enabled;
        if(collapsed){
            buildCollapsed();
        }else{
            buildExpanded();
        }
        window.pack();
        keepOnScreen();
        toFront();
        window.toFront();
    }

    void buildCollapsed(){
        ImageButton btn = window.button(Icon.players, Styles.cleari, () -> {
            collapsed = false;
            if(activeId != null){
                unread.put(activeId, 0);
                rebuildUnread();
            }
            if(running && ToolApi.loggedIn() && channels.isEmpty()) refreshChannels();
            rebuildWindow();
        }).size(52f).get();
        btn.getStyle().up = Tex.pane;
        if(totalUnread > 0){
            window.add(new Table(t -> {
                t.setFillParent(true);
                t.top().right();
                t.add(String.valueOf(Math.min(totalUnread, 99)))
                    .color(Color.white).fontScale(0.75f)
                    .pad(4f);
            }));
        }
        addDrag(btn);
    }

    void buildExpanded(){
        window.table(Tex.pane, root -> {
            root.top();
            root.defaults().growX();

            root.table(head -> {
                ImageButton drag = head.button(Icon.move, Styles.cleari, () -> {}).size(36f).get();
                addDrag(drag);
                headerLabel = head.add(headerText()).growX().left().padLeft(6f).get();
                head.add(connected ? "[lime]●" : "[gray]●").padRight(6f);
                if(ToolApi.loggedIn()){
                    head.button(Icon.exit, Styles.cleari, () -> {
                        ToolApi.logout();
                        session = null;
                        channels.clear();
                        messages.clear();
                        stop();
                        running = true;
                        rebuildWindow();
                    }).size(36f).tooltip("@client.tool.chat.logout");
                }
                head.button(Icon.downOpen, Styles.cleari, () -> {
                    collapsed = true;
                    rebuildWindow();
                }).size(36f);
            }).height(42f).pad(2f);
            root.row();

            if(!ToolApi.loggedIn()){
                root.table(login -> {
                    login.add("@client.tool.chat.needlogin").wrap().width(360f).pad(8f);
                    login.row();
                    login.button("@client.tool.chat.login", Icon.link, this::beginLogin).disabled(b -> loginBusy).width(220f).pad(8f);
                }).pad(12f);
                return;
            }

            root.table(body -> {
                body.top();
                body.table(ch -> {
                    ch.top();
                    for(Channel c : channels){
                        int n = unread.get(c.id, 0);
                        String name = (c.id.equals(activeId) ? "[accent]" : "") + "#" + (c.name == null ? "?" : c.name);
                        if(n > 0) name += " [scarlet](" + n + ")";
                        ch.button(name, Styles.flatTogglet, () -> selectChannel(c.id))
                            .checked(c.id.equals(activeId)).growX().height(36f).pad(1f);
                        ch.row();
                    }
                    if(channels.isEmpty()){
                        ch.add("@client.tool.chat.nochannels").color(Color.gray).pad(8f);
                    }
                }).width(140f).growY().top();

                body.table(msg -> {
                    messagePane = new Table();
                    messagePane.top().left();
                    scroll = msg.pane(messagePane).grow().get();
                    scroll.setFadeScrollBars(false);
                    msg.row();
                    msg.table(in -> {
                        input = in.field("", t -> {}).growX().get();
                        input.setMessageText("@client.tool.chat.placeholder");
                        input.setMaxLength(500);
                        input.addListener(new InputListener(){
                            @Override
                            public boolean keyDown(InputEvent event, KeyCode keycode){
                                if(keycode == KeyCode.enter){
                                    send();
                                    return true;
                                }
                                return false;
                            }
                        });
                        in.button(Icon.ok, Styles.cleari, this::send).size(40f);
                    }).growX().padTop(4f);
                }).grow();
            }).size(560f, 340f).pad(4f);
        });
        fillMessages();
    }

    String headerText(){
        if(session != null && session.name != null && !session.name.isEmpty()){
            return Core.bundle.format("client.tool.chat.hello", session.name);
        }
        return Core.bundle.get("client.tool.chat.title");
    }

    void addDrag(Element el){
        el.addListener(new InputListener(){
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button){
                dragX = x;
                dragY = y;
                return true;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer){
                window.moveBy(x - dragX, y - dragY);
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button){
                Core.settings.put("toolchat-x", window.x);
                Core.settings.put("toolchat-y", window.y);
            }
        });
    }

    void keepOnScreen(){
        float maxX = Core.graphics.getWidth() - 20f;
        float maxY = Core.graphics.getHeight() - 20f;
        window.x = Mathf.clamp(window.x, 8f, Math.max(8f, maxX - window.getWidth()));
        window.y = Mathf.clamp(window.y, 8f, Math.max(8f, maxY - window.getHeight()));
    }

    void beginLogin(){
        if(loginBusy) return;
        loginBusy = true;
        loginCancel = false;
        ToolApi.loginUri(pair -> {
            String url = pair[0], loginId = pair[1];
            if(url == null || url.isEmpty()){
                loginBusy = false;
                ui.showErrorMessage("@client.tool.chat.loginfail");
                return;
            }
            if(!Core.app.openURI(url)){
                Core.app.setClipboardText(url);
                ui.showInfoFade("@client.tool.chat.logincopied");
            }
            BaseDialog wait = new BaseDialog("@client.tool.chat.login");
            wait.cont.add("@client.tool.chat.loginwait").width(420f).wrap().pad(10f);
            wait.buttons.button("@cancel", () -> {
                loginCancel = true;
                loginBusy = false;
                wait.hide();
            });
            wait.buttons.button("@client.tool.chat.openurl", Icon.link, () -> Core.app.openURI(url));
            wait.show();
            pollLogin(loginId, wait, 0);
        }, err -> {
            loginBusy = false;
            ui.showErrorMessage(Core.bundle.format("client.tool.chat.error", err));
        });
    }

    void pollLogin(String loginId, Dialog wait, int attempt){
        if(loginCancel) return;
        if(attempt > 12){
            loginBusy = false;
            wait.hide();
            ui.showErrorMessage("@client.tool.chat.logintimeout");
            return;
        }
        ToolApi.pollLoginToken(loginId, done -> {
            if(loginCancel) return;
            if(Boolean.TRUE.equals(done)){
                loginBusy = false;
                wait.hide();
                ui.showInfoFade("@client.tool.chat.logindone");
                start();
            }else{
                pollLogin(loginId, wait, attempt + 1);
            }
        }, err -> {
            if(loginCancel) return;
            loginBusy = false;
            wait.hide();
            ui.showErrorMessage(Core.bundle.format("client.tool.chat.error", err));
        });
    }

    void refreshChannels(){
        ToolApi.getChannels(list -> {
            channels.set(list);
            if((activeId == null || activeId.isEmpty()) && list.any()){
                activeId = list.first().id;
            }
            if(activeId != null && !activeId.isEmpty()) loadMessages(activeId);
            rebuildWindow();
        }, err -> Log.debug("tool chat channels: @", err));
    }

    void selectChannel(String id){
        activeId = id;
        unread.put(id, 0);
        rebuildUnread();
        loadMessages(id);
        rebuildWindow();
    }

    void loadMessages(String channelId){
        ToolApi.getMessages(channelId, null, list -> {
            list.reverse();
            messages.put(channelId, list);
            fetchUsers(list);
            fillMessages();
        }, err -> Log.debug("tool chat messages: @", err));
    }

    void fetchUsers(Seq<Message> list){
        Seq<String> missing = new Seq<>();
        for(Message m : list){
            if(m.createdBy != null && !m.createdBy.isEmpty() && !users.containsKey(m.createdBy) && !missing.contains(m.createdBy)){
                missing.add(m.createdBy);
            }
        }
        if(missing.any()){
            ToolApi.getUserBatch(missing, map -> {
                users.putAll(map);
                fillMessages();
            });
        }
    }

    void fillMessages(){
        if(messagePane == null || collapsed) return;
        messagePane.clear();
        Seq<Message> list = messages.get(activeId, new Seq<>());
        for(Message m : list){
            addMessageRow(m);
        }
        Core.app.post(() -> {
            if(scroll != null) scroll.setScrollY(Float.MAX_VALUE);
        });
    }

    void addMessageRow(Message m){
        User u = users.get(m.createdBy);
        String name = u != null && u.name != null && !u.name.isEmpty() ? u.name : (m.createdBy == null ? "?" : m.createdBy.substring(0, Math.min(8, m.createdBy.length())));
        String content = m.content == null ? "" : m.content;
        messagePane.table(row -> {
            row.left().top();
            row.add("[accent]" + name + "[]").left().growX();
            row.row();
            Matcher match = SCHEM_LINK.matcher(content);
            if(match.find()){
                String itemId = match.group(1);
                row.add(content).wrap().width(360f).left();
                row.row();
                row.button("@client.tool.chat.openschem", Styles.cleart, () -> {
                    if(ui.toolSchematics != null){
                        SchematicItem item = new SchematicItem();
                        item.itemId = itemId;
                        item.name = itemId;
                        ui.toolSchematics.showDetails(item);
                    }
                }).left().height(32f);
            }else{
                row.add(content).wrap().width(380f).left();
            }
        }).growX().pad(3f).left();
        messagePane.row();
    }

    void send(){
        if(input == null) return;
        String text = input.getText();
        if(text == null || text.trim().isEmpty() || activeId == null || activeId.isEmpty()) return;
        String toSend = text.trim();
        input.clearText();
        ToolApi.sendMessage(activeId, toSend, msg -> {
            Seq<Message> list = messages.get(activeId, () -> {
                Seq<Message> s = new Seq<>();
                messages.put(activeId, s);
                return s;
            });
            if(msg != null && msg.id != null){
                boolean exists = list.contains(m -> msg.id.equals(m.id));
                if(!exists) list.add(msg);
            }
            fillMessages();
        }, err -> ui.showInfoFade(Core.bundle.format("client.tool.chat.error", err)));
    }

    void connectStream(){
        if(!running || streamRunning || !ToolApi.loggedIn()) return;
        streamRunning = true;
        connected = true;
        if(headerLabel != null) headerLabel.setText(headerText());
        streamThread = ToolApi.startChatStream(chatId, this::handleStreamLine, () -> {
            streamRunning = false;
            connected = false;
            Core.app.post(() -> {
                if(running){
                    arc.util.Timer.schedule(this::connectStream, 5f);
                }
            });
        });
    }

    private StringBuilder dataBuf = new StringBuilder();
    private String currentEvent = "data";

    void handleStreamLine(String line){
        if(line == null) return;
        if(line.isEmpty()){
            dispatchEvent();
            return;
        }
        if(line.startsWith(":")) return;
        if(line.startsWith("event:")){
            currentEvent = line.substring(6).trim();
            return;
        }
        if(line.startsWith("data:")){
            if(dataBuf.length() > 0) dataBuf.append('\n');
            dataBuf.append(line.substring(5).trim());
        }
    }

    void dispatchEvent(){
        String data = dataBuf.toString().trim();
        String event = currentEvent;
        dataBuf.setLength(0);
        currentEvent = "data";
        if(data.isEmpty()) return;
        if("heartbeat".equalsIgnoreCase(event) || "Connected".equals(data) || "\"Connected\"".equals(data)){
            Core.app.post(() -> connected = true);
            return;
        }
        Core.app.post(() -> {
            try{
                if(data.startsWith("[")){
                    arc.util.serialization.Jval arr = arc.util.serialization.Jval.read(data);
                    if(arr.isArray()){
                        Seq<Message> batch = new Seq<>();
                        for(arc.util.serialization.Jval it : arr.asArray()){
                            batch.add(Message.parse(it));
                        }
                        ingest(batch);
                    }
                }else if(data.startsWith("{")){
                    ingest(Seq.with(Message.parse(arc.util.serialization.Jval.read(data))));
                }
            }catch(Throwable t){
                Log.debug("tool chat sse: @", t.toString());
            }
        });
    }

    void ingest(Seq<Message> incoming){
        for(Message msg : incoming){
            if(msg == null || msg.id == null || msg.id.isEmpty()) continue;
            String ch = msg.channelId == null ? activeId : msg.channelId;
            Seq<Message> list = messages.get(ch, () -> {
                Seq<Message> s = new Seq<>();
                messages.put(ch, s);
                return s;
            });
            if(list.contains(m -> msg.id.equals(m.id))) continue;
            list.add(msg);
            boolean open = !collapsed && ch.equals(activeId);
            if(open){
                unread.put(ch, 0);
            }else{
                unread.put(ch, unread.get(ch, 0) + 1);
            }
        }
        fetchUsers(incoming);
        rebuildUnread();
        if(!collapsed) fillMessages();
        else rebuildWindow();
    }

    void rebuildUnread(){
        int sum = 0;
        for(ObjectIntMap.Entry<String> e : unread.entries()) sum += e.value;
        totalUnread = sum;
    }
}
