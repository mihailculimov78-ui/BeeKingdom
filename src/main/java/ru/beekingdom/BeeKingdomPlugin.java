package ru.beekingdom;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.attribute.Attribute;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.concurrent.ThreadLocalRandom;

public final class BeeKingdomPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private enum Mode {
        FOLLOW("Следовать за игроком"), STAY("Свободный полёт"), TARGET("Атака выбранной цели"),
        PEACEFUL("Мирный режим"), BERSERK("Защита территории");
        final String title;
        Mode(String title) { this.title = title; }
    }

    private static final class BeeProfile {
        UUID owner;
        Mode mode = Mode.FOLLOW;
        String targetName = "";
        String armor = "";
        String world = "";
        int chunkX;
        int chunkZ;
        boolean hasLocation;
        boolean fattened;
        int sugarFed;
        boolean saddled;
        String potionEffect = "";
        BeeProfile(UUID owner) { this.owner = owner; }
    }

    private static final class MenuHolder implements InventoryHolder {
        final boolean detail;
        final int page;
        final UUID beeId;
        final List<UUID> entries = new ArrayList<>();
        Inventory inventory;
        MenuHolder(boolean detail, int page, UUID beeId) { this.detail = detail; this.page = page; this.beeId = beeId; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final Map<UUID, BeeProfile> profiles = new HashMap<>();
    private final Map<UUID, Bee> loadedBees = new HashMap<>();
    // Tombstones let the plugin release bees even when their chunks are currently unloaded.
    private final Set<UUID> releasedBees = new HashSet<>();
    private final Map<UUID, UUID> pendingBeeTarget = new HashMap<>();
    private final Set<UUID> waitingForTarget = new HashSet<>();
    private final Set<UUID> waitingForArmyTarget = new HashSet<>();
    private final Map<UUID, Vector> freeFlightDirections = new HashMap<>();
    private final Map<UUID, Integer> freeFlightRefresh = new HashMap<>();
    private final Map<UUID, LivingEntity> ridingAttackTargets = new HashMap<>();

    private NamespacedKey ownerKey, modeKey, targetKey, armorKey, collarKey, displayKey, fattenedKey, saddledKey, potionEffectKey, sugarFedKey;

    @Override public void onEnable() {
        ownerKey = new NamespacedKey(this, "owner");
        modeKey = new NamespacedKey(this, "mode");
        targetKey = new NamespacedKey(this, "target");
        armorKey = new NamespacedKey(this, "armor");
        collarKey = new NamespacedKey(this, "bee_collar");
        displayKey = new NamespacedKey(this, "armor_display");
        fattenedKey = new NamespacedKey(this, "fattened");
        saddledKey = new NamespacedKey(this, "saddled");
        potionEffectKey = new NamespacedKey(this, "potion_effect");
        sugarFedKey = new NamespacedKey(this, "sugar_fed");
        saveDefaultConfig();
        loadProfiles();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("beekdom") != null) getCommand("beekdom").setExecutor(this);
        registerCollarRecipe();
        for (World world : Bukkit.getWorlds()) for (Entity entity : world.getEntities()) if (entity instanceof Bee bee) registerLoadedBee(bee);
        cleanupAlreadyLoadedChunks();
        Bukkit.getScheduler().runTaskTimer(this, this::tickBees, 1L, 2L);
        getLogger().info("BeeKingdom включён. Профилей пчёл: " + profiles.size());
    }

    @Override public void onDisable() { saveProfiles(); loadedBees.clear(); }

    private void registerCollarRecipe() {
        ItemStack item = namedItem(Material.LEAD, ChatColor.GOLD + "Ошейник пчеловода",
                List.of(ChatColor.GRAY + "ПКМ — открыть улей", ChatColor.YELLOW + "BeeKingdom"));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(collarKey, PersistentDataType.STRING, "true"));
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "bee_collar"), item);
        recipe.shape("LLL", "L L", "LLL");
        recipe.setIngredient('L', Material.LEAD);
        Bukkit.removeRecipe(new NamespacedKey(this, "bee_collar"));
        Bukkit.addRecipe(recipe);
    }

    private boolean isCollar(ItemStack item) {
        return item != null && item.getType() == Material.LEAD && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(collarKey, PersistentDataType.STRING);
    }

    @EventHandler public void onRidingAttackPlayer(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) return;
        Player rider = event.getPlayer();
        if (!(rider.getVehicle() instanceof Bee bee)) return;
        BeeProfile profile = profiles.get(bee.getUniqueId());
        if (profile == null || !profile.fattened || !profile.saddled || !profile.owner.equals(rider.getUniqueId())
                || target.getUniqueId().equals(rider.getUniqueId())) return;
        event.setCancelled(true);
        ridingAttackTargets.put(bee.getUniqueId(), target);
        bee.setAware(false);
        rider.sendMessage(ChatColor.YELLOW + "Пчела атакует " + target.getName() + " один раз.");
    }

    @EventHandler public void onTame(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Bee bee) || event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.POPPY) return;
        if (bee.getPersistentDataContainer().has(ownerKey, PersistentDataType.STRING)) {
            player.sendMessage(ChatColor.RED + "Эта пчела уже приручена."); return;
        }
        event.setCancelled(true);
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        double chance = Math.max(0.0, Math.min(1.0, getConfig().getDouble("taming-chance", 0.35)));
        if (ThreadLocalRandom.current().nextDouble() >= chance) {
            player.sendMessage(ChatColor.YELLOW + "Пчела пока не доверяет тебе. Попробуй ещё раз!");
            return;
        }
        BeeProfile profile = new BeeProfile(player.getUniqueId());
        rememberLocation(profile, bee);
        profiles.put(bee.getUniqueId(), profile);
        loadedBees.put(bee.getUniqueId(), bee);
        writeProfileToBee(bee, profile);
        applyBeeHealth(bee);
        bee.setPersistent(true);
        bee.setRemoveWhenFarAway(false);
        bee.setCustomName(ChatColor.YELLOW + "Пчела " + player.getName());
        bee.setCustomNameVisible(true);
        saveProfiles();
        player.sendMessage(ChatColor.GREEN + "Пчела приручена! Она теперь принадлежит тебе.");
    }

    @EventHandler public void onFeedBee(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Bee bee) || event.getHand() != EquipmentSlot.HAND) return;
        BeeProfile profile = profiles.get(bee.getUniqueId());
        if (profile == null || !profile.owner.equals(event.getPlayer().getUniqueId()) || profile.fattened) return;
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (hand.getType() != Material.SUGAR) return;
        event.setCancelled(true);
        if (event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
        profile.sugarFed = Math.min(64, profile.sugarFed + 1);
        if (profile.sugarFed >= 64) {
            profile.fattened = true;
            applyBeeScale(bee, profile);
            applyStoredPotionEffect(bee, profile);
            event.getPlayer().sendMessage(ChatColor.GREEN + "Пчела съела 64 сахара и выросла!");
        } else {
            event.getPlayer().sendMessage(ChatColor.YELLOW + "Сахар съеден: " + profile.sugarFed + "/64. Продолжай кормить по одной штуке.");
        }
        writeProfileToBee(bee, profile);
        saveProfiles();
    }

    private void applyStoredPotionEffect(Bee bee, BeeProfile profile) {
        PotionEffectType type = "SPEED".equalsIgnoreCase(profile.potionEffect) ? PotionEffectType.SPEED
                : "STRENGTH".equalsIgnoreCase(profile.potionEffect) ? PotionEffectType.STRENGTH : null;
        if (type != null) bee.addPotionEffect(new PotionEffect(type, 20 * 60 * 8, 0, true, true, true));
    }

    private void applyBeeScale(Bee bee, BeeProfile profile) {
        var scale = bee.getAttribute(Attribute.SCALE);
        if (scale != null) scale.setBaseValue(profile.fattened ? 1.85 : 1.0);
    }

    @EventHandler public void onSaddleOrMount(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Bee bee) || event.getHand() != EquipmentSlot.HAND) return;
        BeeProfile profile = profiles.get(bee.getUniqueId());
        if (profile == null || !profile.owner.equals(event.getPlayer().getUniqueId()) || !profile.fattened) return;
        ItemStack hand = event.getPlayer().getInventory().getItemInMainHand();
        if (!profile.saddled && hand.getType() == Material.SADDLE) {
            event.setCancelled(true); profile.saddled = true;
            if (event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE) hand.setAmount(hand.getAmount() - 1);
            writeProfileToBee(bee, profile); saveProfiles();
            event.getPlayer().sendMessage(ChatColor.GREEN + "Седло надето. Теперь можно оседлать пчелу.");
        } else if (profile.saddled && hand.getType() != Material.SUGAR) {
            event.setCancelled(true);
            if (bee.getPassengers().isEmpty()) {
                bee.addPassenger(event.getPlayer());
                event.getPlayer().sendMessage(ChatColor.GREEN + "Ты оседлал большую пчелу. Смотри в нужную сторону для управления.");
            }
        }
    }

    @EventHandler public void onCollarUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        if (!isCollar(event.getItem())) return;
        event.setCancelled(true);
        openMainMenu(event.getPlayer(), 0);
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        int raw = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (raw < 0) { event.setCancelled(true); return; }
        if (raw >= topSize) {
            // Разрешаем обычный клик в инвентаре игрока, чтобы он мог взять броню на курсор.
            // Shift-клик запрещён, иначе предмет попадёт в GUI автоматически.
            if (event.isShiftClick()) event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        if (event.getView().getTitle().contains("Управление отрядом")) {
            if (raw == 22) { openMainMenu(player, 0); return; }
            if (raw == 11 || raw == 15) { applyArmyItem(player, event, raw); return; }
            return;
        }
        if (event.getView().getTitle().contains("Откормленные пчёлы")) {
            if (raw < holder.entries.size()) { openBeeMenu(player, holder.entries.get(raw)); return; }
            if (raw == 45) { openMainMenu(player, 0); return; }
            if (raw == 51 && holder.page > 0) openFattenedMenu(player, holder.page - 1);
            if (raw == 53) openFattenedMenu(player, holder.page + 1);
            return;
        }
        if (!holder.detail) {
            if (raw < holder.entries.size()) { openBeeMenu(player, holder.entries.get(raw)); return; }
            if (raw == 44) { openArmyEquipmentMenu(player); return; }
            if (raw == 50) { openFattenedMenu(player, 0); return; }
            if (raw >= 45 && raw <= 49) {
                Mode[] modes = {Mode.FOLLOW, Mode.STAY, Mode.TARGET, Mode.PEACEFUL, Mode.BERSERK};
                Mode mode = modes[raw - 45];
                if (mode == Mode.TARGET) {
                    waitingForArmyTarget.add(player.getUniqueId()); player.closeInventory();
                    player.sendMessage(ChatColor.YELLOW + "Напиши в чат ник игрока. Напиши «отмена», чтобы отменить.");
                } else { setArmyMode(player.getUniqueId(), mode, ""); openMainMenu(player, holder.page); }
                return;
            }
            if (raw == 51 && holder.page > 0) openMainMenu(player, holder.page - 1);
            if (raw == 53) openMainMenu(player, holder.page + 1);
            return;
        }

        if (event.getView().getTitle().contains("Управление отрядом")) {
            if (raw == 22) { openMainMenu(player, 0); return; }
            if (raw == 11 || raw == 15) { applyArmyItem(player, event, raw); return; }
            return;
        }
        if (event.getView().getTitle().contains("Откормленные пчёлы")) {
            if (raw == 45) { openMainMenu(player, 0); return; }
            if (raw == 51 && holder.page > 0) openFattenedMenu(player, holder.page - 1);
            if (raw == 53) openFattenedMenu(player, holder.page + 1);
            return;
        }
        UUID beeId = holder.beeId;
        BeeProfile profile = profiles.get(beeId);
        Bee bee = loadedBees.get(beeId);
        if (raw == 49) { openMainMenu(player, 0); return; }
        if (raw == 34) { releaseBee(player, beeId); return; }
        if (profile == null || bee == null || !bee.isValid() || bee.isDead()) {
            player.sendMessage(ChatColor.RED + "Пчела сейчас не загружена. Подойди к её чанку."); player.closeInventory(); return;
        }
        if (raw == 10) setMode(player, beeId, Mode.FOLLOW, "");
        else if (raw == 12) setMode(player, beeId, Mode.STAY, "");
        else if (raw == 14) {
            pendingBeeTarget.put(player.getUniqueId(), beeId); waitingForTarget.add(player.getUniqueId()); player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + "Напиши в чат ник онлайн-игрока. Напиши «отмена», чтобы отменить."); return;
        } else if (raw == 16) setMode(player, beeId, Mode.PEACEFUL, "");
        else if (raw == 28) setMode(player, beeId, Mode.BERSERK, "");
        else if (raw == 31) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && isHorseArmor(cursor.getType())) {
                if (!profile.armor.isBlank()) giveArmor(player, profile.armor);
                profile.armor = cursor.getType().name();
                if (cursor.getAmount() <= 1) event.setCursor(null);
                else { cursor.setAmount(cursor.getAmount() - 1); event.setCursor(cursor); }
                applyArmorDisplay(bee, profile); writeProfileToBee(bee, profile); saveProfiles(); openBeeMenu(player, beeId); return;
            }
            if (!profile.armor.isBlank()) {
                giveArmor(player, profile.armor); profile.armor = "";
                applyArmorDisplay(bee, profile); writeProfileToBee(bee, profile); saveProfiles(); openBeeMenu(player, beeId); return;
            }
            player.sendMessage(ChatColor.YELLOW + "Возьми конскую броню на курсор и нажми на слот экипировки."); return;
        }
        saveProfiles(); openBeeMenu(player, beeId);
    }

    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder)) return;
        int top = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < top)) event.setCancelled(true);
    }

    private boolean isHorseArmor(Material material) { return material.name().endsWith("_HORSE_ARMOR"); }
    private void giveArmor(Player player, String name) {
        Material material = Material.matchMaterial(name);
        if (material == null) return;
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(material));
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private void releaseBee(Player player, UUID beeId) {
        BeeProfile profile = profiles.get(beeId);
        if (profile == null || !profile.owner.equals(player.getUniqueId())) return;
        Bee bee = loadedBees.get(beeId);
        if (bee != null && bee.isValid()) {
            removeArmorDisplay(bee);
            PersistentDataContainer pdc = bee.getPersistentDataContainer();
            pdc.remove(ownerKey); pdc.remove(modeKey); pdc.remove(targetKey); pdc.remove(armorKey);
            bee.setAware(true); bee.setTarget(null); bee.setCustomName(null); bee.setCustomNameVisible(false);
            bee.setPersistent(false); bee.setRemoveWhenFarAway(true);
            releasedBees.remove(beeId);
        } else {
            // При загрузке этого чанка удалим метки приручения из самой сущности.
            releasedBees.add(beeId);
        }
        profiles.remove(beeId); loadedBees.remove(beeId);
        freeFlightDirections.remove(beeId); freeFlightRefresh.remove(beeId);
        pendingBeeTarget.values().removeIf(id -> id.equals(beeId));
        saveProfiles();
        player.sendMessage(ChatColor.GREEN + "Пчела отпущена. Она больше не связана с твоим ошейником.");
        openMainMenu(player, 0);
    }

    @EventHandler public void onChatTarget(AsyncChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        boolean single = waitingForTarget.remove(playerId);
        boolean army = waitingForArmyTarget.remove(playerId);
        if (!single && !army) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId); if (player == null) return;
            if (input.equalsIgnoreCase("отмена") || input.equalsIgnoreCase("cancel")) {
                pendingBeeTarget.remove(playerId); player.sendMessage(ChatColor.GRAY + "Выбор цели отменён."); return;
            }
            Player target = Bukkit.getPlayerExact(input);
            if (target == null) { player.sendMessage(ChatColor.RED + "Игрок с таким ником не найден онлайн."); return; }
            if (army) { setArmyMode(playerId, Mode.TARGET, target.getName()); player.sendMessage(ChatColor.GREEN + "Цель армии: " + target.getName()); openMainMenu(player, 0); }
            else {
                UUID beeId = pendingBeeTarget.remove(playerId);
                if (beeId == null || !profiles.containsKey(beeId)) { player.sendMessage(ChatColor.RED + "Не удалось найти выбранную пчелу."); return; }
                setMode(player, beeId, Mode.TARGET, target.getName()); openBeeMenu(player, beeId);
            }
        });
    }

    @EventHandler public void onBeeTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Bee bee)) return;
        BeeProfile profile = profiles.get(bee.getUniqueId()); if (profile == null) return;
        LivingEntity target = event.getTarget(); if (target == null) return;
        if (target instanceof Player player && player.getUniqueId().equals(profile.owner)) { event.setCancelled(true); return; }
        if (profile.saddled && bee.getPassengers().stream().anyMatch(passenger -> passenger instanceof Player)) return;
        switch (profile.mode) {
            case FOLLOW, PEACEFUL -> event.setCancelled(true);
            // В свободном полёте пчела использует обычный AI и может защищаться.
            case STAY -> { }
            case TARGET -> { if (!(target instanceof Player player) || !player.getName().equalsIgnoreCase(profile.targetName)) event.setCancelled(true); }
            case BERSERK -> { if (target instanceof Bee other && isOwnedBy(other, profile.owner)) event.setCancelled(true); }
        }
    }

    @EventHandler public void onBeeDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Bee victim && event.getDamager() instanceof Player attacker) {
            BeeProfile victimProfile = profiles.get(victim.getUniqueId());
            // Урон по приручённым пчёлам не отменяем: атаковать их могут все игроки.
            // В свободном полёте пчела отвечает на нападение обычным поведением Minecraft.
            if (victimProfile != null && victimProfile.mode == Mode.STAY) {
                victim.setAware(true);
                victim.setTarget(attacker);
            }
        }
        if (event.getDamager() instanceof Bee bee) {
            BeeProfile profile = profiles.get(bee.getUniqueId());
            if (profile != null) {
                if (event.getEntity() instanceof Player player && player.getUniqueId().equals(profile.owner)) event.setCancelled(true);
                // Сбрасываем флаг ужаливания, чтобы приручённая пчела не погибала после атаки.
                Bukkit.getScheduler().runTask(this, () -> { if (bee.isValid()) bee.setHasStung(false); });
            }
        }
    }

    @EventHandler public void onBeeDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Bee bee)) return;
        BeeProfile profile = profiles.remove(bee.getUniqueId()); if (profile == null) return;
        if (!profile.armor.isBlank()) { Material armor = Material.matchMaterial(profile.armor); if (armor != null) event.getDrops().add(new ItemStack(armor)); }
        removeArmorDisplay(bee); loadedBees.remove(bee.getUniqueId());
        freeFlightDirections.remove(bee.getUniqueId()); freeFlightRefresh.remove(bee.getUniqueId());
        pendingBeeTarget.values().removeIf(id -> id.equals(bee.getUniqueId())); saveProfiles();
    }

    @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) if (entity instanceof Bee bee) registerLoadedBee(bee);
        boolean changed = profiles.entrySet().removeIf(entry -> {
            BeeProfile p = entry.getValue();
            return p.hasLocation && p.world.equals(event.getWorld().getName()) && p.chunkX == event.getChunk().getX()
                    && p.chunkZ == event.getChunk().getZ() && !loadedBees.containsKey(entry.getKey());
        });
        if (changed) saveProfiles();
    }

    @EventHandler public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) if (entity instanceof Bee bee) {
            BeeProfile profile = profiles.get(bee.getUniqueId());
            if (profile != null) rememberLocation(profile, bee);
            loadedBees.remove(bee.getUniqueId());
            freeFlightDirections.remove(bee.getUniqueId()); freeFlightRefresh.remove(bee.getUniqueId());
        }
        saveProfiles();
    }

    private void cleanupAlreadyLoadedChunks() {
        boolean changed = profiles.entrySet().removeIf(entry -> {
            BeeProfile p = entry.getValue();
            if (!p.hasLocation) return false;
            World world = Bukkit.getWorld(p.world);
            return world != null && world.isChunkLoaded(p.chunkX, p.chunkZ) && !loadedBees.containsKey(entry.getKey());
        });
        if (changed) saveProfiles();
    }

    private void registerLoadedBee(Bee bee) {
        UUID id = bee.getUniqueId();
        if (releasedBees.remove(id)) {
            removeArmorDisplay(bee);
            PersistentDataContainer releasedPdc = bee.getPersistentDataContainer();
            releasedPdc.remove(ownerKey); releasedPdc.remove(modeKey); releasedPdc.remove(targetKey); releasedPdc.remove(armorKey);
            bee.setAware(true); bee.setTarget(null); bee.setCustomName(null); bee.setCustomNameVisible(false);
            bee.setPersistent(false); bee.setRemoveWhenFarAway(true);
            saveProfiles();
            return;
        }
        String ownerText = bee.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerText == null) return;
        BeeProfile profile = profiles.get(id);
        if (profile == null) {
            try { profile = new BeeProfile(UUID.fromString(ownerText)); } catch (IllegalArgumentException ex) { return; }
            String modeText = bee.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
            if (modeText != null) try { profile.mode = Mode.valueOf(modeText); } catch (IllegalArgumentException ignored) { }
            profile.targetName = valueOrEmpty(bee.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING));
            profile.armor = valueOrEmpty(bee.getPersistentDataContainer().get(armorKey, PersistentDataType.STRING));
            profile.fattened = Boolean.parseBoolean(valueOrEmpty(bee.getPersistentDataContainer().get(fattenedKey, PersistentDataType.STRING)));
            profile.saddled = Boolean.parseBoolean(valueOrEmpty(bee.getPersistentDataContainer().get(saddledKey, PersistentDataType.STRING)));
            profile.potionEffect = valueOrEmpty(bee.getPersistentDataContainer().get(potionEffectKey, PersistentDataType.STRING));
            Integer fed = bee.getPersistentDataContainer().get(sugarFedKey, PersistentDataType.INTEGER);
            profile.sugarFed = fed == null ? 0 : Math.max(0, Math.min(64, fed));
            profiles.put(id, profile);
        }
        rememberLocation(profile, bee); loadedBees.put(id, bee);
        if (!profile.armor.isBlank()) applyArmorDisplay(bee, profile);
        applyBeeScale(bee, profile);
        applyBeeHealth(bee);
    }

    private void applyBeeHealth(Bee bee) {
        var health = bee.getAttribute(Attribute.MAX_HEALTH);
        if (health == null) return;
        double maxHealth = Math.max(20.0, getConfig().getDouble("tamed-bee-max-health", 60.0));
        health.setBaseValue(maxHealth);
        if (bee.getHealth() < maxHealth) bee.setHealth(maxHealth);
    }

    private void rememberLocation(BeeProfile profile, Bee bee) {
        profile.world = bee.getWorld().getName(); profile.chunkX = bee.getLocation().getBlockX() >> 4;
        profile.chunkZ = bee.getLocation().getBlockZ() >> 4; profile.hasLocation = true;
    }

    private void tickBees() {
        for (Map.Entry<UUID, BeeProfile> entry : profiles.entrySet()) {
            Bee bee = loadedBees.get(entry.getKey()); if (bee == null || !bee.isValid() || bee.isDead()) continue;
            BeeProfile profile = entry.getValue();
            // Флаг ужаливания регулярно сбрасывается: таймер смерти после укуса не должен сработать.
            if (bee.hasStung()) bee.setHasStung(false);
            Player rider = bee.getPassengers().stream().filter(passenger -> passenger instanceof Player).map(passenger -> (Player) passenger).findFirst().orElse(null);
            if (rider != null && profile.fattened && profile.saddled) {
                bee.setAware(false);
                bee.setGravity(false);
                LivingEntity attackTarget = ridingAttackTargets.get(bee.getUniqueId());
                if (attackTarget != null && (!attackTarget.isValid() || attackTarget.isDead() || !attackTarget.getWorld().equals(bee.getWorld()))) {
                    ridingAttackTargets.remove(bee.getUniqueId()); attackTarget = null;
                }
                if (attackTarget != null) {
                    Vector delta = attackTarget.getLocation().toVector().subtract(bee.getLocation().toVector());
                    if (delta.lengthSquared() <= 2.25) {
                        attackTarget.damage(4.0, bee);
                        ridingAttackTargets.remove(bee.getUniqueId());
                        bee.setVelocity(new Vector(0, 0.08, 0));
                    } else {
                        bee.setVelocity(delta.normalize().multiply(0.48));
                        faceVelocity(bee);
                    }
                } else {
                    Vector direction = rider.getLocation().getDirection().normalize();
                    bee.setVelocity(direction.multiply(0.38));
                    bee.setRotation(rider.getLocation().getYaw(), Math.max(-35.0f, Math.min(35.0f, rider.getLocation().getPitch())));
                }
                continue;
            }
            bee.setGravity(true);
            ridingAttackTargets.remove(bee.getUniqueId());
            Player owner = Bukkit.getPlayer(profile.owner);
            if (profile.mode == Mode.FOLLOW) {
                bee.setAware(false); bee.setTarget(null);
                if (owner == null || !owner.isOnline() || !owner.getWorld().equals(bee.getWorld())) continue;
                double distance = owner.getLocation().distance(bee.getLocation());
                if (distance > 28) bee.teleport(owner.getLocation().clone().add(1.0, 1.0, 0));
                else if (distance > 2.8) {
                    Vector direction = owner.getLocation().toVector().subtract(bee.getLocation().toVector()).normalize().multiply(0.22);
                    bee.setVelocity(bee.getVelocity().multiply(0.35).add(direction));
                    faceVelocity(bee);
                } else bee.setVelocity(bee.getVelocity().multiply(0.45));
            } else if (profile.mode == Mode.STAY) {
                // Свободный полёт использует штатный AI пчелы Minecraft:
                // естественное блуждание, повороты и реакцию на окружающий мир.
                // Не сбрасываем цель — пчела должна суметь ответить на нападение.
                bee.setAware(true);
            } else if (profile.mode == Mode.PEACEFUL) {
                bee.setAware(true); bee.setTarget(null);
            } else if (profile.mode == Mode.TARGET) {
                bee.setAware(true); Player target = Bukkit.getPlayerExact(profile.targetName);
                if (target != null && target.getWorld().equals(bee.getWorld()) && target.getLocation().distanceSquared(bee.getLocation()) <= 625) bee.setTarget(target);
                else bee.setTarget(null);
            } else if (profile.mode == Mode.BERSERK) {
                bee.setAware(true);
                LivingEntity target = bee.getNearbyEntities(16, 16, 16).stream().filter(e -> e instanceof LivingEntity)
                        .filter(e -> !e.getUniqueId().equals(profile.owner))
                        .filter(e -> !(e instanceof Bee other && isOwnedBy(other, profile.owner)))
                        .map(e -> (LivingEntity) e).min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(bee.getLocation()))).orElse(null);
                bee.setTarget(target);
            }
            rememberLocation(profile, bee);
        }
    }

    private void faceVelocity(Bee bee) {
        Vector velocity = bee.getVelocity();
        double horizontal = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        if (horizontal < 0.008) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-velocity.getX(), velocity.getZ()));
        float pitch = (float) Math.toDegrees(Math.atan2(-velocity.getY(), horizontal));
        bee.setRotation(yaw, Math.max(-25.0f, Math.min(25.0f, pitch)));
    }

    private boolean isOwnedBy(Bee bee, UUID owner) {
        String value = bee.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        return owner.toString().equals(value);
    }

    private void setMode(Player player, UUID beeId, Mode mode, String target) {
        BeeProfile profile = profiles.get(beeId); if (profile == null || !profile.owner.equals(player.getUniqueId())) return;
        profile.mode = mode; profile.targetName = target == null ? "" : target;
        Bee bee = loadedBees.get(beeId); if (bee != null) { writeProfileToBee(bee, profile); bee.setAware(mode != Mode.FOLLOW && mode != Mode.STAY); }
        player.sendMessage(ChatColor.GREEN + "Режим пчелы: " + mode.title + " — включён.");
    }

    private void setArmyMode(UUID owner, Mode mode, String target) {
        for (Map.Entry<UUID, BeeProfile> entry : profiles.entrySet()) if (entry.getValue().owner.equals(owner)) {
            BeeProfile p = entry.getValue(); p.mode = mode; p.targetName = target == null ? "" : target;
            Bee bee = loadedBees.get(entry.getKey()); if (bee != null) { writeProfileToBee(bee, p); bee.setAware(mode != Mode.FOLLOW && mode != Mode.STAY); }
        }
        saveProfiles();
    }

    private void writeProfileToBee(Bee bee, BeeProfile profile) {
        PersistentDataContainer pdc = bee.getPersistentDataContainer();
        pdc.set(ownerKey, PersistentDataType.STRING, profile.owner.toString());
        pdc.set(modeKey, PersistentDataType.STRING, profile.mode.name());
        pdc.set(targetKey, PersistentDataType.STRING, profile.targetName);
        pdc.set(armorKey, PersistentDataType.STRING, profile.armor);
        pdc.set(fattenedKey, PersistentDataType.STRING, Boolean.toString(profile.fattened));
        pdc.set(saddledKey, PersistentDataType.STRING, Boolean.toString(profile.saddled));
        pdc.set(potionEffectKey, PersistentDataType.STRING, profile.potionEffect);
        pdc.set(sugarFedKey, PersistentDataType.INTEGER, profile.sugarFed);
    }

    private void removeArmorDisplay(Bee bee) {
        for (Entity passenger : new ArrayList<>(bee.getPassengers())) if (passenger instanceof ItemDisplay display
                && display.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING)) display.remove();
    }

    private void applyArmorDisplay(Bee bee, BeeProfile profile) {
        removeArmorDisplay(bee);
        if (profile.armor.isBlank()) return;
        Material material = Material.matchMaterial(profile.armor); if (material == null) return;
        ItemDisplay display = bee.getWorld().spawn(bee.getLocation(), ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(material));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
            // Меньший размер и смещение вверх выводят модель снаряжения над спинкой,
            // вместо того чтобы крупный предмет проходил сквозь тело пчелы.
            entity.setTransformation(new Transformation(new Vector3f(0.0f, 0.16f, 0.0f), new Quaternionf(),
                    new Vector3f(0.48f, 0.48f, 0.48f), new Quaternionf()));
            entity.setPersistent(true); entity.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, bee.getUniqueId().toString());
        });
        bee.addPassenger(display);
    }

    private void openMainMenu(Player player, int requestedPage) {
        List<UUID> owned = profiles.entrySet().stream().filter(e -> e.getValue().owner.equals(player.getUniqueId()) && !e.getValue().fattened)
                .map(Map.Entry::getKey).sorted(Comparator.comparing(UUID::toString)).toList();
        int maxPage = Math.max(0, (owned.size() - 1) / 36); int page = Math.max(0, Math.min(requestedPage, maxPage));
        MenuHolder holder = new MenuHolder(false, page, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§6✦ BeeKingdom §8| §fКоролевство пчёл"); holder.inventory = inv;
        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, "§8 ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);
        int start = page * 36;
        for (int i = start; i < Math.min(start + 36, owned.size()); i++) {
            UUID id = owned.get(i); BeeProfile p = profiles.get(id); Bee bee = loadedBees.get(id);
            boolean loaded = bee != null && bee.isValid() && !bee.isDead();
            List<String> lore = List.of("§7Владелец: §fты", "§7Режим: §f" + p.mode.title,
                    "§7Статус: " + (loaded ? "§aВ мире" : "§eЧанк не загружен"),
                    "§7Экипировка: §f" + (p.armor.isBlank() ? "нет" : friendlyArmor(p.armor)), "", "§eНажми, чтобы открыть настройки");
            inv.setItem(i - start, namedItem(loaded ? Material.HONEYCOMB : Material.HONEY_BOTTLE,
                    "§6Пчела №" + (i + 1), lore)); holder.entries.add(id);
        }
        inv.setItem(45, armyModeItem(player.getUniqueId(), Material.FEATHER, "§aСледовать за мной", Mode.FOLLOW));
        inv.setItem(46, armyModeItem(player.getUniqueId(), Material.OAK_SAPLING, "§eСвободный полёт", Mode.STAY));
        inv.setItem(47, armyModeItem(player.getUniqueId(), Material.TARGET, "§cВыбрать цель", Mode.TARGET));
        inv.setItem(48, armyModeItem(player.getUniqueId(), Material.WHITE_WOOL, "§bМирный режим", Mode.PEACEFUL));
        inv.setItem(49, armyModeItem(player.getUniqueId(), Material.BLAZE_POWDER, "§6Защита территории", Mode.BERSERK));
        if (page > 0) inv.setItem(51, namedItem(Material.ARROW, "§f← Назад", List.of("§7Предыдущая страница")));
        inv.setItem(44, namedItem(Material.CHEST, "§bУправление отрядом", List.of("§7Общая броня и эффекты", "§eНажми, чтобы открыть")));
        inv.setItem(50, namedItem(Material.HONEY_BLOCK, "§6Откормленные пчёлы", List.of("§7Отдельная категория", "§7Количество: " + profiles.values().stream().filter(p -> p.owner.equals(player.getUniqueId()) && p.fattened).count(), "§eНажми, чтобы открыть")));
        inv.setItem(52, namedItem(Material.MAP, "§eСтраница " + (page + 1) + "/" + (maxPage + 1), List.of("§7Обычных пчёл: " + owned.size(), "§7Нажми на пчелу для настроек")));
        if (page < maxPage) inv.setItem(53, namedItem(Material.ARROW, "§fВперёд →", List.of("§7Следующая страница")));
        player.openInventory(inv);
    }

    private void openFattenedMenu(Player player, int requestedPage) {
        List<UUID> owned = profiles.entrySet().stream().filter(e -> e.getValue().owner.equals(player.getUniqueId()) && e.getValue().fattened)
                .map(Map.Entry::getKey).sorted(Comparator.comparing(UUID::toString)).toList();
        int maxPage = Math.max(0, (owned.size() - 1) / 36); int page = Math.max(0, Math.min(requestedPage, maxPage));
        MenuHolder holder = new MenuHolder(false, page, null);
        Inventory inv = Bukkit.createInventory(holder, 54, "§6✦ BeeKingdom §8| §fОткормленные пчёлы"); holder.inventory = inv;
        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, "§8 ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);
        for (int i = page * 36; i < Math.min((page + 1) * 36, owned.size()); i++) {
            UUID id = owned.get(i); BeeProfile p = profiles.get(id); Bee bee = loadedBees.get(id);
            boolean loaded = bee != null && bee.isValid() && !bee.isDead();
            inv.setItem(i - page * 36, namedItem(Material.HONEY_BLOCK, "§6Большая пчела №" + (i + 1),
                    List.of("§7Режим: §f" + p.mode.title, "§7Статус: " + (loaded ? "§aВ мире" : "§eЧанк не загружен"),
                            "§7Броня: §f" + (p.armor.isBlank() ? "нет" : friendlyArmor(p.armor)), "§eНажми для настроек")));
            holder.entries.add(id);
        }
        inv.setItem(45, namedItem(Material.ARROW, "§f← К обычным пчёлам", List.of()));
        if (page > 0) inv.setItem(51, namedItem(Material.ARROW, "§f← Назад", List.of()));
        inv.setItem(52, namedItem(Material.MAP, "§eСтраница " + (page + 1) + "/" + (maxPage + 1), List.of("§7Всего: " + owned.size())));
        if (page < maxPage) inv.setItem(53, namedItem(Material.ARROW, "§fВперёд →", List.of()));
        player.openInventory(inv);
    }

    private void openArmyEquipmentMenu(Player player) {
        MenuHolder holder = new MenuHolder(false, 0, null);
        Inventory inv = Bukkit.createInventory(holder, 27, "§6✦ BeeKingdom §8| §fУправление отрядом"); holder.inventory = inv;
        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, "§8 ", List.of());
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        long count = profiles.values().stream().filter(p -> p.owner.equals(player.getUniqueId())).count();
        inv.setItem(11, namedItem(Material.DIAMOND_HORSE_ARMOR, "§bБроня всему отряду", List.of("§7Нужно по одной конской броне", "§7на каждую пчелу: " + count, "§eВозьми нужное количество на курсор")));
        inv.setItem(15, namedItem(Material.POTION, "§dЭффект всему отряду", List.of("§7Нужно ОДНО взрывное зелье", "§7Сила или Скорость для всего отряда", "§eВозьми зелье на курсор")));
        inv.setItem(22, namedItem(Material.ARROW, "§f← Назад", List.of()));
        player.openInventory(inv);
    }

    private int countArmyItems(Player player, ItemStack cursor, Predicate<ItemStack> matches) {
        int count = cursor != null && matches.test(cursor) ? cursor.getAmount() : 0;
        for (ItemStack item : player.getInventory().getStorageContents())
            if (item != null && matches.test(item)) count += item.getAmount();
        return count;
    }

    private void consumeArmyItems(Player player, InventoryClickEvent event, Predicate<ItemStack> matches, int amount) {
        int remaining = amount;
        ItemStack cursor = event.getCursor();
        if (cursor != null && matches.test(cursor)) {
            int used = Math.min(remaining, cursor.getAmount());
            remaining -= used;
            if (cursor.getAmount() == used) event.setCursor(null);
            else { cursor.setAmount(cursor.getAmount() - used); event.setCursor(cursor); }
        }
        for (int slot = 0; slot < player.getInventory().getStorageContents().length && remaining > 0; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || !matches.test(item)) continue;
            int used = Math.min(remaining, item.getAmount());
            remaining -= used;
            if (item.getAmount() == used) player.getInventory().setItem(slot, null);
            else { item.setAmount(item.getAmount() - used); player.getInventory().setItem(slot, item); }
        }
    }

    private PotionEffectType effectFromPotion(ItemStack item) {
        if (item == null || item.getType() != Material.SPLASH_POTION || !(item.getItemMeta() instanceof PotionMeta meta)) return null;
        String potion = meta.getBasePotionType().name();
        if (potion.contains("STRENGTH")) return PotionEffectType.STRENGTH;
        if (potion.contains("SWIFTNESS") || potion.contains("SPEED")) return PotionEffectType.SPEED;
        return null;
    }

    private void applyArmyItem(Player player, InventoryClickEvent event, int slot) {
        List<Map.Entry<UUID, BeeProfile>> army = profiles.entrySet().stream().filter(e -> e.getValue().owner.equals(player.getUniqueId())).toList();
        if (army.isEmpty()) { player.sendMessage(ChatColor.RED + "У тебя пока нет приручённых пчёл."); return; }
        ItemStack cursor = event.getCursor();
        if (slot == 11) {
            if (cursor == null || !isHorseArmor(cursor.getType()) || countArmyItems(player, cursor, item -> isHorseArmor(item.getType()) && item.getType() == cursor.getType()) < army.size()) {
                player.sendMessage(ChatColor.RED + "Нужно конской брони одного типа: " + army.size() + " шт. (считаются предметы в инвентаре и на курсоре)."); return;
            }
            Material armor = cursor.getType();
            for (Map.Entry<UUID, BeeProfile> entry : army) {
                BeeProfile p = entry.getValue();
                if (!p.armor.isBlank()) giveArmor(player, p.armor);
                p.armor = armor.name();
                Bee bee = loadedBees.get(entry.getKey());
                if (bee != null && bee.isValid()) { applyArmorDisplay(bee, p); writeProfileToBee(bee, p); }
            }
            if (player.getGameMode() != org.bukkit.GameMode.CREATIVE)
                consumeArmyItems(player, event, item -> item.getType() == armor, army.size());
            saveProfiles(); player.sendMessage(ChatColor.GREEN + "Броня выдана всем " + army.size() + " пчёлам.");
        } else {
            PotionEffectType effect = effectFromPotion(cursor);
            if (effect == null) { player.sendMessage(ChatColor.RED + "Пока доступны только зелья Силы и Скорости."); return; }
            Predicate<ItemStack> matchingPotion = item -> effectFromPotion(item) == effect;
            if (countArmyItems(player, cursor, matchingPotion) < 1) {
                player.sendMessage(ChatColor.RED + "Нужно одно взрывное зелье эффекта " + effect.getName() + "."); return;
            }
            for (Map.Entry<UUID, BeeProfile> entry : army) {
                BeeProfile profile = entry.getValue();
                profile.potionEffect = effect.getName();
                Bee bee = loadedBees.get(entry.getKey());
                if (bee != null && bee.isValid()) applyStoredPotionEffect(bee, profile);
            }
            if (player.getGameMode() != org.bukkit.GameMode.CREATIVE)
                consumeArmyItems(player, event, matchingPotion, 1);
            player.sendMessage(ChatColor.GREEN + "Одно взрывное зелье " + effect.getName() + " применено ко всем " + army.size() + " пчёлам.");
        }
        saveProfiles(); openArmyEquipmentMenu(player);
    }

    private void openBeeMenu(Player player, UUID beeId) {
        BeeProfile p = profiles.get(beeId); if (p == null || !p.owner.equals(player.getUniqueId())) return;
        Bee bee = loadedBees.get(beeId); boolean loaded = bee != null && bee.isValid() && !bee.isDead();
        MenuHolder holder = new MenuHolder(true, 0, beeId);
        Inventory inv = Bukkit.createInventory(holder, 54, "§6✦ BeeKingdom §8| §fНастройки пчелы"); holder.inventory = inv;
        ItemStack filler = namedItem(Material.GRAY_STAINED_GLASS_PANE, "§8 ", List.of());
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);
        inv.setItem(4, namedItem(Material.BEEHIVE, "§6Информация о пчеле", List.of("§7Статус: " + (loaded ? "§aЗагружена" : "§eЧанк выгружен"), "§7Текущий режим: §f" + p.mode.title, p.fattened ? "§aОткормлена" : "§7Сахар: " + p.sugarFed + "/64")));
        inv.setItem(10, modeItem(Material.FEATHER, "§aСледовать за игроком", Mode.FOLLOW, p));
        inv.setItem(12, modeItem(Material.OAK_SAPLING, "§eСвободный полёт", Mode.STAY, p));
        inv.setItem(14, modeItem(Material.TARGET, "§cАтака выбранной цели", Mode.TARGET, p, "§7Нажми и введи ник игрока"));
        inv.setItem(16, modeItem(Material.WHITE_WOOL, "§bМирный режим", Mode.PEACEFUL, p));
        inv.setItem(28, modeItem(Material.BLAZE_POWDER, "§6Защита территории", Mode.BERSERK, p));
        inv.setItem(31, namedItem(p.armor.isBlank() ? Material.LEATHER_HORSE_ARMOR : safeMaterial(p.armor, Material.DIAMOND_HORSE_ARMOR),
                "§bЭкипировка пчелы", List.of("§7Разрешена конская броня", p.armor.isBlank() ? "§cСейчас без брони" : "§aНадето: " + friendlyArmor(p.armor),
                        "", "§eВозьми броню на курсор и нажми сюда", "§7Нажми пустым курсором, чтобы снять")));
        inv.setItem(34, namedItem(Material.LEAD, "§cОтпустить пчелу", List.of("§7Удалит её из твоего списка", "§7Пчела станет обычной дикой пчелой", "", "§cНажми, чтобы отпустить")));
        inv.setItem(49, namedItem(Material.ARROW, "§f← Назад к улью", List.of("§7Вернуться к списку пчёл")));
        player.openInventory(inv);
    }

    private Material safeMaterial(String name, Material fallback) { Material m = Material.matchMaterial(name); return m == null ? fallback : m; }
    private String friendlyArmor(String name) { return name.toLowerCase().replace('_', ' '); }
    private ItemStack armyModeItem(UUID owner, Material material, String name, Mode mode) {
        List<BeeProfile> list = profiles.values().stream().filter(p -> p.owner.equals(owner)).toList();
        boolean active = !list.isEmpty() && list.stream().allMatch(p -> p.mode == mode);
        return namedItem(material, name, List.of(active ? "§aАктивно у всей армии" : "§7Нажми, чтобы применить ко всем", "§7Пчёл: " + list.size()));
    }
    private ItemStack modeItem(Material material, String name, Mode mode, BeeProfile p, String... extra) {
        List<String> lore = new ArrayList<>(); lore.add(p.mode == mode ? "§a● Активен" : "§7○ Не выбран");
        lore.add("§7Нажми, чтобы переключить режим"); if (mode == Mode.TARGET && !p.targetName.isBlank()) lore.add("§7Цель: §f" + p.targetName);
        lore.addAll(List.of(extra)); return namedItem(material, name, lore);
    }
    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material); ItemMeta meta = item.getItemMeta(); meta.setDisplayName(name); meta.setLore(lore); item.setItemMeta(meta); return item;
    }

    private void loadProfiles() {
        profiles.clear();
        releasedBees.clear();
        for (String value : getConfig().getStringList("released-bees")) {
            try { releasedBees.add(UUID.fromString(value)); } catch (IllegalArgumentException ignored) { }
        }
        if (!getConfig().isConfigurationSection("bees")) return;
        for (String key : getConfig().getConfigurationSection("bees").getKeys(false)) try {
            UUID id = UUID.fromString(key); String owner = getConfig().getString("bees." + key + ".owner", "");
            BeeProfile p = new BeeProfile(UUID.fromString(owner));
            try { p.mode = Mode.valueOf(getConfig().getString("bees." + key + ".mode", "FOLLOW")); } catch (IllegalArgumentException ignored) { p.mode = Mode.FOLLOW; }
            p.targetName = getConfig().getString("bees." + key + ".target", ""); p.armor = getConfig().getString("bees." + key + ".armor", "");
            p.fattened = getConfig().getBoolean("bees." + key + ".fattened", false);
            p.saddled = getConfig().getBoolean("bees." + key + ".saddled", false);
            p.potionEffect = getConfig().getString("bees." + key + ".potion-effect", "");
            p.sugarFed = Math.max(0, Math.min(64, getConfig().getInt("bees." + key + ".sugar-fed", 0)));
            p.world = getConfig().getString("bees." + key + ".world", ""); p.chunkX = getConfig().getInt("bees." + key + ".chunk-x");
            p.chunkZ = getConfig().getInt("bees." + key + ".chunk-z"); p.hasLocation = !p.world.isBlank() && getConfig().contains("bees." + key + ".chunk-x");
            profiles.put(id, p);
        } catch (IllegalArgumentException ignored) { getLogger().warning("Пропущена некорректная запись пчелы: " + key); }
    }
    private void saveProfiles() {
        getConfig().set("bees", null);
        getConfig().set("released-bees", releasedBees.stream().map(UUID::toString).toList());
        for (Map.Entry<UUID, BeeProfile> entry : profiles.entrySet()) {
            String path = "bees." + entry.getKey(); BeeProfile p = entry.getValue();
            getConfig().set(path + ".owner", p.owner.toString()); getConfig().set(path + ".mode", p.mode.name());
            getConfig().set(path + ".target", p.targetName); getConfig().set(path + ".armor", p.armor);
            getConfig().set(path + ".fattened", p.fattened); getConfig().set(path + ".saddled", p.saddled);
            getConfig().set(path + ".potion-effect", p.potionEffect);
            getConfig().set(path + ".sugar-fed", p.sugarFed);
            if (p.hasLocation) { getConfig().set(path + ".world", p.world); getConfig().set(path + ".chunk-x", p.chunkX); getConfig().set(path + ".chunk-z", p.chunkZ); }
        }
        saveConfig();
    }
    private String valueOrEmpty(String value) { return value == null ? "" : value; }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Эта команда доступна только игроку."); return true; }
        if (!player.hasPermission("beekdom.use")) { player.sendMessage(ChatColor.RED + "У тебя нет разрешения beekdom.use."); return true; }
        openMainMenu(player, 0); return true;
    }
}
