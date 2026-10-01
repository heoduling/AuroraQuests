package gg.auroramc.quests.objective;

import gg.auroramc.aurora.Aurora;
import gg.auroramc.aurora.api.expansions.AuroraExpansion;
import gg.auroramc.aurora.api.expansions.ExpansionManager;
import gg.auroramc.aurora.api.item.ItemManager;
import gg.auroramc.aurora.api.user.AuroraUser;
import gg.auroramc.aurora.expansions.item.ItemExpansion;
import gg.auroramc.quests.api.data.QuestData;
import gg.auroramc.quests.api.event.objective.PlayerTakeItemEvent;
import gg.auroramc.quests.api.objective.ObjectiveDefinition;
import gg.auroramc.quests.api.profile.Profile;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TakeItemObjectiveTest {
    private Field serverField, expansionField;
    private Object oldServer, oldExpansions;
    private ItemStack[] contents;
    private Player player;

    @BeforeEach
    void setup() throws Exception {
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        oldServer = serverField.get(null);
        serverField.set(null, proxy(Server.class, (p, method, args) -> switch (method.getName()) {
            case "isPrimaryThread" -> true;
            case "getName" -> "Folia";
            case "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "26.2";
            default -> throw new AssertionError(method);
        }));
        var inventory = proxy(PlayerInventory.class, (p, method, args) -> {
            if (method.getName().equals("getContents")) return contents;
            throw new AssertionError(method);
        });
        player = proxy(Player.class, (p, method, args) -> {
            if (method.getName().equals("getInventory")) return inventory;
            throw new AssertionError(method);
        });
        var items = new ItemManager();
        var expansion = new ItemExpansion() {
            @Override public ItemManager getItemManager() { return items; }
        };
        expansionField = Aurora.class.getDeclaredField("expansionManager");
        expansionField.setAccessible(true);
        oldExpansions = expansionField.get(null);
        expansionField.set(null, new ExpansionManager() {
            @Override public <T extends AuroraExpansion> T getExpansion(Class<T> type) {
                return type == ItemExpansion.class ? type.cast(expansion) : null;
            }
        });
    }

    @AfterEach
    void restore() throws Exception {
        if (expansionField != null) expansionField.set(null, oldExpansions);
        if (serverField != null) serverField.set(null, oldServer);
    }

    @Test
    void wrongPotionDoesNotConsumeItemsOrAdvance() {
        var poison = arrow(PotionType.POISON, 4);
        var task = task("healing", 1, Material.TIPPED_ARROW, poison);
        submit(task);
        assertEquals(4, poison.getAmount());
        assertEquals(0, task.getProgress());
    }

    @Test
    void consumesOnlyRequiredAmountOfCorrectPotion() {
        var healing = arrow(PotionType.HEALING, 5);
        var task = task("healing", 2, Material.TIPPED_ARROW, healing);
        submit(task);
        assertEquals(3, healing.getAmount());
        assertEquals(2, task.getProgress());
    }

    @Test
    void mixedInventorySkipsWrongArrowsAndUsesMultipleCorrectStacks() {
        var wrong = arrow(PotionType.POISON, 8);
        var first = arrow(PotionType.HEALING, 1);
        var last = arrow(PotionType.HEALING, 4);
        var task = task("healing", 3, Material.TIPPED_ARROW, wrong, first, null, last);
        submit(task);
        assertEquals(8, wrong.getAmount());
        assertEquals(0, first.getAmount());
        assertEquals(2, last.getAmount());
        assertEquals(3, task.getProgress());
    }

    @Test
    void partialSubmissionContinuesWithoutConsumingWrongPotion() {
        var wrong = arrow(PotionType.POISON, 6);
        var first = arrow(PotionType.HEALING, 1);
        var task = task("healing", 3, Material.TIPPED_ARROW, wrong, first);
        submit(task);
        assertEquals(1, task.getProgress());
        var next = arrow(PotionType.HEALING, 2);
        contents = new ItemStack[]{wrong, next};
        submit(task);
        assertEquals(3, task.getProgress());
        assertEquals(0, next.getAmount());
        assertEquals(6, wrong.getAmount());
        submit(task);
        assertEquals(6, wrong.getAmount());
    }

    @Test
    void unspecifiedPotionPreservesGenericArrowSubmissionWithoutReadingMeta() {
        var poison = arrow(PotionType.POISON, 3);
        var task = task(null, 2, Material.TIPPED_ARROW, poison);
        submit(task);
        assertEquals(1, poison.getAmount());
        assertEquals(2, task.getProgress());
        assertEquals(0, poison.metaReads);
    }

    @Test
    void ordinaryItemSubmissionIsUnchanged() {
        var stone = new TestStack(Material.STONE, null, 4);
        var task = task(null, 3, Material.STONE, stone);
        submit(task);
        assertEquals(1, stone.getAmount());
        assertEquals(3, task.getProgress());
        assertEquals(0, stone.metaReads);
    }

    @Test
    void potionConstraintRejectsMissingPotionDataAndNonPotionItems() {
        for (var material : List.of(Material.TIPPED_ARROW, Material.ARROW, Material.STONE)) {
            var item = new TestStack(material, null, 2);
            var task = task("healing", 1, material, item);
            submit(task);
            assertEquals(2, item.getAmount(), material.name());
            assertEquals(0, task.getProgress(), material.name());
        }
    }

    @Test
    void blankInvalidAndForeignPotionNamesFailClosed() {
        for (var value : List.of("", " ", "not_a_potion", "other:healing")) {
            var item = arrow(PotionType.HEALING, 2);
            var task = task(value, 1, Material.TIPPED_ARROW, item);
            submit(task);
            assertEquals(2, item.getAmount(), value);
            assertEquals(0, task.getProgress(), value);
        }
    }

    @Test
    void acceptsNamespacedCaseInsensitivePotionKeys() {
        var item = arrow(PotionType.HEALING, 2);
        var task = task(" MINECRAFT:HEALING ", 1, Material.TIPPED_ARROW, item);
        submit(task);
        assertEquals(1, item.getAmount());
        assertEquals(1, task.getProgress());
    }

    @Test
    void suppliedConfigNamesMapToInfestedAndWindChargedPotions() {
        var names = java.util.Map.of("infestation", PotionType.INFESTED, "wind_charging", PotionType.WIND_CHARGED);
        names.forEach((name, type) -> {
            for (var value : List.of(name, "minecraft:" + name)) {
                var item = arrow(type, 2);
                var task = task(value, 1, Material.TIPPED_ARROW, item);
                submit(task);
                assertEquals(1, item.getAmount(), value);
                assertEquals(1, task.getProgress(), value);
            }
        });
    }

    @Test
    void potionKeysAcceptTheirExactVariantIncludingLongStrongAndWindCharging() {
        for (var type : PotionType.values()) {
            var item = arrow(type, 2);
            var task = task(type.getKey().getKey(), 1, Material.TIPPED_ARROW, item);
            submit(task);
            assertEquals(1, item.getAmount(), type.name());
            assertEquals(1, task.getProgress(), type.name());
        }
        var strong = arrow(PotionType.STRONG_HEALING, 2);
        var healing = task("healing", 1, Material.TIPPED_ARROW, strong);
        submit(healing);
        assertEquals(2, strong.getAmount());
        assertEquals(0, healing.getProgress());
        var extended = arrow(PotionType.LONG_POISON, 2);
        var poison = task("poison", 1, Material.TIPPED_ARROW, extended);
        submit(poison);
        assertEquals(2, extended.getAmount());
        assertEquals(0, poison.getProgress());
    }

    private TakeItemObjective task(String potion, int amount, Material material, ItemStack... inventory) {
        contents = inventory;
        var progress = new QuestData();
        var profile = new Profile(new AuroraUser(UUID.randomUUID()) {
            @Override public Player getPlayer() { return player; }
        }) {
            @Override public void reload(boolean destroy) { }
            @Override public QuestData getData() { return progress; }
        };
        var args = new YamlConfiguration();
        args.set("item", "minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT));
        args.set("amount", amount);
        if (potion != null) args.set("potion-type", potion);
        var definition = ObjectiveDefinition.builder().id("counter").task("TAKE_ITEM")
                .args(args).filters(List.of()).build();
        return new TakeItemObjective(null, definition, profile.toTaskDataWrapper("pool", "quest", "counter"));
    }

    private void submit(TakeItemObjective task) { task.handle(new PlayerTakeItemEvent(player, null)); }

    private static TestStack arrow(PotionType type, int amount) { return new TestStack(Material.TIPPED_ARROW, type, amount); }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class TestStack extends ItemStack {
        private final Material material;
        private final ItemMeta meta;
        private int amount, metaReads;
        TestStack(Material material, PotionType potion, int amount) {
            this.material = material;
            this.amount = amount;
            this.meta = potion == null ? null : proxy(PotionMeta.class, (p, method, args) -> {
                if (method.getName().equals("getBasePotionType")) return potion;
                throw new AssertionError(method);
            });
        }
        @Override public Material getType() { return material; }
        @Override public int getAmount() { return amount; }
        @Override public void setAmount(int amount) { this.amount = amount; }
        @Override public ItemMeta getItemMeta() { metaReads++; return meta; }
    }
}
