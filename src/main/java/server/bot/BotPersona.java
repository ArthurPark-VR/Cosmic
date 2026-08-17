/*
    A bot's persistent identity, plus what it is currently working towards.

    Loaded from bot_persona/bot_goal and turned into the system prompt. The persona is written
    once at creation and never regenerated - stable answers across sessions are what make a
    character feel real, and they matter considerably more than model size.
*/
package server.bot;

import java.util.Optional;

public record BotPersona(
        String name,
        String temperament,
        String speechStyle,
        String backstory,
        String traits,
        String goalText) {

    /**
     * Builds the system prompt. Deliberately specific and closed-ended: vague personas produce
     * vague, interchangeable replies, and an unconstrained model will happily write paragraphs
     * that cannot be sent as a single chat line.
     */
    public String toSystemPrompt() {
        return toSystemPrompt(Familiarity.CLOSE, "", "");
    }

    /**
     * @param familiarity  how well this bot knows the person speaking - governs warmth and
     *                     whether it may use their name at all
     * @param speakerName  who is speaking
     * @param progressNote level-stage guidance for characters whose voice changes as they grow;
     *                     empty for everyone else
     */
    public String toSystemPrompt(Familiarity familiarity, String speakerName, String progressNote) {
        StringBuilder prompt = new StringBuilder(768);
        prompt.append("You are ").append(name)
                .append(", a player character in MapleStory. You are chatting in game.");

        if (!temperament.isBlank()) {
            prompt.append(" Your temperament: ").append(temperament).append('.');
        }
        if (backstory != null && !backstory.isBlank()) {
            prompt.append(' ').append(backstory.trim());
            if (!backstory.trim().endsWith(".")) {
                prompt.append('.');
            }
        }
        if (traits != null && !traits.isBlank() && !"null".equals(traits)) {
            // Passed through as-is. These are the facts that must not drift between sessions.
            prompt.append(" Stable facts about you, which must stay consistent: ")
                    .append(traits.trim()).append('.');
        }
        if (goalText != null && !goalText.isBlank()) {
            prompt.append(" You are currently working towards: ").append(goalText)
                    .append(". Mention it only when it is relevant.");
        }
        if (!speechStyle.isBlank()) {
            prompt.append(" Speak like this: ").append(speechStyle).append('.');
        }

        if (progressNote != null && !progressNote.isBlank()) {
            prompt.append(' ').append(progressNote);
        }
        // Last, so it is the freshest instruction and outweighs the persona's own warmth.
        prompt.append(' ').append(familiarity.directive(speakerName));

        prompt.append(" Reply with one or two short sentences, as a player would type in chat.")
                .append(" Do not use emotes, asterisks or narration.")
                .append(" Never mention being an AI, a bot, or a language model, and never break character.");
        return prompt.toString();
    }

    public static Optional<BotPersona> empty() {
        return Optional.empty();
    }
}
