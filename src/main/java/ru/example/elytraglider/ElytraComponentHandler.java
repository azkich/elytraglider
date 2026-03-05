package ru.example.elytraglider;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Method;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public class ElytraComponentHandler {

  private static final String DISABLED_LORE_LINE = "Элитры отключены, экипируйте вне энда.";

  private final JavaPlugin plugin;
  private Method asNmsCopyMethod;
  private Method asBukkitCopyMethod;
  private Method getComponentsMethod;
  private Method removeComponentMethod;
  private Method setComponentMethod;
  private Method getMethod;
  private Object gliderComponentType;
  private Object gliderComponentValue;
  private Class<?> componentTypeClass;

  public ElytraComponentHandler(JavaPlugin plugin) throws Exception {
    this.plugin = plugin;
    initializeReflection();
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
        plugin.getLogger().warning("Could not get glider component value");
      }
    } catch (Exception e) {
      plugin.getLogger().warning("Could not get glider component value: " + e.getMessage());
      gliderComponentValue = null;
    }
  }

  public void updateElytraGlider(ItemStack elytra, Player player, boolean isEnd) {
    if (elytra == null || elytra.getType() != Material.ELYTRA) {
      return;
    }

    try {
      Object nmsItem = asNmsCopyMethod.invoke(null, elytra);
      if (nmsItem == null) {
        return;
      }

      Object components = getComponentsMethod.invoke(nmsItem);
      
      Object currentGlider = getMethod.invoke(components, gliderComponentType);
      boolean hasGlider = currentGlider != null;

      if (isEnd) {
        // Если в Энде и есть компонент glider - снимаем его
        if (hasGlider) {
          removeComponentMethod.invoke(components, gliderComponentType);
          
          ItemStack updatedElytra = (ItemStack) asBukkitCopyMethod.invoke(null, nmsItem);
          addDisabledLore(updatedElytra);
          player.getInventory().setChestplate(updatedElytra);
        }
      } else {
        // Если не в Энде и нет компонента glider - накладываем его
        // Пропускаем обработку, если компонент уже есть
        if (!hasGlider && gliderComponentValue != null) {
          setComponentMethod.invoke(components, gliderComponentType, gliderComponentValue);
          
          ItemStack updatedElytra = (ItemStack) asBukkitCopyMethod.invoke(null, nmsItem);
          removeDisabledLore(updatedElytra);
          player.getInventory().setChestplate(updatedElytra);
        }
      }
    } catch (Exception e) {
      plugin.getLogger().warning("Failed to update elytra for " + player.getName() + ": " + e.getMessage());
      if (plugin.getLogger().isLoggable(java.util.logging.Level.FINE)) {
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
