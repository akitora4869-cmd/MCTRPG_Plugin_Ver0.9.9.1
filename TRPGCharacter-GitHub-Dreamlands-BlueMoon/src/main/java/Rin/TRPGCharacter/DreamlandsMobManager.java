package Rin.TRPGCharacter;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ドリームランド専用の自然スポーン制御。
 *
 * - ドリームランドでは通常Mobの自然発生を遮断。
 * - 代わりに登録済みのドリームランド神話生物6種を抽選して自然発生。
 * - KP/管理者によるCOMMAND/CUSTOM/SPAWNER_EGG召喚は妨げない。
 */
public class DreamlandsMobManager implements Listener {

    private static final List<String> DREAMLAND_IDS = List.of(
            "nightgaunt",
            "gug",
            "moon_beast",
            "zoog",
            "spider_of_leng",
            "shantak"
    );

    private final Plugin plugin;
    private final DreamlandsManager dreamlandsManager;
    private final MythosManager mythosManager;
    private final CharacterManager characterManager;
    private final Random random = new Random();

    private BukkitTask task;

    public DreamlandsMobManager(Plugin plugin,
                                DreamlandsManager dreamlandsManager,
                                MythosManager mythosManager,
                                CharacterManager characterManager) {
        this.plugin = plugin;
        this.dreamlandsManager = dreamlandsManager;
        this.mythosManager = mythosManager;
        this.characterManager = characterManager;
    }

    public void start() {
        shutdown();

        long interval = Math.max(
                20L,
                plugin.getConfig().getLong("dreamlands.mobs.check-interval-ticks", 100L)
        );

        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tickNaturalSpawns,
                interval,
                interval
        );
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("dreamlands.mobs.enabled", true);
    }

    public double getSpawnRatePercent() {
        return clamp(plugin.getConfig().getDouble("dreamlands.mobs.spawn-rate-percent", 35.0));
    }

    public void setEnabled(boolean enabled) {
        plugin.getConfig().set("dreamlands.mobs.enabled", enabled);
        plugin.saveConfig();
    }

    public void setSpawnRatePercent(double percent) {
        plugin.getConfig().set("dreamlands.mobs.spawn-rate-percent", clamp(percent));
        plugin.saveConfig();
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!dreamlandsManager.isDreamlands(event.getLocation().getWorld())) {
            return;
        }

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();

        // KPコマンド、プラグイン召喚、スポーンエッグは手動演出用として許可。
        if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM
                || reason == CreatureSpawnEvent.SpawnReason.COMMAND
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            return;
        }

        // それ以外は通常Mobの自然発生・スポナー・増援等を含めて遮断。
        event.setCancelled(true);
    }

    private void tickNaturalSpawns() {
        if (!isEnabled() || !dreamlandsManager.isEnabled()) {
            return;
        }
        if (plugin.getTimeStopManager() != null
                && plugin.getTimeStopManager().isStopped()) {
            return;
        }

        World world = dreamlandsManager.getDreamlandsWorld();
        if (world == null) {
            return;
        }

        int maxNear = Math.max(
                1,
                plugin.getConfig().getInt("dreamlands.mobs.max-near-player", 6)
        );
        double minDistance = Math.max(
                8.0,
                plugin.getConfig().getDouble("dreamlands.mobs.min-spawn-distance", 18.0)
        );
        double maxDistance = Math.max(
                minDistance + 4.0,
                plugin.getConfig().getDouble("dreamlands.mobs.max-spawn-distance", 34.0)
        );

        List<Player> players = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (player.isOnline()
                    && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && !characterManager.isDeadCharacter(player)) {
                players.add(player);
            }
        }

        for (Player player : players) {
            if (random.nextDouble() * 100.0 >= getSpawnRatePercent()) {
                continue;
            }

            int nearby = 0;
            for (org.bukkit.entity.Entity entity :
                    player.getNearbyEntities(maxDistance, maxDistance, maxDistance)) {
                if (mythosManager.getDefinition(entity) != null) {
                    nearby++;
                }
            }
            if (nearby >= maxNear) {
                continue;
            }

            Location spawn = findSpawnLocation(player, minDistance, maxDistance);
            if (spawn == null) {
                continue;
            }

            String id = pickCreatureId(spawn);
            mythosManager.summonAt(spawn, id, true);
        }
    }

    private String pickCreatureId(Location location) {
        int y = location.getBlockY();
        int light = location.getBlock().getLightLevel();

        // 高所は飛行生物を少し多めに。
        if (y >= 90 && random.nextDouble() < 0.55) {
            return random.nextBoolean() ? "nightgaunt" : "shantak";
        }

        // 暗い低地・裂け目はガグやレンの蜘蛛が出やすい。
        if (light <= 4 && random.nextDouble() < 0.45) {
            int r = random.nextInt(3);
            return r == 0 ? "gug" : (r == 1 ? "spider_of_leng" : "nightgaunt");
        }

        // 通常抽選。ズーグはやや出やすく、ムーンビーストはやや希少。
        int roll = random.nextInt(100);
        if (roll < 28) return "zoog";
        if (roll < 48) return "nightgaunt";
        if (roll < 66) return "spider_of_leng";
        if (roll < 80) return "gug";
        if (roll < 92) return "shantak";
        return "moon_beast";
    }

    private Location findSpawnLocation(Player player,
                                       double minDistance,
                                       double maxDistance) {
        World world = player.getWorld();

        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = minDistance
                    + random.nextDouble() * (maxDistance - minDistance);

            int x = (int) Math.floor(player.getLocation().getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getLocation().getZ() + Math.sin(angle) * distance);

            int y = world.getHighestBlockYAt(x, z) + 1;
            if (y <= world.getMinHeight() + 2 || y >= world.getMaxHeight() - 3) {
                continue;
            }

            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            Block ground = world.getBlockAt(x, y - 1, z);

            if (!feet.isPassable() || !head.isPassable()) {
                continue;
            }
            if (!ground.getType().isSolid()) {
                continue;
            }
            if (ground.getType() == Material.BEDROCK) {
                continue;
            }

            return new Location(world, x + 0.5, y, z + 0.5);
        }

        return null;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }
}
