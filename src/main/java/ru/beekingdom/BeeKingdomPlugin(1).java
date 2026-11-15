package ru.beekingdom;

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
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class BeeKingdomPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private enum Mode {
        FOLLOW("Следовать за игроком"),
        STAY("Свободный полёт"),
        TARGET("В атаку: выбранная цель"),
        PEACEFUL("Мирный режим"),
        BERSERK("Я конченный, бегите!");

        private final String title;
        Mode(String title) { this.title = title; }
        String title() { return title; }
    }

    private static final class BeeProfile {
        UUID owner;
        Mode mode = Mode.FOLLOW;
        String targetName = "";
        String armor = "";
        BeeProfile(UUID owner) { this.owner = owner; }
    }

    private static final class MenuHolder implements InventoryHolder {
        final boolean detail;
        final int page;
        final UUID beeId;
        final List<UUID> entries = new ArrayList<>();
        Inventory inventory;
        MenuHolder(boolean detail, int page, UUID beeId) {
            this.detail = detail; this.page = page; this.beeId = beeId;
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final Map<UUID, BeeProfile> profiles = new HashMap<>();
    private final Map<UUID, Bee> loadedBees = new HashMap<>();
    private final Set<UUID> waitingForTarget = new HashSet<>();
    private final Set<UUID> waitingForArmyTarget = new HashSet<>();

    private NamespacedKey ownerKey;
    private NamespacedKey modeKey;
    private NamespacedKey targetKey;
    private NamespacedKey armorKey;
    private NamespacedKey collarKey;
    private NamespacedKey displayKey;

    @Override
    public void onEnable() {
        ownerKey = new NamespacedKey(this, "owner");
        modeKey = new NamespacedKey(this, "mode");
        targetKey = new NamespacedKey(this, "target");
        armorKey = new NamespacedKey(this, "armor");
        collarKey = new NamespacedKey(this, "bee_collar");
        displayKey = new NamespacedKey(this, "armor_display");

        saveDefaultConfig();
        loadProfiles();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("beekdom") != null) getCommand("beekdom").setExecutor(this);
        registerCollarRecipe();

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Bee bee) registerLoadedBee(bee);
            }
        }
        Bukkit.getScheduler().runTaskTimer(this, this::tickBees, 20L, 10L);
        getLogger().info("BeeKingdom включён. Загружено профилей пчёл: " + profiles.size());
    }

    @Override
    public void onDisable() {
        saveProfiles();
        loadedBees.clear();
    }

    private void registerCollarRecipe() {
        ItemStack collar = new ItemStack(Material.LEAD);
        ItemMeta meta = collar.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "Ошейник пчеловода");
        meta.setLore(List.of(ChatColor.GRAY + "ПКМ по воздуху — открыть улей", ChatColor.YELLOW + "BeeKingdom"));
        meta.getPersistentDataContainer().set(collarKey, PersistentDataType.STRING, "true");
        collar.setItemMeta(meta);
        ShapedRecipe recipe = new ShapedRecipe(new NamespacedKey(this, "bee_collar"), collar);
        recipe.shape("LLL", "L L", "LLL");
        recipe.setIngredient('L', Material.LEAD);
        Bukkit.addRecipe(recipe);
    }

    private boolean isCollar(ItemStack item) {
        if (item == null || item.getType() != Material.LEAD || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(collarKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onTame(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Bee bee)) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.POPPY) return;

        PersistentDataContainer pdc = bee.getPersistentDataContainer();
        if (pdc.has(ownerKey, PersistentDataType.STRING)) {
            player.sendMessage(ChatColor.RED + "Эта пчела уже приручена.");
            return;
        }
        event.setCancelled(true);
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            hand.setAmount(hand.getAmount() - 1);
        }
        BeeProfile profile = new BeeProfile(player.getUniqueId());
        profiles.put(bee.getUniqueId(), profile);
        loadedBees.put(bee.getUniqueId(), bee);
        writeProfileToBee(bee, profile);
        bee.setPersistent(true);
        bee.setRemoveWhenFarAway(false);
        bee.setCustomName(ChatColor.YELLOW + "Пчела " + player.getName());
        bee.setCustomNameVisible(true);
        saveProfiles();
        player.sendMessage(ChatColor.GREEN + "Пчела приручена! Теперь она принадлежит тебе.");
    }

    @EventHandler
    public void onCollarUse(PlayerInteractEvent event) {
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isCollar(event.getItem())) return;
        event.setCancelled(true);
        openMainMenu(event.getPlayer(), 0);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) return;

        if (!holder.detail) {
            if (slot >= 0 && slot < holder.entries.size() && slot < 36) {
                openBeeMenu(player, holder.entries.get(slot));
                return;
            }
            if (slot >= 45 && slot <= 49) {
                Mode[] modes = {Mode.FOLLOW, Mode.STAY, Mode.TARGET, Mode.PEACEFUL, Mode.BERSERK};
                Mode selected = modes[slot - 45];
                if (selected == Mode.TARGET) {
                    waitingForArmyTarget.add(player.getUniqueId());
                    player.closeInventory();
                    player.sendMessage(ChatColor.YELLOW + "Напиши в чат ник игрока, на которого должна охотиться вся твоя армия. Напиши отмена для отмены.");
                } else {
                    setArmyMode(player.getUniqueId(), selected, "");
                    openMainMenu(player, holder.page);
                }
                return;
            }
            if (slot == 51 && holder.page > 0) openMainMenu(player, holder.page - 1);
            if (slot == 53) openMainMenu(player, holder.page + 1);
            return;
        }

        BeeProfile profile = profiles.get(holder.beeId);
        Bee bee = loadedBees.get(holder.beeId);
        if (profile == null || bee == null || !bee.isValid()) {
            player.sendMessage(ChatColor.RED + "Эта пчела сейчас не загружена. Подойди ближе к её чанку.");
            player.closeInventory();
            return;
        }
        if (slot == 40) {
            openMainMenu(player, 0);
            return;
        }
        if (slot == 10) setMode(player, holder.beeId, Mode.FOLLOW, "");
        else if (slot == 12) setMode(player, holder.beeId, Mode.STAY, "");
        else if (slot == 14) {
            pendingBeeTarget.put(player.getUniqueId(), holder.beeId);
            waitingForTarget.add(player.getUniqueId());
            player.closeInventory();
            player.sendMessage(ChatColor.YELLOW + "Напиши в чат ник игрока для этой пчелы. Напиши отмена для отмены.");
            return;
        } else if (slot == 16) setMode(player, holder.beeId, Mode.PEACEFUL, "");
        else if (slot == 28) setMode(player, holder.beeId, Mode.BERSERK, "");
        else if (slot == 31) {
            ItemStack cursor = event.getCursor();
            if (cursor != null && isHorseArmor(cursor.getType())) {
                profile.armor = cursor.getType().name();
                if (cursor.getAmount() <= 1) event.setCursor(new ItemStack(Material.AIR));
                else { cursor.setAmount(cursor.getAmount() - 1); event.setCursor(cursor); }
                applyArmorDisplay(bee, profile);
                saveProfiles();
                openBeeMenu(player, holder.beeId);
                return;
            }
            if (!profile.armor.isBlank()) {
                Material old = Material.matchMaterial(profile.armor);
                if (old != null) {
                    Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(old));
                    leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                }
                profile.armor = "";
                applyArmorDisplay(bee, profile);
                saveProfiles();
                openBeeMenu(player, holder.beeId);
                return;
            }
            player.sendMessage(ChatColor.YELLOW + "Положи конскую броню на курсор и нажми на этот слот.");
            return;
        }
        saveProfiles();
        openBeeMenu(player, holder.beeId);
    }

    @EventHandler
    public void onChatTarget(AsyncChatEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        boolean single = waitingForTarget.remove(playerId);
        boolean army = waitingForArmyTarget.remove(playerId);
        if (!single && !army) return;
        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;
            if (input.equalsIgnoreCase("отмена") || input.equalsIgnoreCase("cancel")) {
                player.sendMessage(ChatColor.GRAY + "Выбор цели отменён.");
                return;
            }
            Player target = Bukkit.getPlayerExact(input);
            if (target == null) {
                player.sendMessage(ChatColor.RED + "Игрок с таким ником сейчас не найден онлайн.");
                return;
            }
            if (army) {
                setArmyMode(playerId, Mode.TARGET, target.getName());
                player.sendMessage(ChatColor.GREEN + "Вся твоя армия получила цель: " + target.getName());
                openMainMenu(player, 0);
            } else {
                // Выбираем пчелу из последнего открытого меню по выбранному профилю-владельцу.
                UUID beeId = findMostRecentOwnedBee(playerId);
                if (beeId == null) {
                    player.sendMessage(ChatColor.RED + "Не удалось определить пчелу. Открой её меню и повтори.");
                    return;
                }
                setMode(player, beeId, Mode.TARGET, target.getName());
                openBeeMenu(player, beeId);
            }
        });
    }

    // A chat target selection is tied to a single pending bee, not to whichever bee happens to be nearest.
    private final Map<UUID, UUID> pendingBeeTarget = new HashMap<>();

    private UUID findMostRecentOwnedBee(UUID owner) {
        return pendingBeeTarget.remove(owner);
    }

    @EventHandler
    public void onBeeTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Bee bee)) return;
        BeeProfile profile = profiles.get(bee.getUniqueId());
        if (profile == null) return;
        LivingEntity target = event.getTarget();
        if (target == null) return;
        if (target instanceof Player player && player.getUniqueId().equals(profile.owner)) {
            event.setCancelled(true);
            return;
        }
        switch (profile.mode) {
            case FOLLOW, STAY, PEACEFUL -> event.setCancelled(true);
            case TARGET -> {
                if (!(target instanceof Player player) || !player.getName().equalsIgnoreCase(profile.targetName)) event.setCancelled(true);
            }
            case BERSERK -> {
                if (target instanceof Bee otherBee) {
                    String otherOwner = otherBee.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                    if (profile.owner.toString().equals(otherOwner)) event.setCancelled(true);
                }
            }
        }
    }

    @EventHandler
    public void onBeeDamageOwner(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Bee victim && event.getDamager() instanceof Player attacker) {
            BeeProfile victimProfile = profiles.get(victim.getUniqueId());
            if (victimProfile != null && !attacker.getUniqueId().equals(victimProfile.owner)) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getDamager() instanceof Bee bee) {
            BeeProfile profile = profiles.get(bee.getUniqueId());
            if (profile != null && event.getEntity() instanceof Player player && player.getUniqueId().equals(profile.owner)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onBeeDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Bee bee)) return;
        BeeProfile profile = profiles.remove(bee.getUniqueId());
        if (profile == null) return;
        if (!profile.armor.isBlank()) {
            Material armor = Material.matchMaterial(profile.armor);
            if (armor != null) event.getDrops().add(new ItemStack(armor));
        }
        loadedBees.remove(bee.getUniqueId());
        pendingBeeTarget.values().removeIf(id -> id.equals(bee.getUniqueId()));
        saveProfiles();
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) if (entity instanceof Bee bee) registerLoadedBee(bee);
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent event) {
        for (Entity entity : event.getChunk().getEntities()) if (entity instanceof Bee bee) loadedBees.remove(bee.getUniqueId());
    }

    private void registerLoadedBee(Bee bee) {
        String ownerText = bee.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (ownerText == null) return;
        UUID id = bee.getUniqueId();
        BeeProfile profile = profiles.get(id);
        if (profile == null) {
            try { profile = new BeeProfile(UUID.fromString(ownerText)); }
            catch (IllegalArgumentException ignored) { return; }
            String modeText = bee.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
            if (modeText != null) {
                try { profile.mode = Mode.valueOf(modeText); } catch (IllegalArgumentException ignored) { }
            }
            profile.targetName = valueOrEmpty(bee.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING));
            profile.armor = valueOrEmpty(bee.getPersistentDataContainer().get(armorKey, PersistentDataType.STRING));
            profiles.put(id, profile);
        }
        loadedBees.put(id, bee);
        if (!profile.armor.isBlank()) applyArmorDisplay(bee, profile);
    }

    private void tickBees() {
        for (Map.Entry<UUID, BeeProfile> entry : profiles.entrySet()) {
            Bee bee = loadedBees.get(entry.getKey());
            if (bee == null || !bee.isValid() || bee.isDead()) continue;
            BeeProfile profile = entry.getValue();
            Player owner = Bukkit.getPlayer(profile.owner);
            if (profile.mode == Mode.FOLLOW && owner != null && owner.isOnline() && owner.getWorld().equals(bee.getWorld())) {
                double distance = owner.getLocation().distance(bee.getLocation());
                if (distance > 28) {
                    bee.teleport(owner.getLocation().clone().add(1.2, 1.0, 0));
                } else if (distance > 3.5) {
                    Vector direction = owner.getLocation().toVector().subtract(bee.getLocation().toVector()).normalize().multiply(0.16);
                    bee.setVelocity(bee.getVelocity().multiply(0.55).add(direction));
                }
                bee.setTarget(null);
            } else if (profile.mode == Mode.STAY || profile.mode == Mode.PEACEFUL) {
                bee.setTarget(null);
            } else if (profile.mode == Mode.TARGET) {
                Player target = Bukkit.getPlayerExact(profile.targetName);
                if (target != null && target.getWorld().equals(bee.getWorld()) && target.getLocation().distanceSquared(bee.getLocation()) <= 625) {
                    bee.setTarget(target);
                } else bee.setTarget(null);
            } else if (profile.mode == Mode.BERSERK) {
                LivingEntity target = bee.getNearbyEntities(16, 16, 16).stream()
                        .filter(entity -> entity instanceof LivingEntity)
                        .filter(entity -> !entity.getUniqueId().equals(profile.owner))
                        .filter(entity -> {
                            if (!(entity instanceof Bee otherBee)) return true;
                            String otherOwner = otherBee.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
                            return !profile.owner.toString().equals(otherOwner);
                        })
                        .map(entity -> (LivingEntity) entity)
                        .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(bee.getLocation())))
                        .orElse(null);
                bee.setTarget(target);
            }
        }
    }

    private void setMode(Player player, UUID beeId, Mode mode, String target) {
        BeeProfile profile = profiles.get(beeId);
        if (profile == null || !profile.owner.equals(player.getUniqueId())) return;
        profile.mode = mode;
        profile.targetName = target == null ? "" : target;
        Bee bee = loadedBees.get(beeId);
        if (bee != null) writeProfileToBee(bee, profile);
        player.sendMessage(ChatColor.GREEN + "Режим пчелы: " + mode.title() + " — ON");
    }

    private void setArmyMode(UUID owner, Mode mode, String target) {
        for (Map.Entry<UUID, BeeProfile> entry : profiles.entrySet()) {
            BeeProfile profile = entry.getValue();
            if (!profile.owner.equals(owner)) continue;
            profile.mode = mode;
            profile.targetName = target == null ? "" : target;
            Bee bee = loadedBees.get(entry.getKey());
            if (bee != null) writeProfileToBee(bee, profile);
        }
        saveProfiles();
    }

    private void writeProfileToBee(Bee bee, BeeProfile profile) {
        PersistentDataContainer pdc = bee.getPersistentDataContainer();
        pdc.set(ownerKey, PersistentDataType.STRING, profile.owner.toString());
        pdc.set(modeKey, PersistentDataType.STRING, profile.mode.name());
        pdc.set(targetKey, PersistentDataType.STRING, profile.targetName);
        pdc.set(armorKey, PersistentDataType.STRING, profile.armor);
    }

    private void applyArmorDisplay(Bee bee, BeeProfile profile) {
        for (Entity passenger : new ArrayList<>(bee.getPassengers())) {
            if (passenger instanceof ItemDisplay display && display.getPersistentDataContainer().has(displayKey, PersistentDataType.STRING)) {
                display.remove();
            }
        }
        if (profile.armor.isBlank()) return;
        Material material = Material.matchMaterial(profile.armor);
        if (material == null) return;
        ItemDisplay display = bee.getWorld().spawn(bee.getLocation(), ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(material));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setPersistent(true);
            entity.getPersistentDataContainer().set(displayKey, PersistentDataType.STRING, bee.getUniqueId().toString());
        });
        bee.addPassenger(display);
    }

    private boolean isHorseArmor(Material material) {
        return material.name().endsWith("_HORSE_ARMOR");
    }

    private void openMainMenu(Player player, int requestedPage) {
        List<UUID> owned = profiles.entrySet().stream()
                .filter(e -> e.getValue().owner.equals(player.getUniqueId()))
                .map(Map.Entry::getKey)
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        int maxPage = Math.max(0, (owned.size() - 1) / 36);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        MenuHolder holder = new MenuHolder(false, page, null);
        Inventory inventory = Bukkit.createInventory(holder, 54, "§6BeeKingdom §8| §fМои пчёлы");
        holder.inventory = inventory;
        int start = page * 36;
        for (int i = start; i < Math.min(start + 36, owned.size()); i++) {
            UUID id = owned.get(i);
            BeeProfile profile = profiles.get(id);
            Bee bee = loadedBees.get(id);
            ItemStack icon = new ItemStack(Material.HONEYCOMB);
            ItemMeta meta = icon.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + "Пчела " + (i + 1));
            meta.setLore(List.of(ChatColor.GRAY + "Режим: " + profile.mode.title(),
                    ChatColor.GRAY + "Статус: " + (bee != null && bee.isValid() ? ChatColor.GREEN + "Загружена" : ChatColor.RED + "Не загружена"),
                    ChatColor.GRAY + "Броня: " + (profile.armor.isBlank() ? "нет" : profile.armor),
                    ChatColor.YELLOW + "Нажми, чтобы управлять"));
            icon.setItemMeta(meta);
            int slot = i - start;
            inventory.setItem(slot, icon);
            holder.entries.add(id);
        }
        inventory.setItem(45, armyModeItem(player.getUniqueId(), Material.FEATHER, "§aСледовать за мной", Mode.FOLLOW));
        inventory.setItem(46, armyModeItem(player.getUniqueId(), Material.OAK_SAPLING, "§eСвободный полёт", Mode.STAY));
        inventory.setItem(47, armyModeItem(player.getUniqueId(), Material.TARGET, "§cВ атаку: выбранная цель", Mode.TARGET));
        inventory.setItem(48, armyModeItem(player.getUniqueId(), Material.WHITE_WOOL, "§bМирный режим", Mode.PEACEFUL));
        inventory.setItem(49, armyModeItem(player.getUniqueId(), Material.BLAZE_POWDER, "§4Я конченный, бегите!", Mode.BERSERK));
        if (page > 0) inventory.setItem(51, menuItem(Material.ARROW, "§fПредыдущая страница"));
        inventory.setItem(52, menuItem(Material.PAPER, "§eСтраница " + (page + 1) + "/" + (maxPage + 1), "§7Всего пчёл: " + owned.size()));
        if (page < maxPage) inventory.setItem(53, menuItem(Material.ARROW, "§fСледующая страница"));
        player.openInventory(inventory);
    }

    private void openBeeMenu(Player player, UUID beeId) {
        BeeProfile profile = profiles.get(beeId);
        if (profile == null || !profile.owner.equals(player.getUniqueId())) return;
        MenuHolder holder = new MenuHolder(true, 0, beeId);
        Inventory inventory = Bukkit.createInventory(holder, 45, "§6BeeKingdom §8| §fПчела");
        holder.inventory = inventory;
        inventory.setItem(10, modeItem(Material.FEATHER, "§aСледовать за игроком", Mode.FOLLOW, profile));
        inventory.setItem(12, modeItem(Material.OAK_SAPLING, "§eСвободный полёт", Mode.STAY, profile));
        inventory.setItem(14, modeItem(Material.TARGET, "§cВ атаку", Mode.TARGET, profile, "§7Нажми и введи ник онлайн-игрока"));
        inventory.setItem(16, modeItem(Material.WHITE_WOOL, "§bМирный режим", Mode.PEACEFUL, profile));
        inventory.setItem(28, modeItem(Material.BLAZE_POWDER, "§4Я конченный, бегите!", Mode.BERSERK, profile));
        List<String> armorLore = new ArrayList<>();
        armorLore.add("§7Только конская броня");
        armorLore.add(profile.armor.isBlank() ? "§cСейчас без брони" : "§aНадето: " + profile.armor);
        armorLore.add("§eПоложи броню на курсор и нажми");
        armorLore.add("§7Повторный клик без предмета снимет броню");
        inventory.setItem(31, namedItem(Material.DIAMOND_HORSE_ARMOR, "§bЭкипировка пчелы", armorLore));
        inventory.setItem(40, menuItem(Material.ARROW, "§fНазад к списку пчёл"));
        player.openInventory(inventory);
    }

    private ItemStack armyModeItem(UUID owner, Material material, String name, Mode mode) {
        List<BeeProfile> owned = profiles.values().stream().filter(p -> p.owner.equals(owner)).toList();
        boolean active = !owned.isEmpty() && owned.stream().allMatch(p -> p.mode == mode);
        return namedItem(material, name, List.of(active ? "§aON" : "§cOFF", "§7Нажми, чтобы применить ко всей армии"));
    }

    private ItemStack modeItem(Material material, String name, Mode mode, BeeProfile profile, String... extra) {
        List<String> lore = new ArrayList<>();
        lore.add(profile.mode == mode ? "§aON" : "§cOFF");
        lore.add("§7Нажми, чтобы включить этот режим");
        if (mode == Mode.TARGET && !profile.targetName.isBlank()) lore.add("§7Цель: " + profile.targetName);
        lore.addAll(List.of(extra));
        return namedItem(material, name, lore);
    }

    private ItemStack menuItem(Material material, String name, String... lore) {
        return namedItem(material, name, List.of(lore));
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void loadProfiles() {
        profiles.clear();
        if (!getConfig().isConfigurationSection("bees")) return;
        for (String key : getConfig().getConfigurationSection("bees").getKeys(false)) {
            try {
                UUID beeId = UUID.fromString(key);
                String ownerText = getConfig().getString("bees." + key + ".owner", "");
                BeeProfile profile = new BeeProfile(UUID.fromString(ownerText));
                try { profile.mode = Mode.valueOf(getConfig().getString("bees." + key + ".mode", "FOLLOW")); }
                catch (IllegalArgumentException ignored) { profile.mode = Mode.FOLLOW; }
                profile.targetName = getConfig().getString("bees." + key + ".target", "");
                profile.armor = getConfig().getString("bees." + key + ".armor", "");
                profiles.put(beeId, profile);
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Пропущена некорректная запись пчелы: " + key);
            }
        }
    }

    private void saveProfiles() {
        getConfig().set("bees", null);
        for (Map.Entry<UUID, BeeProfile> entry : profiles.entrySet()) {
            String path = "bees." + entry.getKey();
            BeeProfile profile = entry.getValue();
            getConfig().set(path + ".owner", profile.owner.toString());
            getConfig().set(path + ".mode", profile.mode.name());
            getConfig().set(path + ".target", profile.targetName);
            getConfig().set(path + ".armor", profile.armor);
        }
        saveConfig();
    }

    private String valueOrEmpty(String value) { return value == null ? "" : value; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эта команда доступна только игроку.");
            return true;
        }
        if (!player.hasPermission("beekdom.use")) {
            player.sendMessage(ChatColor.RED + "У тебя нет разрешения beekdom.use.");
            return true;
        }
        openMainMenu(player, 0);
        return true;
    }
}
