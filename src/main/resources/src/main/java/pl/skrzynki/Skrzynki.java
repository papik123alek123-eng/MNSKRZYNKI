package pl.skrzynki;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.*;

public class Skrzynki extends JavaPlugin implements Listener {

    NamespacedKey key;
    File file;
    YamlConfiguration cfg;
    Set<Block> busy = new HashSet<>();
    Set<Entity> ents = new HashSet<>();
    Random rnd = new Random();

    static class Edit implements InventoryHolder {
        String name;
        Inventory inv;
        public @NotNull Inventory getInventory() { return inv; }
    }

    @Override
    public void onEnable() {
        key = new NamespacedKey(this, "crate");
        getDataFolder().mkdirs();
        file = new File(getDataFolder(), "skrzynki.yml");
        cfg = YamlConfiguration.loadConfiguration(file);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        for (Entity e : ents) e.remove();
        for (Block b : busy) b.setType(Material.AIR);
    }

    void save() {
        try { cfg.save(file); } catch (Exception e) { e.printStackTrace(); }
    }

    ItemStack crate(String n) {
        ItemStack it = new ItemStack(Material.CHEST);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName("§6§lSkrzynka §e" + n);
        m.setLore(List.of("§7Kliknij PPM na ziemie, aby otworzyc"));
        m.getPersistentDataContainer().set(key, PersistentDataType.STRING, n);
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    String nameOf(ItemStack i) {
        return i == null || !i.hasItemMeta() ? null
                : i.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    List<ItemStack> items(String n) {
        List<ItemStack> l = new ArrayList<>();
        for (Object o : cfg.getList("crates." + n + ".items", new ArrayList<>()))
            if (o instanceof ItemStack i) l.add(i);
        return l;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, @NotNull String[] a) {
        String cmd = c.getName();

        if (cmd.equals("skrzynkadaj")) {
            Player t = a.length > 1 ? Bukkit.getPlayerExact(a[0]) : null;
            if (t == null || !cfg.contains("crates." + a[1].toLowerCase())) {
                s.sendMessage("§cUzycie: /skrzynkadaj <gracz> <nazwa> (gracz online, skrzynka musi istniec)");
                return true;
            }
            t.getInventory().addItem(crate(a[1].toLowerCase()));
            s.sendMessage("§aDano skrzynke.");
            return true;
        }

        if (!(s instanceof Player p)) return true;
        if (a.length < 1) { p.sendMessage("§cPodaj nazwe skrzynki."); return true; }
        String n = a[0].toLowerCase();
        boolean ex = cfg.contains("crates." + n);

        switch (cmd) {
            case "skrzynkastworz" -> {
                if (ex) { p.sendMessage("§cTaka skrzynka juz istnieje."); return true; }
                cfg.set("crates." + n + ".items", new ArrayList<ItemStack>());
                save();
                p.getInventory().addItem(crate(n));
                p.sendMessage("§aStworzono. Ustaw drop: /skrzynkaedytuj " + n);
            }
            case "skrzynkaedytuj" -> {
                if (!ex) { p.sendMessage("§cNie ma takiej skrzynki."); return true; }
                Edit h = new Edit();
                h.name = n;
                h.inv = Bukkit.createInventory(h, 54, "Drop: " + n);
                for (ItemStack i : items(n)) h.inv.addItem(i);
                p.openInventory(h.inv);
                p.sendMessage("§7Wloz przedmioty i zamknij okno, aby zapisac.");
            }
            case "skrzynkausun" -> {
                if (!ex) { p.sendMessage("§cNie ma takiej skrzynki."); return true; }
                cfg.set("crates." + n, null);
                save();
                p.sendMessage("§aUsunieto skrzynke.");
            }
        }
        return true;
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Edit h)) return;
        List<ItemStack> l = new ArrayList<>();
        for (ItemStack i : e.getInventory().getContents()) if (i != null) l.add(i);
        cfg.set("crates." + h.name + ".items", l);
        save();
        e.getPlayer().sendMessage("§aZapisano drop (" + l.size() + " przedmiotow).");
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (nameOf(e.getItemInHand()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (busy.contains(e.getBlock())) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(PlayerInteractEvent e) {
        Block cb = e.getClickedBlock();
        if (cb != null && busy.contains(cb)) { e.setCancelled(true); return; }
        String n = nameOf(e.getItem());
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || n == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        List<ItemStack> list = items(n);
        Block b = cb.getRelative(e.getBlockFace());
        if (list.isEmpty() || !b.getType().isAir()) {
            p.sendMessage("§cSkrzynka nie ma dropu albo brak miejsca.");
            return;
        }
        e.getItem().setAmount(e.getItem().getAmount() - 1);
        spin(p, b, list);
    }

    void spin(Player p, Block b, List<ItemStack> list) {
        busy.add(b);
        b.setType(Material.CHEST);
        World w = b.getWorld();
        Location base = b.getLocation().add(0.5, 1, 0.5);
        ItemStack reward = list.get(rnd.nextInt(list.size())).clone();
        ItemDisplay d = w.spawn(base, ItemDisplay.class, x -> x.setItemStack(list.get(0)));
        ents.add(d);
        w.playSound(base, Sound.BLOCK_CHEST_OPEN, 1, 1);

        new BukkitRunnable() {
            int t = 0;

            public void run() {
                t++;
                Location l = base.clone().add(0, Math.min(t, 60) / 30.0, 0);
                l.setYaw(t * 12);
                d.teleport(l);

                double a = t * 0.5;
                w.spawnParticle(Particle.END_ROD, l.clone().add(Math.cos(a), 0, Math.sin(a)), 1, 0, 0, 0, 0);
                w.spawnParticle(Particle.END_ROD, l.clone().add(-Math.cos(a), 0, -Math.sin(a)), 1, 0, 0, 0, 0);

                if (t < 60 && t % 5 == 0) {
                    d.setItemStack(list.get(rnd.nextInt(list.size())));
                    w.playSound(l, Sound.BLOCK_NOTE_BLOCK_CHIME, 1, 1.5f);
                }
                if (t == 60) {
                    d.setItemStack(reward);
                    w.playSound(l, Sound.ENTITY_PLAYER_LEVELUP, 1, 1);
                    w.spawnParticle(Particle.TOTEM_OF_UNDYING, l, 50, 0.3, 0.3, 0.3, 0.3);
                }
                if (t >= 90) {
                    cancel();
                    d.remove();
                    ents.remove(d);
                    b.setType(Material.AIR);
                    busy.remove(b);
                    p.getInventory().addItem(reward).values().forEach(r -> w.dropItemNaturally(base, r));
                    p.sendMessage("§6Wylosowales: §e" + reward.getAmount() + "x " + reward.getType());
                }
            }
        }.runTaskTimer(this, 0, 1);
    }
}
