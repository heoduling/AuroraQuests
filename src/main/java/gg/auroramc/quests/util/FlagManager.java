package gg.auroramc.quests.util;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import gg.auroramc.quests.AuroraQuests;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class FlagManager {
    public static StateFlag QUESTS_PROGRESS
            = new StateFlag("aurora-quests-progress", true);

    public static void registerFlags() {
        var registry = WorldGuard.getInstance().getFlagRegistry();
        registerXpGainFlag(registry);
    }

    private static void registerXpGainFlag(FlagRegistry registry) {
        var existing = registry.get(QUESTS_PROGRESS.getName());
        if (existing instanceof StateFlag state) {
            QUESTS_PROGRESS = state;
            return;
        }
        if (existing != null) throw new IllegalStateException("aurora-quests-progress is registered with an incompatible flag type");
        try {
            registry.register(QUESTS_PROGRESS);
            AuroraQuests.logger().info("Registered WorldGuard flag aurora-quests-progress");
        } catch (FlagConflictException e) {
            AuroraQuests.logger().warning("Could not register WorldGuard flag aurora-quests-progress");
        }
    }

    public static boolean isBlocked(Player player) {
        return FlagUtil.isBlocked(player, QUESTS_PROGRESS);
    }

    public static boolean isBlocked(Player player, Location location) {
        return FlagUtil.isBlocked(player, location, QUESTS_PROGRESS);
    }
}
