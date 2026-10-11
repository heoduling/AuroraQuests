package gg.auroramc.quests.hooks.mythicmobs.reward;

import com.google.common.collect.Maps;
import gg.auroramc.aurora.api.reward.RewardCorrector;
import gg.auroramc.quests.AuroraQuests;
import gg.auroramc.quests.api.profile.Profile;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.utils.storage.events.LumineProfileLoadEvent;
import io.lumine.mythic.core.players.PlayerData;
import io.lumine.mythic.core.skills.stats.StatModifierType;
import io.lumine.mythic.core.skills.stats.StatType;
import io.lumine.mythic.core.skills.stats.StatRegistry;
import io.lumine.mythic.core.skills.stats.StatSource;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.Map;
import java.util.HashSet;

public class MythicStatCorrector implements RewardCorrector, Listener {
    private final AuroraQuests plugin = AuroraQuests.getInstance();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMythicProfileLoaded(LumineProfileLoadEvent<?> event) {
        if (event.getProfile() instanceof PlayerData) {
            correctRewards(event.getPlayer());
        }
    }

    @Override
    public void correctRewards(Player player) {
        if (!plugin.isEnabled() || plugin.isStopping()) return;
        var profile = plugin.getProfileManager().getProfile(player);
        if (profile == null || profile.getPlayer() != player) return;
        if (Bukkit.isOwnedByCurrentRegion(player)) {
            correctOwned(player, profile);
        } else {
            try {
                player.getScheduler().run(plugin, task -> correctOwned(player, profile), null);
            } catch (IllegalPluginAccessException ex) {
                if (plugin.isEnabled() && !plugin.isStopping()) throw ex;
            }
        }
    }

    private void correctOwned(Player player, Profile profile) {
        if (!plugin.isEnabled() || plugin.isStopping() || !player.isOnline()
                || plugin.getProfileManager().getProfile(player) != profile) return;
        var mythic = MythicBukkit.inst();
        if (mythic == null || !mythic.isEnabled()) return;
        var mythicProfile = mythic.getPlayerManager().getProfile(player);
        // Mythic publishes this profile before initializing its stat registry.
        // Its load-complete event retries correction once initialization finishes.
        if (mythicProfile == null || mythicProfile.getStatRegistry() == null) return;
        var registry = mythicProfile.getStatRegistry();

        Map<StatType, Map<StatModifierType, Double>> statMap = Maps.newHashMap();

        clearSources(registry);

        // Gather new stat modifiers
        for (var pool : profile.getQuestPools()) {
            // Correct global quests
            if (pool.isGlobal()) {
                for (var quest : pool.getQuests()) {
                    if (!quest.isCompleted()) continue;

                    for (var reward : quest.getDefinition().getRewards().values()) {
                        if (reward instanceof MythicStatReward statReward && statReward.isValid()) {
                            statMap.computeIfAbsent(statReward.getStatType(), (key) -> Maps.newHashMap())
                                    .merge(statReward.getModifierType(), statReward.getValue(quest.getPlaceholders()), Double::sum);
                        }
                    }
                }
            }

            // Correct quest pool leveling
            if (!pool.hasLeveling()) continue;
            var level = pool.getLevel();

            for (int i = 1; i < level + 1; i++) {
                var matcher = pool.getPool().getMatcherManager().getBestMatcher(i);
                if (matcher == null) continue;
                var placeholders = pool.getLevelPlaceholders(i);
                for (var reward : matcher.computeRewards(i)) {
                    if (reward instanceof MythicStatReward statReward && statReward.isValid()) {
                        statMap.computeIfAbsent(statReward.getStatType(), (key) -> Maps.newHashMap())
                                .merge(statReward.getModifierType(), statReward.getValue(placeholders), Double::sum);
                    }
                }
            }
        }

        // Apply the new stat modifiers
        for (var entry : statMap.entrySet()) {
            var statType = entry.getKey();
            for (var modifierEntry : entry.getValue().entrySet()) {
                var modifierType = modifierEntry.getKey();
                var value = modifierEntry.getValue();
                AuroraQuests.logger().debug("Adding stat " + statType.getKey() + " with value " + value + " to player " + player.getName());
                registry.putValue(statType, MythicStatReward.getSource(), modifierType, value);
            }
        }
    }

    public static void clearRewards(Player player) {
        // Native hot unload drains profiles on their player owners before disabling.
        if (!Bukkit.isOwnedByCurrentRegion(player)) return;
        var mythic = MythicBukkit.inst();
        if (mythic == null || !mythic.isEnabled()) return;
        var profile = mythic.getPlayerManager().getProfile(player);
        if (profile != null && profile.getStatRegistry() != null) clearSources(profile.getStatRegistry());
    }

    private static void clearSources(StatRegistry registry) {
        for (var entry : registry.getStatMaps().entrySet()) {
            var data = entry.getValue();
            var sources = new HashSet<StatSource>();
            sources.addAll(data.getAdditives().keySet());
            sources.addAll(data.getAdditiveMultipliers().keySet());
            sources.addAll(data.getCompoundMultipliers().keySet());
            sources.addAll(data.getSetters().keySet());
            for (var source : sources) {
                // Identity changes across hot reloads, including upgrades from 2.5.0-4.
                if (source.getClass().getName().equals(AuroraQuestsStatSource.class.getName())) {
                    registry.removeValue(entry.getKey(), source);
                }
            }
        }
    }
}
