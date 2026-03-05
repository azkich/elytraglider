package ru.example.elytraglider;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class ElytraGliderPlugin extends JavaPlugin {

  private ElytraComponentHandler componentHandler;
  private ElytraListener listener;

  @Override
  public void onEnable() {
    try {
      componentHandler = new ElytraComponentHandler(this);
    } catch (Exception e) {
      getLogger().severe("Failed to initialize component handler: " + e.getMessage());
      e.printStackTrace();
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    listener = new ElytraListener(this, componentHandler);
    getServer().getPluginManager().registerEvents(listener, this);

    // Глобальный таймер — каждые 5 секунд (100 тиков)
    // Проверяет игроков, которые зашли >= 10 секунд назад
    new BukkitRunnable() {
      @Override
      public void run() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> it = listener.pendingJoins.entrySet().iterator();

        while (it.hasNext()) {
          Map.Entry<UUID, Long> entry = it.next();
          if (now - entry.getValue() >= 10000L) { // 10 секунд = 10000 мс
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
              listener.updateElytra(player);
            }
            it.remove();
          }
        }
      }
    }.runTaskTimer(this, 100L, 100L); // старт через 5 сек, повтор каждые 5 секунд

    getLogger().info("ElytraGliderPlugin enabled");
  }

  @Override
  public void onDisable() {
    getLogger().info("ElytraGliderPlugin disabled");
  }
}
