/*
    Proves the LLM pipeline end to end before any bot framework exists.

    Sends a prompt through LlmClient with a throwaway persona and prints the reply. Useful for
    checking that the ollama container is reachable, the model is pulled, and latency is
    tolerable - all of which are far easier to debug here than inside a chat handler.
*/
package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import config.YamlConfig;
import server.bot.LlmClient;

public class AskBotCommand extends Command {
    {
        setDescription("Send a prompt to the local LLM and print the reply. Tests bot dialogue.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();

        if (!LlmClient.isEnabled()) {
            player.yellowMessage("Bot LLM is disabled. Set USE_BOT_LLM: true in config.yaml and restart.");
            return;
        }
        if (params.length == 0) {
            player.yellowMessage("Syntax: @askbot <something to say>");
            return;
        }

        String question = String.join(" ", params);

        // Stand-in for what a real bot's persona row will supply. Kept deliberately specific:
        // vague personas produce vague, interchangeable replies.
        String persona = """
                You are a MapleStory player character named Jinho, a blunt Hermit who grinds in \
                Ludibrium and resents how crowded it gets. Your favourite food is fried chicken. \
                You are three levels away from your 4th job advancement and mention it when it \
                is relevant. Reply in one or two short sentences, in character, as if typing in \
                game chat. Never mention being an AI or a language model.""";

        long startedAt = System.currentTimeMillis();
        player.dropMessage(5, "[askbot] thinking...");

        // Fire and forget: this returns immediately so the calling network thread is never held.
        LlmClient.getInstance().chat(player.getId(), persona, question)
                .thenAccept(reply -> {
                    long elapsed = System.currentTimeMillis() - startedAt;
                    if (reply.isPresent()) {
                        player.dropMessage(6, "Jinho: " + reply.get());
                        player.dropMessage(5, "[askbot] " + elapsed + "ms");
                    } else {
                        player.dropMessage(5, "[askbot] no reply after " + elapsed + "ms - a real bot "
                                + "would use a canned line here. Check the ollama container and that "
                                + "'" + YamlConfig.config.server.BOT_LLM_MODEL + "' is pulled.");
                    }
                });
    }
}
