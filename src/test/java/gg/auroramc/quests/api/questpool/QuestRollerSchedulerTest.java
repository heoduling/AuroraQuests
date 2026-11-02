package gg.auroramc.quests.api.questpool;

import gg.auroramc.aurora.api.AuroraLogger;
import gg.auroramc.aurora.api.user.AuroraUser;
import gg.auroramc.quests.AuroraQuests;
import gg.auroramc.quests.api.profile.Profile;
import gg.auroramc.quests.api.profile.ProfileManager;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.*;
import org.quartz.JobBuilder;
import org.quartz.JobExecutionContext;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class QuestRollerSchedulerTest {
    private final List<Runnable> async = new ArrayList<>();
    private final List<Runnable> owners = new ArrayList<>();
    private final Map<Player, Boolean> online = new IdentityHashMap<>();
    private boolean onOwner;
    private Object oldServer, oldPlugin, oldLogger;
    private AuroraQuests plugin;
    private ProfileManager profiles;
    private PoolManager pools;
    private TestPool pool;

    @BeforeEach
    void setup() throws Exception {
        oldServer = get(Bukkit.class, "server");
        oldPlugin = get(AuroraQuests.class, "instance");
        oldLogger = get(AuroraQuests.class, "l");
        var scheduler = proxy(AsyncScheduler.class, (p, m, a) -> {
            if (m.getName().equals("runDelayed")) {
                async.add(() -> ((Consumer<ScheduledTask>) a[1]).accept(null));
                return proxy(ScheduledTask.class, QuestRollerSchedulerTest::empty);
            }
            return empty(p, m, a);
        });
        set(Bukkit.class, "server", proxy(Server.class, (p, m, a) -> switch (m.getName()) {
            case "getAsyncScheduler" -> scheduler;
            case "getLogger" -> Logger.getLogger("RollerTest");
            case "getName" -> "Folia";
            case "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "26.2";
            default -> empty(p, m, a);
        }));
        plugin = allocate(AuroraQuests.class);
        profiles = new ProfileManager();
        pools = allocate(PoolManager.class);
        pool = allocate(TestPool.class);
        set(pool, "test", this);
        set(pools, "registry", new HashMap<>(Map.of("daily", pool)));
        set(plugin, "profileManager", profiles);
        set(plugin, "poolManager", pools);
        set(plugin, "isEnabled", true);
        set(AuroraQuests.class, "instance", plugin);
        set(AuroraQuests.class, "l", new AuroraLogger() {
            @Override public void debug(String message) {}
        });
    }

    @AfterEach
    void restore() throws Exception {
        set(Bukkit.class, "server", oldServer);
        set(AuroraQuests.class, "instance", oldPlugin);
        set(AuroraQuests.class, "l", oldLogger);
    }

    @Test void missingPlayerPoolDoesNotAbortOtherPlayers() throws Exception {
        profile(false);
        var valid = profile(true);
        assertDoesNotThrow(() -> { fire(); drain(async); drain(owners); });
        assertEquals(1, valid.rolled.calls);
    }

    @Test void missingPlayerPoolIsHarmless() throws Exception {
        profile(false);
        assertDoesNotThrow(() -> { fire(); drain(async); drain(owners); });
    }

    @Test void rerollRunsOnPlayerOwner() throws Exception {
        var valid = profile(true);
        fire(); drain(async);
        assertEquals(0, valid.rolled.calls, "Async fan-out must not modify player quests");
        drain(owners);
        assertEquals(1, valid.rolled.calls);
    }

    @Test void playerQuitsAfterFanout() throws Exception {
        var valid = profile(true);
        fire(); drain(async);
        online.put(valid.getPlayer(), false);
        profiles.destroyProfile(valid.getPlayer().getUniqueId());
        drain(owners);
        assertEquals(0, valid.rolled.calls);
    }

    @Test void replacementProfileRejectsQueuedOldProfile() throws Exception {
        var old = profile(true);
        fire(); drain(async);
        map().put(old.getPlayer().getUniqueId(), new TestProfile(old.getPlayer()));
        drain(owners);
        assertEquals(0, old.rolled.calls);
    }

    @Test void removedPoolRejectsQueuedOldJob() throws Exception {
        var valid = profile(true);
        fire();
        set(pools, "registry", Map.of());
        drain(async); drain(owners);
        assertEquals(0, valid.rolled.calls);
    }

    @Test void replacedPoolRejectsAlreadyQueuedPlayerCallback() throws Exception {
        var valid = profile(true);
        fire(); drain(async);
        var replacement = allocate(TestPool.class);
        set(replacement, "test", this);
        set(pools, "registry", Map.of("daily", replacement));
        drain(owners);
        assertEquals(0, valid.rolled.calls);
    }

    @Test void stoppingPluginDoesNotRerollQueuedProfiles() throws Exception {
        var valid = profile(true);
        fire(); drain(async);
        set(plugin, "stopping", true);
        drain(owners);
        assertEquals(0, valid.rolled.calls);
    }

    @Test void jobForAlreadyRemovedPoolIsHarmless() throws Exception {
        set(pools, "registry", Map.of());
        assertDoesNotThrow(() -> { fire(); drain(async); drain(owners); });
    }

    private void fire() {
        var detail = JobBuilder.newJob(QuestRollerScheduler.QuestRollJob.class).usingJobData("poolId", "daily").build();
        new QuestRollerScheduler.QuestRollJob().execute(proxy(JobExecutionContext.class,
                (p, m, a) -> m.getName().equals("getJobDetail") ? detail : empty(p, m, a)));
    }

    private TestProfile profile(boolean hasPool) throws Exception {
        var uuid = UUID.randomUUID();
        var owner = proxy(EntityScheduler.class, (p, m, a) -> {
            if (m.getName().equals("run")) {
                owners.add(() -> {
                    onOwner = true;
                    try { ((Consumer<ScheduledTask>) a[1]).accept(null); }
                    finally { onOwner = false; }
                });
                return proxy(ScheduledTask.class, QuestRollerSchedulerTest::empty);
            }
            return empty(p, m, a);
        });
        var player = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> uuid;
            case "getScheduler" -> owner;
            case "isOnline" -> online.getOrDefault(p, false);
            default -> empty(p, m, a);
        });
        online.put(player, true);
        var profile = new TestProfile(player);
        profile.rolled = allocate(TestQuestPool.class);
        profile.rolled.test = this;
        profile.hasPool = hasPool;
        map().put(uuid, profile);
        return profile;
    }

    private Map<UUID, Profile> map() throws Exception { return (Map<UUID, Profile>) get(profiles, "profileMap"); }
    private static void drain(List<Runnable> queue) { var copy = new ArrayList<>(queue); queue.clear(); copy.forEach(Runnable::run); }
    private static class TestProfile extends Profile {
        TestQuestPool rolled;
        boolean hasPool;
        TestProfile(Player player) { super(new AuroraUser(player.getUniqueId()) { @Override public Player getPlayer() { return player; } }); }
        @Override public void reload(boolean destroy) {}
        @Override public void destroy() { hasPool = false; }
        @Override public QuestPool getQuestPool(String id) { return hasPool ? rolled : null; }
    }
    private static class TestQuestPool extends QuestPool {
        QuestRollerSchedulerTest test;
        int calls;
        TestQuestPool() { super(null, null); }
        @Override public void reRollQuests(boolean notify) { assertTrue(test.onOwner, "Player quest mutation must own the player"); calls++; }
    }
    private static class TestPool extends Pool {
        QuestRollerSchedulerTest test;
        TestPool() { super(null, null); }
        @Override public String getId() { return "daily"; }
        @Override public QuestRollerScheduler getQuestRoller() {
            try { return allocate(TestRoller.class); } catch (Exception e) { throw new AssertionError(e); }
        }
    }
    private static class TestRoller extends QuestRollerScheduler {
        TestRoller() { super(null); }
        @Override public void resetNextExecutionTime() {}
    }
    private static Object empty(Object p, Method m, Object[] a) {
        if (m.getName().equals("hashCode")) return System.identityHashCode(p);
        if (m.getName().equals("equals")) return p == a[0];
        if (m.getName().equals("toString")) return "test-double";
        if (m.getReturnType() == boolean.class) return false;
        if (m.getReturnType().isPrimitive()) return 0;
        return null;
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) { return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler)); }
    private static Field field(Object obj, String name) throws Exception {
        for (Class<?> c = obj instanceof Class<?> type ? type : obj.getClass(); c != null; c = c.getSuperclass()) {
            try { var f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object obj, String name) throws Exception { return field(obj, name).get(obj instanceof Class<?> ? null : obj); }
    private static void set(Object obj, String name, Object value) throws Exception { field(obj, name).set(obj instanceof Class<?> ? null : obj, value); }
    private static <T> T allocate(Class<T> type) throws Exception {
        var f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true);
        return type.cast(((sun.misc.Unsafe) f.get(null)).allocateInstance(type));
    }
}
