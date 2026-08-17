/*
    What a bot looks like.

    Every id in here was checked against the Character.wz shipped with this server rather than
    recalled - an equip or hairstyle the client cannot resolve is worse than a dull one, because
    it is the client that has to draw it and a missing asset is not a graceful failure.

    Two encodings matter and are easy to get backwards:
      hair = style base + colour, where colour is the last digit, 0-7
      face = 2G000 + colour*100 + index, where G is 0 for male and 1 for female
    So 30030 and 30034 are the same haircut in different colours, and 20001 and 20401 are the same
    face with different eyes.

    The five written characters get looks chosen for them. Everyone else is derived from their
    name, which means it is stable across restarts and across a rebuilt database - a character
    whose face changes when the server restarts is not a character.
*/
package server.bot;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;

import java.util.Locale;
import java.util.Map;

public final class BotLook {
    private static final Logger log = LoggerFactory.getLogger(BotLook.class);

    private static final short SLOT_TOP = -5;
    private static final short SLOT_BOTTOM = -6;
    private static final short SLOT_SHOES = -7;
    private static final short SLOT_WEAPON = -11;

    /** Hair styles with all eight colours present, so colour can be chosen freely. */
    private static final int[] MALE_HAIR_STYLES = {30000, 30020, 30030, 30040, 30050, 30060};
    private static final int[] FEMALE_HAIR_STYLES = {31000, 31010, 31020, 31030, 31040, 31050, 31060};

    /** Face indices that exist for both genders across all eight eye colours. */
    private static final int[] FACE_INDICES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, 12, 13, 20, 21, 22, 23, 24, 25, 26};

    private static final int[] MALE_TOPS = {1040002, 1040006, 1040010, 1040015, 1040021, 1040036, 1040042};
    private static final int[] FEMALE_TOPS = {1041002, 1041006, 1041010, 1041015, 1041027, 1041038, 1041046};
    private static final int[] MALE_BOTTOMS = {1060002, 1060006, 1060010, 1060016, 1060023, 1060026};
    private static final int[] FEMALE_BOTTOMS = {1061002, 1061008, 1061009, 1061020, 1061024, 1061039};
    private static final int[] SHOES = {1072001, 1072004, 1072005, 1072008, 1072037, 1072038, 1072039};

    /**
     * A single-piece outfit worn instead of a top and bottom. Reserved for the written cast, so
     * they read as distinct at a glance in a town full of separates.
     */
    public record Look(int hair, int face, int top, int bottom, int overall, int shoes) {
        public boolean wearsOverall() {
            return overall > 0;
        }
    }

    /**
     * The written cast, chosen rather than derived. Overalls and deliberate colours, because these
     * are the five you are meant to recognise across a crowded map.
     */
    private static final Map<String, Look> WRITTEN = Map.of(
            // Dawn Warrior: plain, solid, unfussy. Brown hair, warm face.
            "cassiel", new Look(30030, 20301, 0, 0, 1050018, 1072005),
            // Thunder Breaker: loud. Orange hair, cocky face.
            "rhys", new Look(30020, 20102, 0, 0, 1050081, 1072038),
            // Blaze Wizard: severe and expensive-looking. Dark red hair.
            "seraphine", new Look(31050, 21306, 0, 0, 1051020, 1072039),
            // Wind Archer: muted, green, easy to miss on purpose.
            "elowen", new Look(31040, 21000, 0, 0, 1051023, 1072037),
            // Aran: visibly a beginner. Cheap separates, plain everything.
            "wren", new Look(31000, 21001, 1041002, 1061002, 0, 1072001)
    );

    private BotLook() {
    }

    public static Look forBot(String name, int gender, int hairOverride, int faceOverride) {
        Look written = WRITTEN.get(name.toLowerCase(Locale.ROOT));
        if (written != null) {
            return written;
        }

        // Derived from the name so it survives restarts and database rebuilds unchanged.
        //
        // Each attribute reads a different byte of an avalanched hash rather than dividing one
        // number by a series of small constants. That earlier approach left the attributes
        // correlated - similar names produced similar quotients - and the cast came out sharing
        // nine faces between twenty-one people.
        int seed = mix(stableHash(name));
        boolean female = gender == 1;

        int[] styles = female ? FEMALE_HAIR_STYLES : MALE_HAIR_STYLES;
        int hair = styles[byteAt(seed, 0) % styles.length] + byteAt(seed, 1) % 8;
        int face = (female ? 21000 : 20000)
                + (byteAt(seed, 2) % 8) * 100
                + FACE_INDICES[byteAt(seed, 3) % FACE_INDICES.length];

        // A hair or face explicitly set in the database wins, so any of them can be restyled by
        // hand without touching code. Zero means "derive one", which is what the cast carries.
        if (hairOverride > 0) {
            hair = hairOverride;
        }
        if (faceOverride > 0) {
            face = faceOverride;
        }

        int[] tops = female ? FEMALE_TOPS : MALE_TOPS;
        int[] bottoms = female ? FEMALE_BOTTOMS : MALE_BOTTOMS;
        int outfitSeed = mix(seed);
        return new Look(
                hair,
                face,
                tops[byteAt(outfitSeed, 0) % tops.length],
                bottoms[byteAt(outfitSeed, 1) % bottoms.length],
                0,
                SHOES[byteAt(outfitSeed, 2) % SHOES.length]);
    }

    /** One byte of the hash, so attributes drawn from different bytes are independent. */
    private static int byteAt(int hash, int index) {
        return (hash >>> (index * 8)) & 0xFF;
    }

    /**
     * Avalanche finalizer, so a one-character change in a name changes every attribute rather
     * than nudging one of them.
     */
    private static int mix(int hash) {
        int h = hash;
        h ^= h >>> 16;
        h *= 0x7feb352d;
        h ^= h >>> 15;
        h *= 0x846ca68b;
        h ^= h >>> 16;
        return h;
    }

    /**
     * Applies a look to a loaded character. Idempotent, and called on every spawn rather than only
     * at creation - the cast already existed by the time it had faces worth having, and a look
     * that can only be set once is a look that can never be corrected.
     *
     * @return true if anything changed and the character needs saving
     */
    public static boolean apply(Character chr, Look look, Job job) {
        boolean changed = false;

        if (chr.getHair() != look.hair()) {
            chr.setHair(look.hair());
            changed = true;
        }
        if (chr.getFace() != look.face()) {
            chr.setFace(look.face());
            changed = true;
        }

        Inventory equipped = chr.getInventory(InventoryType.EQUIPPED);
        if (look.wearsOverall()) {
            // An overall occupies the top slot and forbids a bottom; leaving trousers underneath
            // is what produces the classic half-dressed character.
            changed |= wear(equipped, look.overall(), SLOT_TOP);
            changed |= clear(equipped, SLOT_BOTTOM);
        } else {
            changed |= wear(equipped, look.top(), SLOT_TOP);
            changed |= wear(equipped, look.bottom(), SLOT_BOTTOM);
        }
        changed |= wear(equipped, look.shoes(), SLOT_SHOES);
        changed |= wear(equipped, BotBodyFactory.weaponFor(job), SLOT_WEAPON);

        return changed;
    }

    private static boolean wear(Inventory equipped, int itemId, short slot) {
        if (itemId <= 0) {
            return false;
        }
        Item current = equipped.getItem(slot);
        if (current != null && current.getItemId() == itemId) {
            return false;
        }
        try {
            Item item = ItemInformationProvider.getInstance().getEquipById(itemId);
            if (item == null) {
                log.warn("Bot equip {} did not resolve, leaving slot {} as it was", itemId, slot);
                return false;
            }
            if (current != null) {
                equipped.removeSlot(slot);
            }
            item.setPosition((byte) slot);
            equipped.addItemFromDB(item);
            return true;
        } catch (RuntimeException e) {
            log.warn("Bot equip {} failed for slot {}", itemId, slot, e);
            return false;
        }
    }

    private static boolean clear(Inventory equipped, short slot) {
        if (equipped.getItem(slot) == null) {
            return false;
        }
        equipped.removeSlot(slot);
        return true;
    }

    /**
     * String.hashCode is specified by the language, so it does not drift between JVM versions the
     * way an arbitrary hash might. That matters: this is what keeps a character's face the same
     * one it had yesterday.
     */
    private static int stableHash(String value) {
        return value.toLowerCase(Locale.ROOT).hashCode();
    }
}
