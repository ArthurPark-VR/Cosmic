/*
    The handful of bots that are the same people every time you log in.

    Ordinary SoloMapling bots have no persistent identity at all. They are cloned from one template
    at boot, handed a name off a shuffled pool and an id off a counter, and none of it survives a
    restart. For scenery that is exactly right - nobody needs the third shop bot in FM room 4 to be
    the same third shop bot tomorrow.

    It stops being right the moment a player adopts one. A guild whose roster empties every restart
    is not a guild, and a companion whose face changes overnight is not a companion. So a small set
    is promoted: adopting a bot into your guild records who it is - name, look, class, level, guild -
    and at the next boot exactly those bots are spawned back before the rest of the world populates.

    Identity is keyed on NAME, and the name is reserved out of the spawn pool so nothing else can
    take it. That is what makes this work without giving bots rows in `characters`: see the comment
    on migration 202 for why a real character row would be the more expensive answer, not the
    safer one.
*/
package soloMapling.ArtificialPlayer.BotAiSystem;

import client.Character;
import client.Job;
import client.SkinColor;
import client.inventory.InventoryType;
import config.YamlConfig;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotDecorateEquips;
import soloMapling.ArtificialPlayer.BotGeneration;
import soloMapling.ArtificialPlayer.BotGuildSystem.BotGuildCommands;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.FreeMarket.FMShopDescGen;
import tools.DatabaseConnection;

import java.awt.Point;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.BotLogger.log;

public final class CompanionRegistry {

    /** One stored companion. Everything here is what makes it recognisably the same character. */
    public record Companion(String name, int ownerId, int guildId, int guildRank, int allianceRank,
                            int baseClass, int job, int level, int gender, int skinColor,
                            int hair, int face, int homeMap) {
    }

    /** Loaded rows, keyed lowercase by name. */
    private static final Map<String, Companion> byName = new ConcurrentHashMap<>();

    /** Live character ids of restored/adopted companions, so a lookup by id is O(1). */
    private static final Map<Integer, String> liveById = new ConcurrentHashMap<>();

    private static final long SAVE_INTERVAL_MS = java.util.concurrent.TimeUnit.MINUTES.toMillis(5);

    private static final java.util.concurrent.atomic.AtomicBoolean saving =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private CompanionRegistry() {
    }

    // ── Startup ──────────────────────────────────────────────────────────────

    /**
     * Reads the table and reserves every companion name. Must run before ANY bot spawns, or the
     * name pool can hand a companion's name to a piece of scenery and the two become
     * indistinguishable to every by-name lookup in the framework.
     */
    public static void load() {
        byName.clear();
        liveById.clear();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT name, ownerid, guildid, guildrank, alliancerank, baseclass, job, level,"
                             + " gender, skincolor, hair, face, homemap FROM bot_companions");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Companion c = new Companion(
                        rs.getString("name"), rs.getInt("ownerid"), rs.getInt("guildid"),
                        rs.getInt("guildrank"), rs.getInt("alliancerank"), rs.getInt("baseclass"),
                        rs.getInt("job"), rs.getInt("level"), rs.getInt("gender"),
                        rs.getInt("skincolor"), rs.getInt("hair"), rs.getInt("face"),
                        rs.getInt("homemap"));
                byName.put(key(c.name()), c);
                FMShopDescGen.reserveName(c.name());
            }
        } catch (SQLException e) {
            // A missing table or an unreachable database costs the companions, not the server.
            log("[Companions] could not load: " + e.getMessage());
        }
        log("[Companions] loaded " + byName.size());
        startSaver();
    }

    /**
     * Companions level up, change job and change guild while the server runs, and none of that is
     * worth a database write per event. A periodic re-snapshot captures all of it, and the cost of
     * losing the last few minutes on an unclean shutdown is a companion coming back very slightly
     * behind - not a companion coming back as someone else.
     */
    private static void startSaver() {
        if (!saving.compareAndSet(false, true)) {
            return;
        }
        soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                .scheduleWithFixedDelay(() -> {
                    try {
                        saveLive();
                    } catch (RuntimeException e) {
                        log("[Companions] save sweep failed: " + e.getMessage());
                    }
                }, SAVE_INTERVAL_MS, SAVE_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Spawns every stored companion back into the world, then re-seats each in its guild.
     *
     * <p>Runs on the startup wave thread and blocks on each spawn's readiness poll, which is why it
     * belongs in a wave rather than on a client thread.
     */
    public static void restore() {
        if (byName.isEmpty()) {
            return;
        }
        List<Integer> spawned = new ArrayList<>();
        for (Companion c : byName.values()) {
            // Per companion, because one that cannot be rebuilt (a job id that no longer resolves,
            // a home map that no longer exists) must cost only itself.
            try {
                Character bot = spawnCompanion(c);
                if (bot == null) {
                    log("[Companions] failed to restore " + c.name());
                    continue;
                }
                liveById.put(bot.getId(), key(c.name()));
                spawned.add(bot.getId());
            } catch (Exception e) {
                log("[Companions] failed to restore " + c.name() + ": " + e);
            }
        }
        // Type and start them together: the same batching every other cohort uses.
        BotTypeManager.setAndStartBots(spawned, BotTypeManager.BotType.SOCIAL_BOT);

        // Guilds last. A guild is only reachable once the world is up, and re-seating is cheap.
        for (Companion c : byName.values()) {
            if (c.guildId() <= 0) {
                continue;
            }
            try {
                Character bot = findLive(c.name());
                if (bot != null) {
                    BotGuildCommands.seatInGuild(bot, c.guildId(), c.guildRank(), c.allianceRank());
                }
            } catch (Exception e) {
                log("[Companions] could not re-seat " + c.name() + " in guild "
                        + c.guildId() + ": " + e);
            }
        }
        log("[Companions] restored " + spawned.size());
    }

    private static Character spawnCompanion(Companion c) {
        MapleMap map = soloMapling.server.SoloMaplingUtilities.getMapleMapById(c.homeMap());
        if (map == null || map.getPortal(0) == null) {
            return null;
        }
        Point at = map.getPortal(0).getPosition();
        // Exact level (min == max pins it) and exact job, so the companion comes back as itself
        // rather than as a fresh roll of the same class.
        int id = BotGeneration.createBot(at, map, Math.max(1, c.baseClass()), c.level(), c.level(),
                c.job(), c.name());
        if (id < 0) {
            return null;
        }
        Character bot = BotHelpers.getCharFromChannelStorage(id);
        for (int i = 0; bot == null && i < 30; i++) {
            BotHelpers.blockingSleep(100);
            bot = BotHelpers.getCharFromChannelStorage(id);
        }
        if (bot == null) {
            return null;
        }
        applyIdentity(bot, c);
        return bot;
    }

    /**
     * Overwrites the random roll createBot just produced with the stored identity.
     *
     * <p>Equips are re-picked afterwards because gender is part of the identity and half the
     * wardrobe is gender-locked; re-running the equip pass is cheaper and more correct than trying
     * to reconcile a female companion wearing what a male roll chose. The EQUIPPED inventory's
     * checked-latch is cleared for the same reason it is cleared everywhere else: it caches a
     * can-wear verdict, and the verdict is now stale.
     */
    private static void applyIdentity(Character bot, Companion c) {
        // The name is NOT set here - createBot pinned it before registration, because
        // PlayerStorage indexes by name at that moment and a later rename would leave the
        // companion findable only under a name nobody knows.
        bot.setGender(c.gender());
        bot.setSkinColor(SkinColor.getById(c.skinColor()));
        bot.setHair(c.hair());
        bot.setFace(c.face());
        if (c.job() > 0) {
            bot.setJob(Job.getById(c.job()));
        }
        try {
            BotDecorateEquips.decorateBotEquips(bot);
        } catch (RuntimeException e) {
            // Cosmetic. A companion in the wrong shirt is better than a companion that failed to boot.
        }
        bot.getInventory(InventoryType.EQUIPPED).checked(false);
        bot.equipChanged();
    }

    // ── Adoption ─────────────────────────────────────────────────────────────

    /** How many companions this server keeps. Adoption past the cap is refused, not silently dropped. */
    public static int cap() {
        return Math.max(0, YamlConfig.config.server.BOT_COMPANION_LIMIT);
    }

    public static int count() {
        return byName.size();
    }

    public static boolean isFull() {
        return byName.size() >= cap();
    }

    public static boolean isCompanion(String name) {
        return name != null && byName.containsKey(key(name));
    }

    public static boolean isCompanion(int botId) {
        return liveById.containsKey(botId);
    }

    /**
     * Promotes a live bot to a permanent companion. Idempotent: adopting one twice just refreshes
     * its record.
     *
     * @return false if the roster is full and this bot is not already on it.
     */
    public static boolean adopt(Character bot, Character owner) {
        if (bot == null) {
            return false;
        }
        if (!isCompanion(bot.getName()) && isFull()) {
            return false;
        }
        Companion c = snapshot(bot, owner == null ? 0 : owner.getId());
        byName.put(key(c.name()), c);
        liveById.put(bot.getId(), key(c.name()));
        FMShopDescGen.reserveName(c.name());
        write(c);
        return true;
    }

    /** Drops a companion back to being scenery. The name returns to the spawn pool. */
    public static void dismiss(String name) {
        if (name == null) {
            return;
        }
        Companion gone = byName.remove(key(name));
        liveById.values().remove(key(name));
        FMShopDescGen.releaseName(name);
        if (gone == null) {
            return;
        }
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("DELETE FROM bot_companions WHERE name = ?")) {
            ps.setString(1, name);
            ps.executeUpdate();
        } catch (SQLException e) {
            log("[Companions] could not dismiss " + name + ": " + e.getMessage());
        }
    }

    /**
     * Re-snapshots every live companion. Companions level up and change guild while the server
     * runs, and none of that is worth a write per event - a periodic sweep and a shutdown pass
     * capture it just as well.
     */
    public static void saveLive() {
        for (Map.Entry<Integer, String> e : liveById.entrySet()) {
            Character bot = BotHelpers.getCharFromChannelStorage(e.getKey());
            if (bot == null) {
                continue;
            }
            Companion old = byName.get(e.getValue());
            Companion now = snapshot(bot, old == null ? 0 : old.ownerId());
            if (!now.equals(old)) {
                byName.put(e.getValue(), now);
                write(now);
            }
        }
    }

    private static Companion snapshot(Character bot, int ownerId) {
        int job = bot.getJob() == null ? 0 : bot.getJob().getId();
        return new Companion(
                bot.getName(), ownerId, bot.getGuildId(), bot.getGuildRank(), bot.getAllianceRank(),
                Math.max(1, job / 100), job, bot.getLevel(), bot.getGender(),
                bot.getSkinColor() == null ? 0 : bot.getSkinColor().getId(),
                bot.getHair(), bot.getFace(),
                bot.getMapId() >= 0 ? homeTownOf(bot.getMapId()) : 100000000);
    }

    /**
     * Where a companion is put back at boot. Deliberately its town rather than wherever it happened
     * to be standing at shutdown - restoring one into a boss interior or an event instance would be
     * a bug, not fidelity.
     */
    private static int homeTownOf(int mapId) {
        return switch (mapId / 1000000) {
            case 101 -> 101000000; // Ellinia
            case 102 -> 102000000; // Perion
            case 103 -> 103000000; // Kerning City
            case 104 -> 104000000; // Lith Harbour
            case 105 -> 105000000; // Sleepywood
            case 200 -> 200000000; // Orbis
            case 211 -> 211000000; // El Nath
            case 220 -> 220000000; // Ludibrium
            default -> 100000000;  // Henesys
        };
    }

    private static void write(Companion c) {
        String sql = "INSERT INTO bot_companions (name, ownerid, guildid, guildrank, alliancerank,"
                + " baseclass, job, level, gender, skincolor, hair, face, homemap)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON DUPLICATE KEY UPDATE ownerid=VALUES(ownerid), guildid=VALUES(guildid),"
                + " guildrank=VALUES(guildrank), alliancerank=VALUES(alliancerank),"
                + " baseclass=VALUES(baseclass), job=VALUES(job), level=VALUES(level),"
                + " gender=VALUES(gender), skincolor=VALUES(skincolor), hair=VALUES(hair),"
                + " face=VALUES(face), homemap=VALUES(homemap)";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, c.name());
            ps.setInt(2, c.ownerId());
            ps.setInt(3, c.guildId());
            ps.setInt(4, c.guildRank());
            ps.setInt(5, c.allianceRank());
            ps.setInt(6, c.baseClass());
            ps.setInt(7, c.job());
            ps.setInt(8, c.level());
            ps.setInt(9, c.gender());
            ps.setInt(10, c.skinColor());
            ps.setInt(11, c.hair());
            ps.setInt(12, c.face());
            ps.setInt(13, c.homeMap());
            ps.executeUpdate();
        } catch (SQLException e) {
            log("[Companions] could not save " + c.name() + ": " + e.getMessage());
        }
    }

    private static Character findLive(String name) {
        for (Map.Entry<Integer, String> e : liveById.entrySet()) {
            if (e.getValue().equals(key(name))) {
                return BotHelpers.getCharFromChannelStorage(e.getKey());
            }
        }
        return null;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
