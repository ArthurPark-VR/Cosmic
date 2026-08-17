package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Pure-registry tests for the claim/slot/section math the grind system leans on to spread bots
// across spots (and, past the cap, to level sharers instead of stacking them). The registry is
// global static state, so every test uses its own map id.
class BotSpotClaimsTest {

    @Test
    void capacityIsEnforcedAndSquatBypassesIt() {
        int map = 990_001;
        assertEquals(0, BotSpotClaims.claim(map, 0, 2, 11));
        assertEquals(1, BotSpotClaims.claim(map, 0, 2, 12));
        // spot full at capacity -> refused
        assertEquals(-1, BotSpotClaims.claim(map, 0, 2, 13));
        // the saturated-share path registers with an uncapped claim: always lands, lowest free slot
        assertEquals(2, BotSpotClaims.claim(map, 0, Integer.MAX_VALUE, 13));
        assertEquals(3, BotSpotClaims.holders(map, 0));
        BotSpotClaims.release(map, 0, 11);
        BotSpotClaims.release(map, 0, 12);
        BotSpotClaims.release(map, 0, 13);
        assertEquals(0, BotSpotClaims.holders(map, 0));
    }

    @Test
    void reclaimBySameBotReturnsExistingSlot() {
        int map = 990_002;
        int slot = BotSpotClaims.claim(map, 5, 3, 42);
        assertEquals(slot, BotSpotClaims.claim(map, 5, 3, 42));
        assertEquals(1, BotSpotClaims.holders(map, 5));
        BotSpotClaims.release(map, 5, 42);
    }

    @Test
    void slotsAreStableWhenAnotherHolderLeaves() {
        int map = 990_003;
        assertEquals(0, BotSpotClaims.claim(map, 1, 3, 21));
        assertEquals(1, BotSpotClaims.claim(map, 1, 3, 22));
        assertEquals(2, BotSpotClaims.claim(map, 1, 3, 23));
        BotSpotClaims.release(map, 1, 22); // middle holder leaves
        // survivors keep their slots (their sections must never shift under them)
        assertEquals(0, BotSpotClaims.claim(map, 1, 3, 21));
        assertEquals(2, BotSpotClaims.claim(map, 1, 3, 23));
        // a newcomer takes the freed middle slot
        assertEquals(1, BotSpotClaims.claim(map, 1, 3, 24));
        BotSpotClaims.release(map, 1, 21);
        BotSpotClaims.release(map, 1, 23);
        BotSpotClaims.release(map, 1, 24);
    }

    @Test
    void sectionsPartitionTheSpanWithoutOverlapOrGaps() {
        int minX = -600, maxX = 400, cap = 3;
        int prevEnd = minX;
        for (int slot = 0; slot < cap; slot++) {
            int[] band = BotSpotClaims.section(minX, maxX, slot, cap);
            assertEquals(prevEnd, band[0]); // starts where the previous band ended (no gap/overlap)
            assertTrue(band[1] > band[0]);
            prevEnd = band[1];
        }
        assertEquals(maxX, prevEnd); // bands cover the whole span
        // capacity 1 = the whole span
        int[] whole = BotSpotClaims.section(minX, maxX, 0, 1);
        assertEquals(minX, whole[0]);
        assertEquals(maxX, whole[1]);
    }
}
