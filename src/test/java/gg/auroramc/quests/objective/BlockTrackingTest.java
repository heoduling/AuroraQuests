package gg.auroramc.quests.objective;

import gg.auroramc.aurora.Aurora;
import gg.auroramc.aurora.api.expansions.AuroraExpansion;
import gg.auroramc.aurora.api.expansions.ExpansionManager;
import gg.auroramc.aurora.api.item.TypeId;
import gg.auroramc.aurora.api.user.AuroraUser;
import gg.auroramc.aurora.expansions.region.RegionExpansion;
import gg.auroramc.quests.api.data.QuestData;
import gg.auroramc.quests.api.event.objective.PlayerBreakCustomBlockEvent;
import gg.auroramc.quests.api.objective.ObjectiveDefinition;
import gg.auroramc.quests.api.objective.ObjectiveMeta;
import gg.auroramc.quests.api.profile.Profile;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockDropItemEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class BlockTrackingTest {
    private final Player player = proxy(Player.class);
    private final Block block = proxy(Block.class);
    private final BlockState state = proxy(BlockState.class);
    private Field expansionField;
    private Object originalManager;
    private Profile.TaskDataWrapper data;
    private ObjectiveDefinition definition;

    @BeforeAll
    static void server() throws Exception {
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, Server.class.cast(Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getLogger" -> Logger.getLogger("BlockTrackingTest");
                    case "getName" -> "Folia";
                    case "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "26.2";
                    case "isPrimaryThread" -> true;
                    default -> throw new AssertionError(m);
                })));
    }

    @BeforeEach
    void setup() throws Exception {
        expansionField = Aurora.class.getDeclaredField("expansionManager");
        expansionField.setAccessible(true);
        originalManager = expansionField.get(null);
        tracker(null);
        var profile = new Profile(new AuroraUser(UUID.randomUUID()) {
            @Override public Player getPlayer() { return player; }
        }) {
            @Override public void reload(boolean destroy) {}
        };
        data = new Profile.TaskDataWrapper(profile, "pool", "quest", "task");
        definition = ObjectiveDefinition.builder().id("task").task("BLOCK_BREAK")
                .args(new YamlConfiguration()).filters(List.of()).build();
    }

    @AfterEach
    void restoreManager() throws Exception {
        expansionField.set(null, originalManager);
    }

    @Test
    void missingTrackerSkipsLootAndCustomBreakWithoutException() {
        var drops = new BlockDropItemEvent(block, state, player, List.of());
        assertDoesNotThrow(() -> new BlockLootObjective(null, definition, data).onBlockDrop(drops));
        assertDoesNotThrow(() -> new FarmingObjective(null, definition, data).onBlockDrop(drops));
        var objective = new BreakCounter();
        assertDoesNotThrow(() -> objective.onBlockBreak(new PlayerBreakCustomBlockEvent(player, TypeId.from(Material.STONE), block)));
        assertEquals(0, objective.count);
    }

    @Test
    void enabledTrackerRetainsPlacedVersusNaturalCustomBreakBehavior() throws Exception {
        var objective = new BreakCounter();
        var event = new PlayerBreakCustomBlockEvent(player, TypeId.from(Material.STONE), block);
        tracker(new RegionExpansion() { @Override public boolean isPlacedBlock(Block block) { return true; } });
        objective.onBlockBreak(event);
        assertEquals(0, objective.count);
        tracker(new RegionExpansion() { @Override public boolean isPlacedBlock(Block block) { return false; } });
        objective.onBlockBreak(event);
        assertEquals(1, objective.count);
    }

    @Test
    void questProgressCompletionUnlocksAndRollDataRoundTrip() throws Exception {
        var yaml = new YamlConfiguration();
        yaml.set("rolled.日常.time", 1790700000000L);
        yaml.set("rolled.日常.quests", List.of("采矿/沙砾", "建筑"));
        yaml.set("progression.日常.采矿/沙砾.mine", 12.5D);
        yaml.set("progression.日常.采矿/沙砾.unlocked", true);
        yaml.set("progression.日常.建筑", true);
        yaml.set("pool_unlocks", List.of("日常", "新手"));
        yaml.set("completed_count.日常", 4294967296L);
        var original = new QuestData();
        original.initFrom(yaml);
        var saved = new YamlConfiguration();
        original.serializeInto(saved);
        var reloaded = new YamlConfiguration();
        reloaded.loadFromString(saved.saveToString());
        var restored = new QuestData();
        restored.initFrom(reloaded);
        assertEquals(12.5D, restored.getProgression("日常", "采矿/沙砾", "mine"));
        assertTrue(restored.hasCompletedQuest("日常", "建筑"));
        assertTrue(restored.isQuestStartUnlocked("日常", "采矿/沙砾"));
        assertTrue(restored.isPoolUnlocked("日常"));
        assertTrue(restored.isPoolUnlocked("新手"));
        assertEquals(4294967296L, restored.getCompletedCount("日常"));
        assertEquals(1790700000000L, restored.getPoolRollData("日常").timestamp());
        assertEquals(List.of("采矿/沙砾", "建筑"), restored.getPoolRollData("日常").quests());
    }

    private void tracker(RegionExpansion tracker) throws IllegalAccessException {
        expansionField.set(null, new ExpansionManager() {
            @Override public <T extends AuroraExpansion> T getExpansion(Class<T> type) {
                return type == RegionExpansion.class ? type.cast(tracker) : null;
            }
        });
    }

    private final class BreakCounter extends BlockBreakObjective {
        double count;
        BreakCounter() { super(null, BlockTrackingTest.this.definition, BlockTrackingTest.this.data); }
        @Override public void progress(double amount, ObjectiveMeta meta) { count += amount; }
    }

    private static <T> T proxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> switch (m.getName()) {
            case "getType" -> Material.PUMPKIN;
            case "getLocation" -> new Location(null, 0, 64, 0);
            default -> throw new AssertionError(m);
        }));
    }
}
