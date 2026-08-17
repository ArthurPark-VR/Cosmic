/*
    How well a bot knows one specific person.

    Exists because "do not be overly familiar at first" is not something a persona can express.
    A persona is a constant; familiarity is a function of shared history. Without this, a model
    handed a warm character will greet a total stranger like an old friend on the first line -
    and it will use their name unprompted, because the name is sitting right there in the prompt,
    which reads as unsettling rather than friendly.

    For the characters written as possible attachments this is the whole arc: an attachment needs
    a distance to close, and without stages there is no distance.
*/
package server.bot;

public enum Familiarity {
    STRANGER,
    ACQUAINTANCE,
    FRIEND,
    CLOSE;

    public static Familiarity of(int interactions) {
        if (interactions <= 0) {
            return STRANGER;
        }
        if (interactions < 6) {
            return ACQUAINTANCE;
        }
        if (interactions < 25) {
            return FRIEND;
        }
        return CLOSE;
    }

    /**
     * The instruction handed to the model. Written as behaviour rather than as a label, because
     * a model told "you are at stage ACQUAINTANCE" will invent what that means.
     */
    public String directive(String speakerName) {
        return switch (this) {
            case STRANGER -> "You have never spoken to this person before and you do not know who "
                    + "they are. Do not use their name, do not greet them like a friend, and do not "
                    + "act pleased to hear from them. Be civil but brief and a little guarded, the "
                    + "way anyone is with a stranger who messages them out of nowhere. If you want "
                    + "to know who they are, ask.";
            case ACQUAINTANCE -> "You have spoken with " + speakerName + " a few times now. You "
                    + "recognise the name and you are friendly enough, but you do not know them "
                    + "well yet and you would not assume closeness.";
            case FRIEND -> "You know " + speakerName + " reasonably well by now and you are "
                    + "comfortable with them. You can be relaxed, tease a little, and refer back "
                    + "to things you have talked about before.";
            case CLOSE -> "You know " + speakerName + " well and you are genuinely glad to hear "
                    + "from them. You can be warm and open, and you can say what you actually "
                    + "think without softening it first.";
        };
    }

    /**
     * Interactions still needed to reach the next stage, or -1 at the top.
     * Used only for the admin readout - the bots are never told their own counters.
     */
    public int nextThreshold() {
        return switch (this) {
            case STRANGER -> 1;
            case ACQUAINTANCE -> 6;
            case FRIEND -> 25;
            case CLOSE -> -1;
        };
    }
}
