/*
    Decides which bot answers something a player said, and says it back on the right channel.

    The hard part here is not generating a reply, it is restraint. A room where every bot answers
    every line is not a populated town, it is a wall of noise, and the illusion dies faster than
    if they had said nothing at all. So: at most one bot answers any given line, being addressed by
    name always wins over proximity, and a bot that has just spoken stays quiet for a moment
    afterwards.

    Replies are broadcast as ordinary chat from the bot's own character, which is why bodies had to
    come first - getChatText is keyed by character id, and a name with no character behind it
    cannot appear in a chat bubble.
*/
package server.bot;

import client.Character;
import config.YamlConfig;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

import java.awt.Point;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class BotChat {
    private static final Logger log = LoggerFactory.getLogger(BotChat.class);

    /**
     * How long a bot stays quiet after speaking. Without this, a player typing three lines in a
     * row gets three overlapping generations back from the same character, which reads as a
     * malfunction rather than a conversation.
     */
    private static final long QUIET_AFTER_SPEAKING_MS = 6000;

    private static final Map<String, Long> lastSpoke = new ConcurrentHashMap<>();

    private BotChat() {
    }

    /**
     * A player said something out loud in a map. Picks at most one bot to answer.
     */
    public static void onMapChat(Character speaker, String message) {
        if (!YamlConfig.config.server.BOT_CHAT_IN_MAP || !LlmClient.isEnabled()) {
            return;
        }
        List<Character> bots = BotWorld.inMap(speaker.getMapId());
        if (bots.isEmpty()) {
            return;
        }

        Character bot = choose(bots, speaker, message, YamlConfig.config.server.BOT_CHAT_MAP_RANGE);
        if (bot == null) {
            return;
        }

        // Acted on before the reply is generated, so the bot starts moving while it is still
        // composing what to say about it - which is the order a person would do it in.
        applyIntent(bot, speaker, message);

        final int mapId = speaker.getMapId();
        final int botId = bot.getId();
        final String botName = bot.getName();
        speak(botName, speaker.getName(), "MAP", message, reply -> {
            Character live = BotWorld.get(botName);
            // Re-checked at delivery rather than at request time: generation takes seconds, and a
            // bot that has since been despawned, or a player who has walked out, should not
            // produce a line shouted into an empty room.
            if (live == null || live.getMapId() != mapId || live.getMap() == null) {
                return;
            }
            live.getMap().broadcastMessage(PacketCreator.getChatText(botId, reply, false, 0));
        });
    }

    /**
     * A player said something in party chat. Answered by a bot in the party.
     */
    public static void onPartyChat(Character speaker, String message) {
        if (!LlmClient.isEnabled() || speaker.getParty() == null) {
            return;
        }
        List<Character> bots = partyBots(speaker);
        Character bot = choose(bots, speaker, message, Integer.MAX_VALUE);
        if (bot == null) {
            return;
        }

        final String botName = bot.getName();
        speak(botName, speaker.getName(), "PARTY", message, reply -> {
            Character live = BotWorld.get(botName);
            if (live == null || live.getParty() == null) {
                return;
            }
            World world = Server.getInstance().getWorld(live.getWorld());
            if (world != null) {
                world.partyChat(live.getParty(), reply, botName);
            }
        });
    }

    /**
     * A player said something in guild chat. Answered by a bot in the same guild.
     */
    public static void onGuildChat(Character speaker, String message) {
        if (!LlmClient.isEnabled() || speaker.getGuildId() <= 0) {
            return;
        }
        List<Character> bots = guildBots(speaker.getGuildId());
        Character bot = choose(bots, speaker, message, Integer.MAX_VALUE);
        if (bot == null) {
            return;
        }

        final String botName = bot.getName();
        speak(botName, speaker.getName(), "GUILD", message, reply -> {
            Character live = BotWorld.get(botName);
            if (live == null || live.getGuildId() <= 0) {
                return;
            }
            Server.getInstance().guildChat(live.getGuildId(), botName, live.getId(), reply);
        });
    }

    /**
     * A player said something to their spouse. Only answered if the spouse is a bot.
     */
    public static void onSpouseChat(Character speaker, String message) {
        if (!LlmClient.isEnabled()) {
            return;
        }
        int partnerId = speaker.getPartnerId();
        if (partnerId <= 0 || !BotWorld.isBotCharacter(partnerId)) {
            return;
        }
        Character bot = null;
        for (Character candidate : BotWorld.all()) {
            if (candidate.getId() == partnerId) {
                bot = candidate;
                break;
            }
        }
        if (bot == null) {
            return;
        }

        final String botName = bot.getName();
        // No cooldown and no name check: this channel has exactly one other person in it, and
        // being ignored by your spouse is a worse failure than answering twice.
        request(botName, speaker.getName(), "SPOUSE", message,
                reply -> speaker.sendPacket(PacketCreator.OnCoupleMessage(botName, reply, true)));
    }

    /**
     * Acts on the handful of things a player says that are instructions. Public because the
     * whisper path needs it too - "follow me" whispered has to work the same as said out loud.
     */
    public static void applyIntent(Character bot, Character speaker, String message) {
        if (bot == null || !bot.isBot()) {
            return;
        }
        switch (BotIntent.of(message)) {
            case FOLLOW -> {
                // Walk to the speaker's map first if they are elsewhere, so a whispered
                // "follow me" from across the world still ends with them beside you.
                if (bot.getMapId() != speaker.getMapId() && speaker.getMap() != null) {
                    BotMovement.warpTo(bot, speaker.getMap(), speaker.getPosition());
                }
                BotCombat.disengage(bot.getName());
                BotMovement.follow(bot, speaker);
            }
            case FIGHT -> {
                if (bot.getMapId() != speaker.getMapId() && speaker.getMap() != null) {
                    BotMovement.warpTo(bot, speaker.getMap(), speaker.getPosition());
                }
                // Combat drives its own movement - it closes on targets and drifts back to you
                // between them - so a separate follow loop would fight it for control.
                BotMovement.stopFollowing(bot.getName());
                BotCombat.engage(bot, speaker);
            }
            case STOP -> {
                BotMovement.stopFollowing(bot.getName());
                BotCombat.disengage(bot.getName());
            }
            case NONE -> {
            }
        }
    }

    /**
     * Picks the bot that should answer, or null if none should.
     *
     * <p>Being named always wins - if you say "Elowen, are you there" then Elowen answers even if
     * she is across the map and someone else is standing next to you. Otherwise the nearest bot
     * within range answers, so who replies depends on where you are standing, which is the thing
     * that makes a town feel like a place rather than a chatroom.
     */
    private static Character choose(List<Character> bots, Character speaker, String message, int range) {
        if (bots.isEmpty()) {
            return null;
        }
        String lower = message.toLowerCase(Locale.ROOT);

        for (Character bot : bots) {
            if (lower.contains(bot.getName().toLowerCase(Locale.ROOT))) {
                return bot;   // addressed directly, cooldown does not apply
            }
        }

        Point origin = speaker.getPosition();
        Character nearest = null;
        double best = Double.MAX_VALUE;
        long now = System.currentTimeMillis();

        for (Character bot : bots) {
            if (now - lastSpoke.getOrDefault(bot.getName().toLowerCase(Locale.ROOT), 0L)
                    < QUIET_AFTER_SPEAKING_MS) {
                continue;
            }
            double distance = 0;
            if (range != Integer.MAX_VALUE) {
                Point at = bot.getPosition();
                if (origin == null || at == null) {
                    continue;
                }
                distance = origin.distance(at);
                if (distance > range) {
                    continue;
                }
            }
            if (distance < best) {
                best = distance;
                nearest = bot;
            }
        }
        return nearest;
    }

    /** Marks the bot as having spoken, then generates and delivers. */
    private static void speak(String botName, String speakerName, String channel, String message,
                              java.util.function.Consumer<String> deliver) {
        lastSpoke.put(botName.toLowerCase(Locale.ROOT), System.currentTimeMillis());
        request(botName, speakerName, channel, message, deliver);
    }

    private static void request(String botName, String speakerName, String channel, String message,
                                java.util.function.Consumer<String> deliver) {
        BotDialogue.reply(botName, speakerName, channel, message)
                .thenAccept(reply -> {
                    // Unlike a whisper, silence in a room is not a failure state - it just reads
                    // as someone not looking up. So a dropped generation says nothing rather than
                    // falling back to a canned line, which would be obviously mechanical in a
                    // channel the player is watching continuously.
                    reply.ifPresent(text -> {
                        try {
                            deliver.accept(text);
                        } catch (RuntimeException e) {
                            log.warn("Failed delivering {} reply from '{}'", channel, botName, e);
                        }
                    });
                });
    }

    private static List<Character> partyBots(Character speaker) {
        List<Character> found = new java.util.ArrayList<>();
        if (speaker.getParty() == null) {
            return found;
        }
        for (Character bot : BotWorld.all()) {
            if (bot.getParty() != null && bot.getParty().getId() == speaker.getParty().getId()) {
                found.add(bot);
            }
        }
        return found;
    }

    private static List<Character> guildBots(int guildId) {
        List<Character> found = new java.util.ArrayList<>();
        for (Character bot : BotWorld.all()) {
            if (bot.getGuildId() == guildId) {
                found.add(bot);
            }
        }
        return found;
    }
}
