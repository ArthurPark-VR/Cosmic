/*
    Puts the bot cast into the world and takes it out again.

    A bot here is an ordinary Character on an ordinary channel, registered in the same player
    storage as everybody else. That is the entire design: once a bot is in PlayerStorage and in a
    MapleMap, whisper, party, guild, buddy and marriage all find it by name or id without knowing
    it is a bot, because to them it is not. The only thing missing is somebody on the other end of
    the socket, which Client.createBotClient handles by discarding packets.

    Spawning happens once at boot rather than on demand. Characters that appear the moment you walk
    into a town and vanish when you leave read as scenery; characters that were already standing
    there read as people.
*/
package server.bot;

import client.Character;
import client.Client;
import config.YamlConfig;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

public final class BotWorld {
    private static final Logger log = LoggerFactory.getLogger(BotWorld.class);

    /** The account that owns every bot character. Created by the 104-bot-bodies changeset. */
    private static final String BOT_ACCOUNT = "botsystem";

    /** Live bots, by lowercased name. Name is the handle everywhere in this codebase. */
    private static final Map<String, Character> live = new ConcurrentHashMap<>();

    /** Character ids of live bots, so hot paths can ask without a name lookup. */
    private static final Map<Integer, String> liveIds = new ConcurrentHashMap<>();

    private BotWorld() {
    }

    public static boolean isEnabled() {
        return YamlConfig.config.server.USE_BOT_BODIES;
    }

    public static Character get(String name) {
        return name == null ? null : live.get(name.toLowerCase());
    }

    public static boolean isBotCharacter(int characterId) {
        return liveIds.containsKey(characterId);
    }

    public static Collection<Character> all() {
        return live.values();
    }

    /**
     * Bots currently standing in the given map. Used by anything that talks to the room rather
     * than to one character - map chat being the first.
     */
    public static List<Character> inMap(int mapId) {
        List<Character> found = new ArrayList<>();
        for (Character chr : live.values()) {
            if (chr.getMapId() == mapId) {
                found.add(chr);
            }
        }
        return found;
    }

    /**
     * Creates any missing character rows, then loads and places every bot. Safe to call once at
     * boot; calling it twice would double-register, so it refuses if anything is already live.
     */
    public static synchronized void spawnAll() {
        if (!isEnabled()) {
            log.info("Bot bodies: disabled");
            return;
        }
        if (!live.isEmpty()) {
            log.warn("Bot bodies: already spawned, ignoring");
            return;
        }

        int accountId = findBotAccount();
        if (accountId <= 0) {
            log.warn("Bot bodies: no '{}' account, skipping. Has the 104-bot-bodies migration run?",
                    BOT_ACCOUNT);
            return;
        }

        World world = Server.getInstance().getWorld(0);
        if (world == null) {
            log.warn("Bot bodies: world 0 is not up, skipping");
            return;
        }
        Channel channel = world.getChannel(1);
        if (channel == null) {
            log.warn("Bot bodies: channel 1 is not up, skipping");
            return;
        }

        List<BotBody> bodies = loadBodies();
        int spawned = 0;
        for (BotBody body : bodies) {
            try {
                if (spawn(body, accountId, world, channel)) {
                    spawned++;
                }
            } catch (Exception e) {
                // One bad character must not stop the rest of the cast from appearing.
                log.warn("Bot bodies: could not spawn '{}'", body.name(), e);
            }
        }
        log.info("Bot bodies: {} of {} in the world", spawned, bodies.size());
    }

    private static boolean spawn(BotBody body, int accountId, World world, Channel channel)
            throws SQLException {
        Client botClient = Client.createBotClient(world.getId(), channel.getId());
        botClient.setAccID(accountId);
        botClient.setAccountName(BOT_ACCOUNT);

        int characterId = body.characterId();
        if (characterId <= 0) {
            characterId = BotBodyFactory.create(botClient, body);
            if (characterId <= 0) {
                return false;
            }
            rememberCharacterId(body.name(), characterId);
        }

        Character chr = Character.loadCharFromDB(characterId, botClient, true);
        if (chr == null) {
            log.warn("Bot bodies: '{}' has characterid {} but it would not load",
                    body.name(), characterId);
            return false;
        }
        botClient.setPlayer(chr);

        // Applied on every spawn rather than only at creation. The cast already existed by the
        // time it had faces worth having, and a look that can only be set once is a look that can
        // never be corrected.
        BotLook.Look look = BotLook.forBot(body.name(), body.gender(), body.hair(), body.face());
        if (BotLook.apply(chr, look, chr.getJob())) {
            chr.saveCharToDB(false);
        }

        channel.addPlayer(chr);
        world.addPlayer(chr);

        // Out of the party search queue before entering the world. The characters table defaults
        // partySearch to 1, so a bot would otherwise be offered to real players looking for a
        // party - and it would never accept, because the invite arrives as a packet nobody
        // receives. The invite would then occupy that player's one pending slot until it expired.
        if (chr.isRecvPartySearchInviteEnabled()) {
            chr.toggleRecvPartySearchInvite();
        }
        chr.setEnteredChannelWorld();

        MapleMap map = chr.getMap();
        if (map == null) {
            log.warn("Bot bodies: '{}' has no map, leaving it unplaced", body.name());
            return false;
        }

        // Before addPlayer, because addPlayer is what broadcasts the spawn - set the position
        // afterwards and every client in the map has already drawn the bot in the wrong place.
        // loadCharFromDB leaves a character at its portal, which hangs in the air above the
        // floor; a real client falls that last stretch and reports where it landed, which is a
        // step a character with nobody connected to it never takes.
        chr.setPosition(BotMovement.onGround(map, chr.getPosition()));
        chr.setStance(0);

        map.addPlayer(chr);

        live.put(body.name().toLowerCase(), chr);
        liveIds.put(chr.getId(), body.name());
        return true;
    }

    /**
     * Removes bots from the world. Called on shutdown so the player storage and maps do not hold
     * characters whose channel is going away.
     */
    public static synchronized void despawnAll() {
        if (live.isEmpty()) {
            return;
        }
        // Before anything is removed: a follow tick that fires against a half-torn-down map is a
        // stack trace during shutdown, which is a bad place to be reading one.
        BotMovement.stopAll();
        BotCombat.stopAll();

        World world = Server.getInstance().getWorld(0);
        for (Character chr : live.values()) {
            try {
                MapleMap map = chr.getMap();
                if (map != null) {
                    map.removePlayer(chr);
                }
                Channel channel = world != null ? world.getChannel(chr.getClient().getChannel()) : null;
                if (channel != null) {
                    channel.removePlayer(chr);
                }
                if (world != null) {
                    world.removePlayer(chr);
                }
                chr.saveCharToDB(true);
            } catch (Exception e) {
                log.warn("Bot bodies: could not despawn '{}'", chr.getName(), e);
            }
        }
        live.clear();
        liveIds.clear();
        log.info("Bot bodies: despawned");
    }

    private static int findBotAccount() {
        final String sql = "SELECT id FROM accounts WHERE name = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, BOT_ACCOUNT);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : -1;
            }
        } catch (SQLException e) {
            log.warn("Bot bodies: could not read the bot account", e);
            return -1;
        }
    }

    /**
     * Reads the personas marked for a body. Job and town come out of the persona's traits rather
     * than duplicate columns, because they are already written there and two sources for one fact
     * is how they end up disagreeing.
     */
    private static List<BotBody> loadBodies() {
        final String sql = """
                SELECT name, characterid, level, gender, hair, face, traits
                FROM bot_persona WHERE has_body = 1 ORDER BY name""";
        List<BotBody> bodies = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String traits = rs.getString("traits");
                bodies.add(new BotBody(
                        rs.getString("name"),
                        rs.getInt("characterid"),
                        rs.getInt("level"),
                        rs.getInt("gender"),
                        rs.getInt("hair"),
                        rs.getInt("face"),
                        traitValue(traits, "job"),
                        BotBodyFactory.mapForHaunt(traitValue(traits, "hangout"))));
            }
        } catch (SQLException e) {
            log.warn("Bot bodies: could not read personas", e);
        }
        return bodies;
    }

    private static void rememberCharacterId(String botName, int characterId) {
        final String sql = "UPDATE bot_persona SET characterid = ? WHERE name = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setInt(1, characterId);
            ps.setString(2, botName);
            ps.executeUpdate();
        } catch (SQLException e) {
            // Not fatal now, but it means a duplicate character next boot, so it is worth shouting.
            log.error("Bot bodies: created character {} for '{}' but could not record it",
                    characterId, botName, e);
        }
    }

    /**
     * Pulls one string value out of the traits JSON without dragging in a JSON parser. The traits
     * column is written by our own migrations and is flat string-to-string, so a scan is enough -
     * and it fails to an empty string rather than throwing, which is what callers want here.
     */
    static String traitValue(String traitsJson, String key) {
        if (traitsJson == null) {
            return "";
        }
        String needle = "\"" + key + "\"";
        int at = traitsJson.indexOf(needle);
        if (at < 0) {
            return "";
        }
        int colon = traitsJson.indexOf(':', at + needle.length());
        if (colon < 0) {
            return "";
        }
        int open = traitsJson.indexOf('"', colon + 1);
        if (open < 0) {
            return "";
        }
        int close = traitsJson.indexOf('"', open + 1);
        if (close < 0) {
            return "";
        }
        return traitsJson.substring(open + 1, close);
    }
}
