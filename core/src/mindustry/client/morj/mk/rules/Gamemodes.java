package mindustry.client.morj.mk.rules;

public class Gamemodes {
    private Gamemodes() {}

    public static final Gamemode hub = new Hub();
    public static final Gamemode forts = new Forts();
    public static final Gamemode castle = new Castle();
    public static final Gamemode hexed = new BlankGamemode("hexed-pvp") {{ visible = false; }};
    public static final Gamemode attack = new BlankGamemode("attack") {{ vanillaGamemode = true; }};
    public static final Gamemode pvp = new BlankGamemode("pvp") {{ vanillaGamemode = true; }};
    public static final Gamemode spvp = new BlankGamemode("sandbox-pvp") {{ vanillaGamemode = true; }};
    public static final Gamemode survival = new BlankGamemode("survival") {{ vanillaGamemode = true; }};
    public static final Gamemode miniPvP = new BlankGamemode("mini-pvp") {{ vanillaGamemode = true; }};
}
