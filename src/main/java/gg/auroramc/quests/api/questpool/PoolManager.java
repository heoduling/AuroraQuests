package gg.auroramc.quests.api.questpool;

import gg.auroramc.aurora.api.reward.*;
import gg.auroramc.aurora.api.util.NamespacedId;
import gg.auroramc.quests.AuroraQuests;
import gg.auroramc.quests.api.event.QuestsLoadedEvent;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;

import java.util.*;

public class PoolManager {
    @Getter
    private final RewardFactory rewardFactory = new RewardFactory();
    @Getter
    private final RewardAutoCorrector rewardAutoCorrector = new RewardAutoCorrector();

    private volatile Map<String, Pool> registry = Map.of();

    public PoolManager() {
        rewardFactory.registerRewardType(NamespacedId.fromDefault("command"), CommandReward.class);
        rewardFactory.registerRewardType(NamespacedId.fromDefault("money"), MoneyReward.class);
        rewardFactory.registerRewardType(NamespacedId.fromDefault("item"), ItemReward.class);

        try {
            var scheduler = StdSchedulerFactory.getDefaultScheduler();
            // Jobs read their context directly; setter injection only creates Bean cache entries.
            scheduler.setJobFactory(new org.quartz.simpl.SimpleJobFactory());
            scheduler.start();
        } catch (SchedulerException e) {
            AuroraQuests.logger().severe("Failed to start scheduler: " + e.getMessage());
        }
    }

    public void reload(List<Pool> pools) {
        for (Pool pool : registry.values()) {
            pool.dispose();
        }
        var replacement = new HashMap<String, Pool>();
        for (Pool pool : pools) {
            replacement.put(pool.getId(), pool);
        }
        registry = Map.copyOf(replacement);
        for (Pool pool : pools) {
            pool.start();
        }
        Bukkit.getPluginManager().callEvent(new QuestsLoadedEvent());
    }

    public Pool getPool(String id) {
        return registry.get(id);
    }

    public Set<String> getPoolIds() {
        return Collections.unmodifiableSet(registry.keySet());
    }

    public Collection<Pool> getPools() {
        return Collections.unmodifiableCollection(registry.values());
    }
}
