package Rin.TRPGCharacter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ドリームランドの常夜と、リソースパック不要の「青紫の異界月」を管理する。
 *
 * 月は各プレイヤー専用のTextDisplayを3層重ねて描画し、
 * プレイヤーの移動に追従して常に遠い空に浮かんで見えるようにする。
 */
public class DreamlandsMoonManager {

    private final Plugin plugin;
    private final DreamlandsManager dreamlandsManager;
    private final Map<UUID, List<TextDisplay>> moons = new HashMap<>();
    private BukkitTask task;

    public DreamlandsMoonManager(Plugin plugin, DreamlandsManager dreamlandsManager) {
        this.plugin = plugin;
        this.dreamlandsManager = dreamlandsManager;
    }

    public void start() {
        shutdown();

        long interval = Math.max(
                5L,
                plugin.getConfig().getLong("dreamlands.moon.update-interval-ticks", 10L)
        );

        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tick,
                1L,
                interval
        );
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (List<TextDisplay> displays : moons.values()) {
            removeDisplays(displays);
        }
        moons.clear();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("dreamlands.moon.enabled", true);
    }

    private void tick() {
        World dream = dreamlandsManager.getDreamlandsWorld();
        if (dream == null) {
            clearAll();
            return;
        }

        // ドリームランドだけを常夜に固定。通常世界の時間には触れない。
        if (plugin.getConfig().getBoolean("dreamlands.always-night", true)) {
            long fixedTime = plugin.getConfig().getLong("dreamlands.night-time", 18000L);
            dream.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
            dream.setTime(Math.floorMod(fixedTime, 24000L));
        }

        dream.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        dream.setStorm(false);
        dream.setThundering(false);

        // ドリームランド外へ出たプレイヤーの月を削除。
        List<UUID> remove = new ArrayList<>();
        for (Map.Entry<UUID, List<TextDisplay>> entry : moons.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline() || !dreamlandsManager.isDreamlands(player.getWorld())
                    || !isEnabled()) {
                removeDisplays(entry.getValue());
                remove.add(entry.getKey());
            }
        }
        for (UUID uuid : remove) {
            moons.remove(uuid);
        }

        if (!isEnabled()) {
            return;
        }

        for (Player player : dream.getPlayers()) {
            if (!player.isOnline() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }

            List<TextDisplay> displays = moons.get(player.getUniqueId());
            if (displays == null || displays.size() != 3 || displays.stream().anyMatch(TextDisplay::isDead)) {
                removeDisplays(displays);
                displays = createMoon(player);
                moons.put(player.getUniqueId(), displays);
            }

            updateMoonPosition(player, displays);
        }
    }

    private List<TextDisplay> createMoon(Player owner) {
        List<TextDisplay> result = new ArrayList<>(3);
        Location base = moonLocation(owner);

        // 外縁の青紫ハロー
        result.add(spawnLayer(owner, base.clone().add(0.0, 0.0, 0.06),
                NamedTextColor.DARK_PURPLE, moonScale() * 1.18f, (byte) 135));

        // 月本体
        result.add(spawnLayer(owner, base.clone(),
                NamedTextColor.LIGHT_PURPLE, moonScale(), (byte) 255));

        // 内側の淡い月明かり
        result.add(spawnLayer(owner, base.clone().add(0.0, 0.0, -0.06),
                NamedTextColor.AQUA, moonScale() * 0.76f, (byte) 190));

        // 各プレイヤーには自分用の月だけを見せる。
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (other.getUniqueId().equals(owner.getUniqueId())) {
                continue;
            }
            for (TextDisplay display : result) {
                other.hideEntity(plugin, display);
            }
        }

        return result;
    }

    private TextDisplay spawnLayer(Player owner,
                                   Location location,
                                   NamedTextColor color,
                                   float scale,
                                   byte opacity) {
        TextDisplay display = owner.getWorld().spawn(location, TextDisplay.class);

        display.text(Component.text("●", color));
        display.setBillboard(Display.Billboard.CENTER);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setDefaultBackground(false);
        display.setBackgroundColor(null);
        display.setShadowed(false);
        display.setSeeThrough(true);
        display.setTextOpacity(opacity);
        display.setViewRange(8.0f);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);

        display.setTransformation(new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        ));

        return display;
    }

    private void updateMoonPosition(Player player, List<TextDisplay> displays) {
        Location base = moonLocation(player);
        if (displays.size() < 3) return;

        displays.get(0).teleport(base.clone().add(0.0, 0.0, 0.06));
        displays.get(1).teleport(base);
        displays.get(2).teleport(base.clone().add(0.0, 0.0, -0.06));
    }

    private Location moonLocation(Player player) {
        double horizontal = Math.max(
                24.0,
                plugin.getConfig().getDouble("dreamlands.moon.horizontal-distance", 42.0)
        );
        double height = plugin.getConfig().getDouble("dreamlands.moon.height-offset", 34.0);

        // 北東の空に固定。プレイヤーの移動分だけ追従するので、
        // 遠景の天体のように同じ方向に留まって見える。
        return player.getLocation().clone().add(horizontal, height, -horizontal * 0.72);
    }

    private float moonScale() {
        double configured = plugin.getConfig().getDouble("dreamlands.moon.scale", 18.0);
        return (float) Math.max(2.0, Math.min(40.0, configured));
    }

    private void clearAll() {
        for (List<TextDisplay> displays : moons.values()) {
            removeDisplays(displays);
        }
        moons.clear();
    }

    private void removeDisplays(List<TextDisplay> displays) {
        if (displays == null) return;
        for (TextDisplay display : displays) {
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
    }
}
