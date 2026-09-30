package gg.auroramc.quests.hooks.luckperms;

import gg.auroramc.aurora.api.reward.PermissionReward;
import gg.auroramc.aurora.api.util.NamespacedId;
import gg.auroramc.quests.AuroraQuests;
import gg.auroramc.quests.hooks.Hook;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.event.EventSubscription;
import org.bukkit.Bukkit;

public class LuckPermsHook implements Hook {
    private AuroraQuests plugin;
    private EventSubscription<UserDataRecalculateEvent> subscription;

    @Override
    public void hook(AuroraQuests plugin) {
        this.plugin = plugin;

        plugin.getPoolManager().getRewardFactory()
                .registerRewardType(NamespacedId.fromDefault("permission"), PermissionReward.class);

        plugin.getPoolManager().getRewardAutoCorrector()
                .registerCorrector(NamespacedId.fromDefault("permission"), new PermissionCorrector());

        var lp = LuckPermsProvider.get();

        subscription = lp.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, this::onDataRecalculate);

        AuroraQuests.logger().info("Hooked into LuckPerms for permission rewards and for permission start requirements.");
    }

    public void close() {
        if (subscription != null) { subscription.close(); subscription = null; }
    }

    private void onDataRecalculate(UserDataRecalculateEvent event) {
        if (plugin.isStopping()) return;
        var player = Bukkit.getPlayer(event.getUser().getUniqueId());
        if (player != null) {
            player.getScheduler().run(plugin, task -> {
                if (plugin.isStopping()) return;
                var profile = plugin.getProfileManager().getProfile(player);
                if (profile != null) {
                    for (var pool : profile.getQuestPools()) {
                        pool.unlock(false);
                        pool.rollIfNecessary(true);
                        pool.startQuests();
                    }
                }
            }, null);
        }
    }
}
