/*
	This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
		       Matthias Butz <matze@odinms.de>
		       Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.autoban.AutobanFactory;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ChatLogger;
import server.bot.BotDialogue;
import tools.PacketCreator;
import tools.PacketCreator.WhisperFlag;

/**
 * @author Chronos
 */
public final class WhisperHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(WhisperHandler.class);

    // result types, not sure if there are proper names for these
    public static final byte RT_ITC = 0x00;
    public static final byte RT_SAME_CHANNEL = 0x01;
    public static final byte RT_CASH_SHOP = 0x02;
    public static final byte RT_DIFFERENT_CHANNEL = 0x03;

    @Override
    public void handlePacket(InPacket p, Client c) {
        byte request = p.readByte();
        String name = p.readString();
        Character target = c.getWorldServer().getPlayerStorage().getCharacterByName(name);

        if (target == null) {
            // Not a logged-in player - but it may be a bot persona. Whispering a name is how
            // players actually reach each other, so this is the natural interface for talking
            // to a bot, and it works whether or not the bot is spawned on a map anywhere.
            if (request == (WhisperFlag.WHISPER | WhisperFlag.REQUEST) && BotDialogue.isBot(name)) {
                handleBotWhisper(p.readString(), c.getPlayer(), name);
                return;
            }
            c.sendPacket(PacketCreator.getWhisperResult(name, false));
            return;
        }

        switch (request) {
            case WhisperFlag.LOCATION | WhisperFlag.REQUEST:
                handleFind(c.getPlayer(), target, WhisperFlag.LOCATION);
                break;
            case WhisperFlag.WHISPER | WhisperFlag.REQUEST:
                String message = p.readString();
                handleWhisper(message, c.getPlayer(), target);
                break;
            case WhisperFlag.LOCATION_FRIEND | WhisperFlag.REQUEST:
                handleFind(c.getPlayer(), target, WhisperFlag.LOCATION_FRIEND);
                break;
            default:
                log.warn("Unknown request {} triggered by {}", request, c.getPlayer().getName());
                break;
        }
    }

    private void handleFind(Character user, Character target, byte flag) {
        if (user.gmLevel() >= target.gmLevel()) {
            if (target.getCashShop().isOpened()) {
                user.sendPacket(PacketCreator.getFindResult(target, RT_CASH_SHOP, -1, flag));
            } else if (target.getClient().getChannel() == user.getClient().getChannel()) {
                user.sendPacket(PacketCreator.getFindResult(target, RT_SAME_CHANNEL, target.getMapId(), flag));
            } else {
                user.sendPacket(PacketCreator.getFindResult(target, RT_DIFFERENT_CHANNEL, target.getClient().getChannel() - 1, flag));
            }
        } else {
            // not found for whisper is the same message
            user.sendPacket(PacketCreator.getWhisperResult(target.getName(), false));
        }
    }

    /**
     * Delivers a whisper to a bot and whispers its reply back, using the same packets a real
     * player conversation uses - from the client's point of view there is no difference.
     */
    private void handleBotWhisper(String message, Character user, String botName) {
        if (message.length() > Byte.MAX_VALUE) {
            return;
        }
        ChatLogger.log(user.getClient(), "Whisper To " + botName, message);

        // Acknowledge immediately so the client shows the whisper as delivered; the reply
        // arrives seconds later, exactly as a slow-typing player's would.
        user.sendPacket(PacketCreator.getWhisperResult(botName, true));

        // "follow me" has to work whispered as well as said out loud, and it has to happen now
        // rather than after a generation that might not arrive.
        server.bot.BotChat.applyIntent(server.bot.BotWorld.get(botName), user, message);

        final int channel = user.getClient().getChannel() - 1;
        BotDialogue.reply(botName, user.getName(), "WHISPER", message)
                .thenAccept(reply -> {
                    String text = reply.orElseGet(() -> BotDialogue.cannedFallback(botName));
                    user.sendPacket(PacketCreator.getWhisperReceive(botName, channel, false, text));
                });
    }

    private void handleWhisper(String message, Character user, Character target) {
        if (user.getAutobanManager().getLastSpam(7) + 200 > currentServerTime()) {
            return;
        }
        user.getAutobanManager().spam(7);

        if (message.length() > Byte.MAX_VALUE) {
            AutobanFactory.PACKET_EDIT.alert(user, user.getName() + " tried to packet edit with whispers.");
            log.warn("Chr {} tried to send text with length of {}", user.getName(), message.length());
            user.getClient().disconnect(true, false);
            return;
        }

        ChatLogger.log(user.getClient(), "Whisper To " + target.getName(), message);

        target.sendPacket(PacketCreator.getWhisperReceive(user.getName(), user.getClient().getChannel() - 1, user.isGM(), message));

        boolean hidden = target.isHidden() && target.gmLevel() > user.gmLevel();
        user.sendPacket(PacketCreator.getWhisperResult(target.getName(), !hidden));
    }
}
