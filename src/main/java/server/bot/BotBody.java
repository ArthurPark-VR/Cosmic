/*
    The physical facts about a bot: what it looks like, what class it is, where it stands.

    Kept apart from BotPersona on purpose. A persona is who someone is and is written once; a body
    is what the client has to draw and what the world has to place, and it changes - levels climb,
    towns change, a character can be given a body long after the persona was written.
*/
package server.bot;

public record BotBody(
        String name,
        int characterId,
        int level,
        int gender,
        int hair,
        int face,
        String jobName,
        int mapId) {

    public boolean hasCharacter() {
        return characterId > 0;
    }
}
