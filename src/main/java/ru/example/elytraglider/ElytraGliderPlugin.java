package ru.example.elytraglider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class ElytraGliderPlugin extends JavaPlugin implements Listener {

  private static final String DISABLED_LORE_LINE = "Элитры отключены, экипируйте вне энда.";

  private Method asNmsCopyMethod;
  private Method asBukkitCopyMethod;
  private Method getComponentsMethod;
  private Method removeComponentMethod;
  private Method setComponentMethod;
  private Method getMethod;
  private Object gliderComponentType;
  private Object gliderComponentValue;
  private Class<?> componentTypeClass;

  private final Set<UUID> playersInEnd = new HashSet<>();
  private final Set<UUID> processedInCycle = new HashSet<>();
  private BukkitTask periodicTask;
  
  private static final int CHECK_INTERVAL = 100;

  @Override
  public void onEnable() {
    try {
      initializeReflection();
    } catch (Exception e) {
      getLogger().severe("Failed to initialize reflection methods: " + e.getMessage());
      e.printStackTrace();
      getServer().getPluginManager().disablePlugin(this);
      return;
    }

    getServer().getPluginManager().registerEvents(this, this);
    startPeriodicCheck();
    getLogger().info("ElytraGliderPlugin enabled");
  }

  @Override
  public void onDisable() {
    if (periodicTask != null && !periodicTask.isCancelled()) {
      periodicTask.cancel();
    }
    getLogger().info("ElytraGliderPlugin disabled");
  }

  private void initializeReflection() throws Exception {
    Class<?> craftItemStackClass = Class.forName(
        "org.bukkit.craftbukkit.inventory.CraftItemStack");
    asNmsCopyMethod = craftItemStackClass.getMethod("asNMSCopy", ItemStack.class);

    ItemStack dummyElytra = new ItemStack(Material.ELYTRA);
    Object dummyNmsItem = asNmsCopyMethod.invoke(null, dummyElytra);
    Class<?> nmsItemStackClass = dummyNmsItem.getClass();

    asBukkitCopyMethod = craftItemStackClass.getMethod("asBukkitCopy", nmsItemStackClass);
    getComponentsMethod = nmsItemStackClass.getMethod("getComponents");

    Object dummyComponents = getComponentsMethod.invoke(dummyNmsItem);
    Class<?> dataComponentMapClass = dummyComponents.getClass();

    componentTypeClass = null;
    for (Method method : dataComponentMapClass.getMethods()) {
      if (method.getName().equals("remove") && method.getParameterCount() == 1) {
        componentTypeClass = method.getParameterTypes()[0];
        break;
      }
    }

    if (componentTypeClass == null) {
      throw new RuntimeException("Could not find ComponentType class");
    }

    ClassLoader cl = componentTypeClass.getClassLoader();
    Class<?> dataComponentsClass = Class.forName(
        "net.minecraft.core.component.DataComponents", false, cl);
    gliderComponentType = dataComponentsClass.getField("GLIDER").get(null);

    removeComponentMethod = dataComponentMapClass.getMethod("remove", componentTypeClass);
    setComponentMethod = dataComponentMapClass.getMethod("set", componentTypeClass, Object.class);
    getMethod = dataComponentMapClass.getMethod("get", componentTypeClass);

    try {
      ItemStack freshElytra = new ItemStack(Material.ELYTRA);
      Object freshNmsItem = asNmsCopyMethod.invoke(null, freshElytra);
      Object freshComponents = getComponentsMethod.invoke(freshNmsItem);
      
      gliderComponentValue = getMethod.invoke(freshComponents, gliderComponentType);
      
      if (gliderComponentValue == null) {
        try {
          Method defaultValueMethod = componentTypeClass.getMethod("defaultValue");
          gliderComponentValue = defaultValueMethod.invoke(gliderComponentType);
        } catch (Exception e) {
          for (String methodName : new String[]{"getDefaultValue", "default", "getDefault"}) {
            try {
              Method m = componentTypeClass.getMethod(methodName);
              gliderComponentValue = m.invoke(gliderComponentType);
              break;
            } catch (Exception ignored) {
            }
          }
        }
      }
      
      if (gliderComponentValue == null) {
        getLogger().warning("Could not get glider component value");
      }
    } catch (Exception e) {
      getLogger().warning("Could not get glider component value: " + e.getMessage());
      gliderComponentValue = null;
    }
  }

  private void startPeriodicCheck() {
    periodicTask = getServer().getScheduler().runTaskTimer(this, () -> {
      processedInCycle.clear();
      
      for (Player player : getServer().getOnlinePlayers()) {
        if (player.getWorld().getEnvironment() == World.Environment.THE_END) {
          ItemStack chestplate = player.getInventory().getChestplate();
          if (chestplate != null && chestplate.getType() == Material.ELYTRA) {
            UUID playerId = player.getUniqueId();
            if (!processedInCycle.contains(playerId)) {
              updateElytraGlider(chestplate, player.getWorld(), player, false);
              processedInCycle.add(playerId);
            }
          }
        }
      }
    }, CHECK_INTERVAL, CHECK_INTERVAL);
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
    Player player = event.getPlayer();
    World newWorld = player.getWorld();
    
    updatePlayerTracking(player, newWorld);

    getServer().getScheduler().runTask(this, () -> {
      ItemStack chestplate = player.getInventory().getChestplate();
      if (chestplate != null && chestplate.getType() == Material.ELYTRA) {
        updateElytraGlider(chestplate, newWorld, player, true);
      }
    });
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    World world = player.getWorld();
    
    updatePlayerTracking(player, world);
    
    getServer().getScheduler().runTask(this, () -> {
      ItemStack chestplate = player.getInventory().getChestplate();
      if (chestplate != null && chestplate.getType() == Material.ELYTRA) {
        updateElytraGlider(chestplate, world, player, true);
      }
    });
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerQuit(PlayerQuitEvent event) {
    playersInEnd.remove(event.getPlayer().getUniqueId());
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onPlayerRespawn(PlayerRespawnEvent event) {
    Player player = event.getPlayer();
    World world = player.getWorld();
    
    updatePlayerTracking(player, world);
    
    getServer().getScheduler().runTask(this, () -> {
      ItemStack chestplate = player.getInventory().getChestplate();
      if (chestplate != null && chestplate.getType() == Material.ELYTRA) {
        updateElytraGlider(chestplate, world, player, true);
      }
    });
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  public void onInventoryClick(InventoryClickEvent event) {
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }

    int slot = event.getSlot();
    boolean isChestplateSlot = slot == 38;
    
    if (event.getSlotType() != null) {
      try {
        if (event.getSlotType().name().equals("ARMOR") || 
            (slot >= 36 && slot <= 39)) {
          isChestplateSlot = true;
        }
      } catch (Exception ignored) {
      }
    }

    if (isChestplateSlot) {
      ItemStack clickedItem = event.getCurrentItem();
      ItemStack cursorItem = event.getCursor();
      
      boolean isElytra = (clickedItem != null && clickedItem.getType() == Material.ELYTRA) ||
                         (cursorItem != null && cursorItem.getType() == Material.ELYTRA);

      if (isElytra) {
        getServer().getScheduler().runTask(this, () -> {
          ItemStack chestplate = player.getInventory().getChestplate();
          if (chestplate != null && chestplate.getType() == Material.ELYTRA) {
            updateElytraGlider(chestplate, player.getWorld(), player, true);
          }
        });
      }
    }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  public void onInventoryClose(InventoryCloseEvent event) {
    if (!(event.getPlayer() instanceof Player player)) {
      return;
    }

    getServer().getScheduler().runTask(this, () -> {
      ItemStack chestplate = player.getInventory().getChestplate();
      if (chestplate != null && chestplate.getType() == Material.ELYTRA) {
        updateElytraGlider(chestplate, player.getWorld(), player, true);
      }
    });
  }

  private void updatePlayerTracking(Player player, World world) {
    UUID playerId = player.getUniqueId();
    boolean isInEnd = world.getEnvironment() == World.Environment.THE_END;
    
    if (isInEnd) {
      playersInEnd.add(playerId);
    } else {
      playersInEnd.remove(playerId);
    }
  }

  private void updateElytraGlider(ItemStack elytra, World world, Player player, boolean forceUpdate) {
    if (elytra == null || elytra.getType() != Material.ELYTRA) {
      return;
    }

    boolean isEnd = world.getEnvironment() == World.Environment.THE_END;

    try {
      Object nmsItem = asNmsCopyMethod.invoke(null, elytra);
      if (nmsItem == null) {
        return;
      }

      Object components = getComponentsMethod.invoke(nmsItem);
      
      Object currentGlider = getMethod.invoke(components, gliderComponentType);
      boolean hasGlider = currentGlider != null;

      if (isEnd) {
        if (hasGlider || forceUpdate) {
          removeComponentMethod.invoke(components, gliderComponentType);
          
          ItemStack updatedElytra = (ItemStack) asBukkitCopyMethod.invoke(null, nmsItem);
          addDisabledLore(updatedElytra);
          player.getInventory().setChestplate(updatedElytra);
        }
      } else {
        if (!hasGlider && gliderComponentValue != null) {
          setComponentMethod.invoke(components, gliderComponentType, gliderComponentValue);
          
          ItemStack updatedElytra = (ItemStack) asBukkitCopyMethod.invoke(null, nmsItem);
          removeDisabledLore(updatedElytra);
          player.getInventory().setChestplate(updatedElytra);
        }
      }
    } catch (Exception e) {
      getLogger().warning("Failed to update elytra for " + player.getName() + ": " + e.getMessage());
      if (getLogger().isLoggable(java.util.logging.Level.FINE)) {
        e.printStackTrace();
      }
    }
  }

  private void addDisabledLore(ItemStack elytra) {
    ItemMeta meta = elytra.getItemMeta();
    if (meta == null) {
      return;
    }
    List<String> lore = meta.getLore();
    if (lore == null) {
      lore = new ArrayList<>();
    } else {
      lore = new ArrayList<>(lore);
    }

    if (!lore.contains(DISABLED_LORE_LINE)) {
      lore.add(DISABLED_LORE_LINE);
      meta.setLore(lore);
      elytra.setItemMeta(meta);
    }
  }

  private void removeDisabledLore(ItemStack elytra) {
    ItemMeta meta = elytra.getItemMeta();
    if (meta == null) {
      return;
    }
    List<String> lore = meta.getLore();
    if (lore == null || lore.isEmpty()) {
      return;
    }

    List<String> newLore = new ArrayList<>(lore);
    if (newLore.remove(DISABLED_LORE_LINE)) {
      if (newLore.isEmpty()) {
        meta.setLore(null);
      } else {
        meta.setLore(newLore);
      }
      elytra.setItemMeta(meta);
    }
  }
}
