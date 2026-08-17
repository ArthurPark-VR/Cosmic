/*
    Turns "someone said something to a bot" into "the bot replies", with persistence.

    Everything here runs off the caller's thread once the DB read is done: chat handlers sit on
    Netty event loop threads, and a model call takes seconds. The DB reads are short and indexed;
    the model call is asynchronous and every failure resolves to empty so the caller can fall back
    to a canned line rather than leaving a bot silent.
*/
package server.bot;

import config.YamlConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class BotDialogue {
    private static final Logger log = LoggerFactory.getLogger(BotDialogue.class);

    /**
     * How many prior lines of a conversation are replayed as context. Small on purpose: 8B-class
     * models lose coherence with long context, and the last few exchanges carry almost all of the
     * conversational continuity a player actually notices.
     */
    private static final int MEMORY_WINDOW = 6;

    private BotDialogue() {
    }

    /**
     * @return true if this name belongs to a bot persona, i.e. worth trying to answer as.
     */
    public static boolean isBot(String name) {
        return loadPersona(name).isPresent();
    }

    public static Optional<BotPersona> loadPersona(String name) {
        final String sql = """
                SELECT p.name, p.temperament, p.speech_style, p.backstory, p.traits,
                       g.goal_type, g.target, g.target_value, g.status
                FROM bot_persona p
                LEFT JOIN bot_goal g ON g.bot_name = p.name
                WHERE p.name = ?""";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BotPersona(
                        rs.getString("name"),
                        nullToEmpty(rs.getString("temperament")),
                        nullToEmpty(rs.getString("speech_style")),
                        rs.getString("backstory"),
                        rs.getString("traits"),
                        describeGoal(rs)));
            }
        } catch (SQLException e) {
            log.warn("Failed loading bot persona '{}'", name, e);
            return Optional.empty();
        }
    }

    private static String describeGoal(ResultSet rs) throws SQLException {
        String type = rs.getString("goal_type");
        if (type == null || "IDLE".equals(type) || !"ACTIVE".equals(rs.getString("status"))) {
            return "";
        }
        String target = nullToEmpty(rs.getString("target"));
        int value = rs.getInt("target_value");
        return value > 0 ? target + " (" + type.toLowerCase() + " " + value + ")" : target;
    }

    /**
     * Produces the bot's reply. Never throws; an empty result means "use a canned line".
     */
    public static CompletableFuture<Optional<String>> reply(String botName, String speakerName,
                                                            String channel, String message) {
        if (!LlmClient.isEnabled()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        // Callers are chat handlers running on Netty event loop threads, so even the short
        // indexed reads below are pushed off-thread rather than held there. Returns immediately.
        return CompletableFuture
                .supplyAsync(() -> {
                    Optional<BotPersona> persona = loadPersona(botName);
                    if (persona.isEmpty()) {
                        return null;
                    }
                    remember(botName, speakerName, channel, "them", message);
                    return new Prepared(persona.get(), buildPrompt(botName, speakerName, message));
                })
                .thenCompose(prepared -> {
                    if (prepared == null) {
                        return CompletableFuture.completedFuture(Optional.<String>empty());
                    }
                    // Bounded by name so one bot cannot be driven to run several generations at
                    // once; the hash is stable across restarts, unlike an object identity.
                    return LlmClient.getInstance()
                            .chat(botName.hashCode(), prepared.persona().toSystemPrompt(), prepared.prompt())
                            .thenApply(reply -> {
                                reply.ifPresent(text -> remember(botName, speakerName, channel, "bot", text));
                                return reply;
                            });
                })
                .exceptionally(error -> {
                    // A conversation must never surface a stack trace to a player.
                    log.warn("Bot dialogue failed for '{}'", botName, error);
                    return Optional.empty();
                });
    }

    private record Prepared(BotPersona persona, String prompt) {
    }

    private static String buildPrompt(String botName, String speakerName, String message) {
        Deque<String> history = recentHistory(botName, speakerName);
        if (history.isEmpty()) {
            return speakerName + " says to you: \"" + message + "\"";
        }
        StringBuilder prompt = new StringBuilder(256);
        prompt.append("Recent conversation with ").append(speakerName).append(":\n");
        for (String line : history) {
            prompt.append(line).append('\n');
        }
        prompt.append('\n').append(speakerName).append(" now says: \"").append(message).append('"');
        return prompt.toString();
    }

    private static Deque<String> recentHistory(String botName, String counterparty) {
        Deque<String> lines = new ArrayDeque<>();
        // Newest first from the index, then reversed, so the prompt reads chronologically.
        final String sql = """
                SELECT speaker, content FROM bot_memory
                WHERE bot_name = ? AND counterparty = ?
                ORDER BY id DESC LIMIT ?""";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, botName);
            ps.setString(2, counterparty);
            ps.setInt(3, MEMORY_WINDOW);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String who = "bot".equals(rs.getString("speaker")) ? botName : counterparty;
                    lines.addFirst(who + ": " + rs.getString("content"));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed loading bot memory for '{}'", botName, e);
        }
        return lines;
    }

    private static void remember(String botName, String counterparty, String channel,
                                 String speaker, String content) {
        final String sql = """
                INSERT INTO bot_memory (bot_name, counterparty, channel, speaker, content)
                VALUES (?, ?, ?, ?, ?)""";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, botName);
            ps.setString(2, counterparty);
            ps.setString(3, channel);
            ps.setString(4, speaker);
            ps.setString(5, truncate(content, 512));
            ps.executeUpdate();
        } catch (SQLException e) {
            // Losing a memory row is not worth failing a conversation over.
            log.warn("Failed persisting bot memory for '{}'", botName, e);
        }
    }

    /**
     * Creates or replaces a persona. Used by the admin command that seeds bots.
     */
    public static boolean savePersona(String name, String temperament, String speechStyle,
                                      String backstory, String traits) {
        final String sql = """
                INSERT INTO bot_persona (name, temperament, speech_style, backstory, traits)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE temperament = VALUES(temperament),
                                        speech_style = VALUES(speech_style),
                                        backstory = VALUES(backstory),
                                        traits = VALUES(traits)""";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, truncate(name, 13));
            ps.setString(2, truncate(temperament, 64));
            ps.setString(3, truncate(speechStyle, 255));
            ps.setString(4, backstory);
            ps.setString(5, traits);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.warn("Failed saving bot persona '{}'", name, e);
            return false;
        }
    }

    public static boolean setGoal(String name, String goalType, String target, int targetValue) {
        final String sql = """
                INSERT INTO bot_goal (bot_name, goal_type, target, target_value, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                ON DUPLICATE KEY UPDATE goal_type = VALUES(goal_type),
                                        target = VALUES(target),
                                        target_value = VALUES(target_value),
                                        status = 'ACTIVE'""";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, truncate(name, 13));
            ps.setString(2, truncate(goalType, 32));
            ps.setString(3, truncate(target, 128));
            ps.setInt(4, targetValue);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.warn("Failed saving bot goal '{}'", name, e);
            return false;
        }
    }

    /**
     * Something to say when the model is unavailable, so a bot is never simply silent.
     */
    public static String cannedFallback(String botName) {
        String[] lines = {
                "sec, afk for a bit",
                "hm?",
                "one moment",
                "brb, buffing",
                "lagging bad right now",
        };
        return lines[Math.floorMod(botName.hashCode() + (int) (System.currentTimeMillis() / 60000), lines.length)];
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    static int memoryWindow() {
        return MEMORY_WINDOW;
    }

    static boolean llmConfigured() {
        return YamlConfig.config.server.USE_BOT_LLM;
    }
}
