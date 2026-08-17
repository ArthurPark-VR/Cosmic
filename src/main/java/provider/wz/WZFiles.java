package provider.wz;

import java.nio.file.Files;
import java.nio.file.Path;

public enum WZFiles {
    QUEST("Quest"),
    ETC("Etc"),
    ITEM("Item"),
    CHARACTER("Character"),
    STRING("String"),
    LIST("List"),
    MOB("Mob"),
    MAP("Map"),
    NPC("Npc"),
    REACTOR("Reactor"),
    SKILL("Skill"),
    SOUND("Sound"),
    UI("UI");

    public static final String DIRECTORY = getWzDirectory();

    private final String fileName;

    WZFiles(String name) {
        this.fileName = name + ".wz";
    }

    public Path getFile() {
        // Resolved per call, not from the DIRECTORY constant: DIRECTORY freezes whatever
        // "wz-path" held when this enum first loaded, which breaks test suites where one
        // test (MobSkillFactoryTest) points the property at a fixture directory. At
        // runtime the property never changes, so behavior is identical.
        return Path.of(getWzDirectory(), fileName);
    }

    public String getFilePath() {
        return getFile().toString();
    }

    private static String getWzDirectory() {
        // Either provide a custom directory path through the "wz-path" property when launching the .jar, or don't provide one to use the default "wz" directory
        String propertyPath = System.getProperty("wz-path");
        if (propertyPath != null && Files.isDirectory(Path.of(propertyPath))) {
            return propertyPath;
        }

        return "wz";
    }
}
