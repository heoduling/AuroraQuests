package gg.auroramc.quests.api.profile;

import gg.auroramc.aurora.api.user.AuroraUser;
import gg.auroramc.quests.AuroraQuests;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ProfileManager {
    private final Map<UUID, Profile> profileMap = new ConcurrentHashMap<>();

    public void createProfile(AuroraUser user) {
        profileMap.compute(user.getUniqueId(), (uuid, existing) -> {
            if (existing != null && existing.getUser() == user && existing.getPlayer() == user.getPlayer()) return existing;
            if (existing != null) existing.destroy();
            return new Profile(user);
        });
    }

    public Profile getProfile(Player player) {
        return profileMap.get(player.getUniqueId());
    }

    public void destroyProfile(UUID uuid) {
        var profile = profileMap.remove(uuid);
        if (profile != null) {
            profile.destroy();
        }
    }

    public Collection<Profile> getProfiles() {
        return profileMap.values();
    }

    /** A snapshot is only a routing list; revalidate each profile on its player's owner. */
    public void forEachProfile(Consumer<Profile> action) {
        var plugin = AuroraQuests.getInstance();
        if (plugin.isStopping()) return;
        for (var profile : new ArrayList<>(profileMap.values())) {
            if (plugin.isStopping()) return;
            var player = profile.getPlayer();
            if (player == null) continue;
            try {
                player.getScheduler().run(plugin, task -> {
                    if (plugin.isStopping() || !player.isOnline()
                            || profileMap.get(player.getUniqueId()) != profile) return;
                    action.accept(profile);
                }, null);
            } catch (IllegalPluginAccessException stopped) {
                if (!plugin.isEnabled() || plugin.isStopping()) return;
                throw stopped;
            }
        }
    }

    public void clear() {
        profileMap.values().forEach(Profile::destroy);
        profileMap.clear();
    }
}
