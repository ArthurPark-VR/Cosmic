/*
    Creates the `characters` row behind a bot, once, the first time it needs a body.

    Everything social in this server keys off a real character row: party membership, guild
    membership, buddy lists and marriage all store a characterid and join against it. A bot
    rendered with raw spawn packets would be visible and simultaneously invisible to every one of
    those systems, so there is no shortcut worth taking here - bots get real rows and then the
    existing systems need no special cases at all.

    Deliberately reuses Character.insertNewChar, the same call the character creation screen makes.
    Hand-rolling the INSERT would drift the moment the schema changes, and this path already knows
    about keymaps, quickslots, inventory and skills.
*/
package server.bot;

import client.Character;
import client.Client;
import client.Job;
import client.SkinColor;
import client.creator.CharacterFactoryRecipe;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.MapId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;

import java.util.Locale;

public final class BotBodyFactory {
    private static final Logger log = LoggerFactory.getLogger(BotBodyFactory.class);

    // Equipped inventory slots. Negative by convention throughout this codebase.
    private static final byte SLOT_TOP = -5;
    private static final byte SLOT_BOTTOM = -6;
    private static final byte SLOT_SHOES = -7;
    private static final byte SLOT_WEAPON = -11;

    private BotBodyFactory() {
    }

    /**
     * Creates the character row for a bot and returns its id, or -1 on failure.
     *
     * @param botClient must already carry the bot-owning account id, since Character.getDefault
     *                  reads accountid straight off the client
     */
    public static int create(Client botClient, BotBody body) {
        Job job = resolveJob(body.jobName(), body.level());

        Character chr = Character.getDefault(botClient);
        chr.setWorld(botClient.getWorld());
        chr.setName(body.name());
        chr.setGender(body.gender());
        chr.setHair(body.hair());
        chr.setFace(body.face());
        chr.setSkinColor(SkinColor.getById(0));
        chr.setJob(job);
        chr.setLevel(body.level());
        chr.setMapId(body.mapId());

        equip(chr, job, body.gender());

        CharacterFactoryRecipe recipe = new CharacterFactoryRecipe(
                job, body.level(), body.mapId(), 0, 0, 0, 0);
        applyStats(recipe, job, body.level());

        if (!chr.insertNewChar(recipe)) {
            log.warn("Could not create a body for bot '{}'", body.name());
            return -1;
        }
        log.info("Created body for bot '{}' - {} level {} in map {}",
                body.name(), job, body.level(), body.mapId());
        return chr.getId();
    }

    /**
     * Stats that roughly match the level, so a bot standing next to you does not read as a level 1
     * with a big number over its head. Not balanced for combat - combat comes later and will
     * recompute from equipment anyway.
     */
    private static void applyStats(CharacterFactoryRecipe recipe, Job job, int level) {
        int primary = 4 + level * 5;
        int secondary = 4 + level;

        switch (job.getId() / 100) {
            case 1, 11 -> {   // warrior, dawn warrior
                recipe.setStr(primary);
                recipe.setDex(secondary);
            }
            case 2, 12 -> {   // magician, blaze wizard
                recipe.setInt(primary);
                recipe.setLuk(secondary);
            }
            case 3, 13 -> {   // bowman, wind archer
                recipe.setDex(primary);
                recipe.setStr(secondary);
            }
            case 4, 14 -> {   // thief, night walker
                recipe.setLuk(primary);
                recipe.setDex(secondary);
            }
            case 5, 15 -> {   // pirate, thunder breaker
                recipe.setDex(primary);
                recipe.setStr(secondary);
            }
            default -> {      // aran and anything unrecognised
                recipe.setStr(primary);
                recipe.setDex(secondary);
            }
        }

        recipe.setMaxHp(Math.min(30000, 50 + level * 60));
        recipe.setMaxMp(Math.min(30000, 5 + level * 25));
        recipe.setMeso(1_000_000);
    }

    /**
     * Starter clothing and a weapon that suits the branch. Every id here is base tier and present
     * in a stock v83 client - an equip the client cannot resolve is worse than being unarmed,
     * since it is the client that has to draw it.
     */
    private static void equip(Character chr, Job job, int gender) {
        boolean female = gender == 1;
        Inventory equipped = chr.getInventory(InventoryType.EQUIPPED);

        addEquip(equipped, female ? 1041002 : 1040002, SLOT_TOP);
        addEquip(equipped, female ? 1061002 : 1060002, SLOT_BOTTOM);
        addEquip(equipped, 1072001, SLOT_SHOES);
        addEquip(equipped, weaponFor(job), SLOT_WEAPON);
    }

    static int weaponFor(Job job) {
        return switch (job.getId() / 100) {
            case 2, 12 -> 1382000;   // staff
            case 3, 13 -> 1452000;   // bow
            case 4, 14 -> 1332000;   // dagger
            case 5, 15 -> 1482000;   // knuckle
            case 21 -> 1442079;      // polearm, the one Aran creation hands out
            default -> 1302000;      // sword
        };
    }

    private static void addEquip(Inventory equipped, int itemId, byte slot) {
        try {
            Item item = ItemInformationProvider.getInstance().getEquipById(itemId);
            if (item == null) {
                log.warn("Bot equip {} did not resolve, leaving the slot empty", itemId);
                return;
            }
            item.setPosition(slot);
            equipped.addItemFromDB(item);
        } catch (RuntimeException e) {
            // Never fatal. A bot with an empty slot is a cosmetic problem; a bot that failed to
            // be created at all is a missing character.
            log.warn("Bot equip {} failed, leaving the slot empty", itemId, e);
        }
    }

    /**
     * Maps the display job name carried in a persona's traits to a Job, choosing the advancement
     * tier from level for the classes that have numbered tiers.
     */
    public static Job resolveJob(String displayName, int level) {
        if (displayName == null || displayName.isBlank()) {
            return Job.BEGINNER;
        }
        String key = displayName.toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
        int tier = tierFor(level);

        Job tiered = switch (key) {
            case "DAWNWARRIOR" -> tierOf("DAWNWARRIOR", tier);
            case "BLAZEWIZARD" -> tierOf("BLAZEWIZARD", tier);
            case "WINDARCHER" -> tierOf("WINDARCHER", tier);
            case "NIGHTWALKER" -> tierOf("NIGHTWALKER", tier);
            case "THUNDERBREAKER" -> tierOf("THUNDERBREAKER", tier);
            case "ARAN" -> tierOf("ARAN", tier);
            default -> null;
        };
        if (tiered != null) {
            return tiered;
        }

        // Names players use that the enum spells differently or does not have at all.
        key = switch (key) {
            case "ROGUE" -> "THIEF";              // no ROGUE constant; 1st job thief is THIEF
            case "WIZARD" -> "FP_WIZARD";         // ambiguous by name, fire/poison is the default
            case "MAGE" -> "FP_MAGE";
            case "ARCHMAGE" -> "FP_ARCHMAGE";
            case "BOWMASTER", "BOWMAN" -> key;    // already exact, listed so the intent is clear
            default -> key;
        };

        try {
            return Job.valueOf(key);
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised bot job '{}', falling back to beginner", displayName);
            return Job.BEGINNER;
        }
    }

    private static Job tierOf(String prefix, int tier) {
        try {
            return Job.valueOf(prefix + tier);
        } catch (IllegalArgumentException e) {
            return Job.BEGINNER;
        }
    }

    private static int tierFor(int level) {
        if (level < 30) {
            return 1;
        }
        if (level < 70) {
            return 2;
        }
        if (level < 120) {
            return 3;
        }
        return 4;
    }

    /**
     * Maps the town named in a persona's traits to a map id. Unknown places fall back to Henesys
     * rather than failing - a bot standing in the wrong town is a much smaller problem than a bot
     * that could not be spawned.
     */
    public static int mapForHaunt(String haunt) {
        if (haunt == null) {
            return MapId.HENESYS;
        }
        return switch (haunt.trim().toLowerCase(Locale.ROOT)) {
            case "ereve" -> MapId.EREVE;
            case "rien" -> MapId.RIEN;
            case "ellinia" -> MapId.ELLINIA;
            case "perion" -> MapId.PERION;
            case "kerning city", "kerning" -> MapId.KERNING_CITY;
            case "lith harbor", "lith harbour" -> MapId.LITH_HARBOUR;
            case "sleepywood" -> MapId.SLEEPYWOOD;
            case "nautilus" -> MapId.NAUTILUS_HARBOR;
            case "orbis" -> MapId.ORBIS;
            case "el nath" -> MapId.EL_NATH;
            case "ludibrium" -> MapId.LUDIBRIUM;
            case "aquarium" -> MapId.AQUARIUM;
            case "leafre" -> MapId.LEAFRE;
            default -> MapId.HENESYS;
        };
    }
}
