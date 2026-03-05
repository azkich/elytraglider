package ru.example.elytraglider;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class ElytraListener implements Listener {

  public final ConcurrentHashMap<UUID, Long> pendingJoins = new ConcurrentHashMap<>();
  private final JavaPlugin plugin;
  private final ElytraComponentHandler componentHandler;

  public ElytraListener(JavaPlugin plugin, ElytraComponentHandler componentHandler) {
    this.plugin = plugin;
    this.componentHandler = componentHandler;
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onJoin(PlayerJoinEvent event) {
    pendingJoins.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onQuit(PlayerQuitEvent event) {
    pendingJoins.remove(event.getPlayer().getUniqueId());
  }

  // Используем PlayerArmorChangeEvent для отслеживания наложения/снятия элитр,
  // без работы с инвентарём напрямую
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onArmorChange(PlayerArmorChangeEvent event) {
    if (event.getSlotType() != PlayerArmorChangeEvent.SlotType.CHEST) {
      return;
    }

    Player player = event.getPlayer();
    if (player == null || !player.isOnline()) {
      return;
    }

    ItemStack newItem = event.getNewItem();
    if (newItem == null || newItem.getType() != Material.ELYTRA) {
      // Новый предмет не элитры — нам ничего делать не нужно
      return;
    }

    // Выполняем обработку в следующем тике, чтобы экипировка точно обновилась
    plugin.getServer().getScheduler().runTask(plugin, () -> updateElytra(player));
  }

  public void updateElytra(Player player) {
    if (player == null || !player.isOnline()) {
      return;
    }

    ItemStack chest = player.getInventory().getChestplate();
    if (chest == null || chest.getType() != Material.ELYTRA) {
      return;
    }

    boolean inEnd = player.getWorld().getEnvironment() == World.Environment.THE_END;
    componentHandler.updateElytraGlider(chest, player, inEnd);
  }
}
