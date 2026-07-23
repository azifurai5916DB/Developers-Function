package com.developersfunction.df;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

final class DfCommand implements CommandExecutor, TabCompleter, Listener {
    private static final String PREFIX = ChatColor.AQUA + "[DF] " + ChatColor.RESET;
    private static final List<String> PUBLIC_COMMANDS = List.of(
            "help", "lag", "ping", "worldinfo", "biome", "chunkinfo", "chunkloader",
            "entitycount", "nearentity", "iteminfo", "lightcheck", "scoreboard", "deathlog", "measure");
    private static final List<String> ADMIN_COMMANDS = List.of(
            "op", "timer", "countdown", "broadcast", "lockchat", "clearitem",
            "inventorycheck", "watching", "locate", "servercheck", "profiler");

    private final JavaPlugin plugin;
    private final OperatorStore operators;
    private final DeathLogStore deathLogs;
    private final Map<UUID, Long> clearConfirmUntil = new HashMap<>();
    private final Map<UUID, WatchState> watching = new HashMap<>();
    private final Map<UUID, MeasureState> measures = new HashMap<>();
    private final Map<UUID, Inventory> readonlyInventories = new HashMap<>();
    private final Map<UUID, BossBar> timers = new HashMap<>();
    private boolean chatLocked;
    private boolean deathScoreboardEnabled;

    DfCommand(JavaPlugin plugin, OperatorStore operators, DeathLogStore deathLogs) {
        this.plugin = plugin;
        this.operators = operators;
        this.deathLogs = deathLogs;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (sub) {
                case "help" -> help(sender, args);
                case "lag" -> lag(sender);
                case "ping" -> ping(sender);
                case "worldinfo" -> playerOnly(sender, this::worldInfo);
                case "biome" -> playerOnly(sender, this::biome);
                case "chunkinfo" -> playerOnly(sender, this::chunkInfo);
                case "chunkloader" -> chunkLoader(sender);
                case "entitycount" -> entityCount(sender);
                case "nearentity" -> playerOnly(sender, this::nearEntity);
                case "iteminfo" -> playerOnly(sender, this::itemInfo);
                case "lightcheck" -> playerOnly(sender, this::lightCheck);
                case "scoreboard" -> scoreboard(sender, args);
                case "deathlog" -> deathLog(sender, args);
                case "measure" -> playerOnly(sender, player -> measure(player, args));
                case "op", "timer", "countdown", "broadcast", "lockchat", "clearitem",
                        "inventorycheck", "watching", "locate", "servercheck", "profiler" -> {
                    if (!isDfAdmin(sender)) {
                        deny(sender);
                        return true;
                    }
                    admin(sender, sub, args);
                }
                default -> sender.sendMessage(PREFIX + ChatColor.RED + "不明なコマンドです。/df help を確認してください。");
            }
        } catch (RuntimeException e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "実行中にエラーが発生しました: " + e.getMessage());
            plugin.getLogger().warning("Command failed: " + e.getMessage());
        }
        return true;
    }

    private void admin(CommandSender sender, String sub, String[] args) {
        switch (sub) {
            case "op" -> op(sender, args);
            case "timer" -> timer(sender, args);
            case "countdown" -> countdown(sender, args);
            case "broadcast" -> broadcast(sender, args);
            case "lockchat" -> lockChat(sender);
            case "clearitem" -> clearItem(sender, args);
            case "inventorycheck" -> inventoryCheck(sender, args);
            case "watching" -> watching(sender, args);
            case "locate" -> locate(sender, args);
            case "servercheck" -> serverCheck(sender);
            case "profiler" -> profiler(sender);
            default -> sender.sendMessage(PREFIX + ChatColor.RED + "未実装です。");
        }
    }

    private void help(CommandSender sender, String[] args) {
        List<String> entries = new ArrayList<>(PUBLIC_COMMANDS);
        if (isDfAdmin(sender)) {
            entries.addAll(ADMIN_COMMANDS);
        }
        if (args.length >= 2 && !isInteger(args[1])) {
            String key = args[1].toLowerCase(Locale.ROOT);
            List<String> hits = entries.stream().filter(cmd -> cmd.contains(key)).toList();
            sender.sendMessage(PREFIX + ChatColor.YELLOW + "検索: " + key);
            hits.forEach(cmd -> sender.sendMessage(ChatColor.GRAY + "/df " + cmd));
            if (hits.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "一致するコマンドはありません。");
            }
            return;
        }
        int page = args.length >= 2 ? Math.max(1, parseInt(args[1], 1)) : 1;
        int pageSize = 8;
        int max = Math.max(1, (int) Math.ceil(entries.size() / (double) pageSize));
        page = Math.min(page, max);
        sender.sendMessage(PREFIX + ChatColor.GOLD + "Help " + page + "/" + max);
        entries.stream().skip((long) (page - 1) * pageSize).limit(pageSize)
                .forEach(cmd -> sender.sendMessage(ChatColor.GRAY + "/df " + cmd));
    }

    private void op(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "/df op add <MCID> | delete <MCID> | list");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "add" -> {
                if (args.length < 3) {
                    sender.sendMessage(PREFIX + "/df op add <MCID>");
                    return;
                }
                OfflinePlayer player = Bukkit.getOfflinePlayer(args[2]);
                operators.add(player);
                sender.sendMessage(PREFIX + args[2] + " をDF管理者に追加しました。");
            }
            case "delete" -> {
                if (args.length < 3) {
                    sender.sendMessage(PREFIX + "/df op delete <MCID>");
                    return;
                }
                operators.remove(Bukkit.getOfflinePlayer(args[2]));
                sender.sendMessage(PREFIX + args[2] + " をDF管理者から削除しました。");
            }
            case "list" -> sender.sendMessage(PREFIX + "DF管理者: " + String.join(", ", operators.listNames()));
            default -> sender.sendMessage(PREFIX + "/df op add <MCID> | delete <MCID> | list");
        }
    }

    private void lag(CommandSender sender) {
        int entities = Bukkit.getWorlds().stream().mapToInt(world -> world.getEntities().size()).sum();
        int mobs = Bukkit.getWorlds().stream().mapToInt(world -> (int) world.getEntities().stream().filter(Mob.class::isInstance).count()).sum();
        int chunks = Bukkit.getWorlds().stream().mapToInt(world -> world.getLoadedChunks().length).sum();
        double tps = currentTps();
        double mspt = currentMspt();
        sender.sendMessage(PREFIX + colorForTps(tps) + "TPS: " + format(tps));
        sender.sendMessage(colorForMspt(mspt) + "MSPT: " + format(mspt));
        sender.sendMessage(ChatColor.GRAY + "Entities: " + entities + " / Mobs: " + mobs);
        sender.sendMessage(ChatColor.GRAY + "Loaded Chunks: " + chunks + " / Players: " + Bukkit.getOnlinePlayers().size());
    }

    private void serverCheck(CommandSender sender) {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        double memoryRate = used / (double) runtime.maxMemory();
        int entities = Bukkit.getWorlds().stream().mapToInt(world -> world.getEntities().size()).sum();
        int chunks = Bukkit.getWorlds().stream().mapToInt(world -> world.getLoadedChunks().length).sum();
        double tps = currentTps();
        double mspt = currentMspt();
        int score = 100;
        score -= penalty(20.0 - tps, 4);
        score -= penalty(mspt - 50.0, 1);
        score -= penalty(memoryRate * 100.0 - 80.0, 1);
        score -= penalty(entities - 1000.0, 0.02);
        score -= penalty(chunks - 2000.0, 0.01);
        score = Math.max(0, Math.min(100, score));
        sender.sendMessage(PREFIX + ChatColor.GOLD + "Server Check");
        sender.sendMessage(ChatColor.GRAY + "TPS: " + format(tps) + " / MSPT: " + format(mspt));
        sender.sendMessage(ChatColor.GRAY + "Memory: " + (int) (memoryRate * 100) + "% / Entities: " + entities + " / Chunks: " + chunks);
        sender.sendMessage(ChatColor.GREEN + "Overall Score: " + score + "/100");
    }

    private void timer(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + "/df timer <分> <色>");
            return;
        }
        int minutes = parseInt(args[1], -1);
        if (minutes <= 0) {
            sender.sendMessage(PREFIX + ChatColor.RED + "分は1以上で指定してください。");
            return;
        }
        BarColor color;
        try {
            color = BarColor.valueOf(args[2].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "色は red/blue/green/yellow/purple/pink/white から指定してください。");
            return;
        }
        BossBar bar = Bukkit.createBossBar("残り時間: " + formatSeconds(minutes * 60), color, BarStyle.SOLID);
        Bukkit.getOnlinePlayers().forEach(bar::addPlayer);
        UUID id = UUID.randomUUID();
        timers.put(id, bar);
        BukkitTask task = new TimerTask(minutes * 60, bar, id).start();
        sender.sendMessage(PREFIX + "タイマーを開始しました。Task #" + task.getTaskId());
    }

    private final class TimerTask extends BukkitRunnable {
        private int remaining;
        private final int total;
        private final BossBar bar;
        private final UUID id;

        private TimerTask(int total, BossBar bar, UUID id) {
            this.remaining = total;
            this.total = total;
            this.bar = bar;
            this.id = id;
        }

        private BukkitTask start() {
            return runTaskTimer(plugin, 0L, 20L);
        }

        @Override
        public void run() {
            if (!timers.containsKey(id)) {
                cancel();
                return;
            }
            Bukkit.getOnlinePlayers().forEach(player -> {
                if (!bar.getPlayers().contains(player)) {
                    bar.addPlayer(player);
                }
            });
            if (remaining <= 0) {
                bar.setTitle("タイムアップ！");
                bar.setProgress(0.0);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    bar.removeAll();
                    timers.remove(id);
                }, 100L);
                timers.remove(id);
                cancel();
                return;
            }
            bar.setTitle("残り時間: " + formatSeconds(remaining));
            bar.setProgress(Math.max(0.0, remaining / (double) total));
            remaining--;
        }
    }

    private void scoreboard(CommandSender sender, String[] args) {
        if (args.length < 2 || !"deathcount".equalsIgnoreCase(args[1])) {
            sender.sendMessage(PREFIX + "/df scoreboard deathcount");
            return;
        }
        deathScoreboardEnabled = !deathScoreboardEnabled;
        if (!deathScoreboardEnabled) {
            Bukkit.getOnlinePlayers().forEach(player -> player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()));
            sender.sendMessage(PREFIX + "デス数スコアボードをOFFにしました。");
            return;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "ScoreboardManagerを取得できません。");
            return;
        }
        Scoreboard board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective("df_deaths", "deathCount", ChatColor.RED + "Deaths");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        Bukkit.getOnlinePlayers().forEach(player -> player.setScoreboard(board));
        sender.sendMessage(PREFIX + "デス数スコアボードをONにしました。");
    }

    private void inventoryCheck(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(PREFIX + "プレイヤーのみ実行できます。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "/df inventorycheck <MCID>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "対象プレイヤーが見つかりません。");
            return;
        }
        Inventory inventory = Bukkit.createInventory(null, 54, "DF Inventory: " + target.getName());
        ItemStack[] storage = target.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++) {
            inventory.setItem(i, storage[i]);
        }
        ItemStack[] armor = target.getInventory().getArmorContents();
        for (int i = 0; i < armor.length; i++) {
            inventory.setItem(45 + i, armor[i]);
        }
        inventory.setItem(50, target.getInventory().getItemInOffHand());
        readonlyInventories.put(viewer.getUniqueId(), inventory);
        viewer.openInventory(inventory);
    }

    private void watching(CommandSender sender, String[] args) {
        if (!(sender instanceof Player watcher)) {
            sender.sendMessage(PREFIX + "プレイヤーのみ実行できます。");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "/df watching <MCID>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "対象プレイヤーが見つかりません。");
            return;
        }
        watching.put(watcher.getUniqueId(), new WatchState(watcher.getLocation(), watcher.getGameMode()));
        watcher.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false));
        watcher.setGameMode(GameMode.SPECTATOR);
        watcher.setSpectatorTarget(target);
        watcher.sendMessage(PREFIX + target.getName() + " の観戦を開始しました。しゃがみで解除します。");
    }

    private void locate(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(PREFIX + "/df locate <MCID>");
            return;
        }
        if (target == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "対象プレイヤーが見つかりません。");
            return;
        }
        Location loc = target.getLocation();
        sender.sendMessage(PREFIX + target.getName());
        sender.sendMessage(ChatColor.GRAY + "X: " + loc.getBlockX());
        sender.sendMessage(ChatColor.GRAY + "Y: " + loc.getBlockY());
        sender.sendMessage(ChatColor.GRAY + "Z: " + loc.getBlockZ());
        sender.sendMessage(ChatColor.GRAY + "ディメンション: " + loc.getWorld().getName());
    }

    private void countdown(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "/df countdown <秒>");
            return;
        }
        int seconds = parseInt(args[1], -1);
        if (seconds <= 0) {
            sender.sendMessage(PREFIX + ChatColor.RED + "秒は1以上で指定してください。");
            return;
        }
        final int[] remaining = {seconds};
        new BukkitRunnable() {
            @Override
            public void run() {
                if (remaining[0] <= 0) {
                    Bukkit.getOnlinePlayers().forEach(player -> player.sendTitle(ChatColor.GOLD + "START!", "", 0, 30, 10));
                    cancel();
                    return;
                }
                String text = ChatColor.YELLOW + String.valueOf(remaining[0]);
                Bukkit.getOnlinePlayers().forEach(player -> player.sendTitle(text, "", 0, 20, 0));
                remaining[0]--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
        sender.sendMessage(PREFIX + "カウントダウンを開始しました。");
    }

    private void broadcast(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + "/df broadcast <内容>");
            return;
        }
        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Bukkit.broadcastMessage(ChatColor.AQUA + "[DF]");
        Bukkit.broadcastMessage(ChatColor.WHITE + message);
    }

    private void lockChat(CommandSender sender) {
        chatLocked = !chatLocked;
        Bukkit.broadcastMessage(PREFIX + (chatLocked ? "チャットをロックしました。" : "チャットロックを解除しました。"));
    }

    private void clearItem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "プレイヤーのみ実行できます。");
            return;
        }
        if (args.length >= 2 && "confirm".equalsIgnoreCase(args[1])) {
            long until = clearConfirmUntil.getOrDefault(player.getUniqueId(), 0L);
            if (System.currentTimeMillis() > until) {
                sender.sendMessage(PREFIX + ChatColor.RED + "確認期限が切れています。/df clearitem を再実行してください。");
                return;
            }
            int removed = 0;
            for (World world : Bukkit.getWorlds()) {
                for (Item item : world.getEntitiesByClass(Item.class)) {
                    item.remove();
                    removed++;
                }
            }
            clearConfirmUntil.remove(player.getUniqueId());
            Bukkit.broadcastMessage(PREFIX + "地面のアイテムを " + removed + " 個削除しました。");
            return;
        }
        int seconds = plugin.getConfig().getInt("settings.clearitem-confirm-seconds", 30);
        clearConfirmUntil.put(player.getUniqueId(), System.currentTimeMillis() + seconds * 1000L);
        sender.sendMessage(PREFIX + ChatColor.YELLOW + "地面のアイテムを削除するには /df clearitem confirm を実行してください。");
    }

    private void entityCount(CommandSender sender) {
        Map<EntityType, Long> counts = Bukkit.getWorlds().stream()
                .flatMap(world -> world.getEntities().stream())
                .collect(Collectors.groupingBy(Entity::getType, Collectors.counting()));
        sender.sendMessage(PREFIX + ChatColor.GOLD + "Entity Count");
        counts.entrySet().stream()
                .sorted(Map.Entry.<EntityType, Long>comparingByValue().reversed())
                .limit(20)
                .forEach(entry -> sender.sendMessage(ChatColor.GRAY + entry.getKey().name() + ": " + entry.getValue()));
    }

    private void chunkInfo(Player player) {
        Chunk chunk = player.getLocation().getChunk();
        senderLines(player, "Chunk X: " + chunk.getX(), "Chunk Z: " + chunk.getZ(),
                "Entity数: " + chunk.getEntities().length, "TileEntity数: " + chunk.getTileEntities().length);
    }

    private void profiler(CommandSender sender) {
        int mobs = Bukkit.getWorlds().stream().mapToInt(world -> (int) world.getEntities().stream().filter(Mob.class::isInstance).count()).sum();
        int chunks = Bukkit.getWorlds().stream().mapToInt(world -> world.getLoadedChunks().length).sum();
        int items = Bukkit.getWorlds().stream().mapToInt(world -> world.getEntitiesByClass(Item.class).size()).sum();
        double mspt = Math.max(1.0, currentMspt());
        int entityAi = clamp((int) Math.round(mobs * 100.0 / Math.max(1.0, mobs + chunks + items)));
        int chunkLoading = clamp((int) Math.round(chunks * 100.0 / Math.max(1.0, mobs + chunks + items)));
        int hopper = clamp((int) Math.round(items * 50.0 / Math.max(1.0, mobs + chunks + items)));
        int redstone = clamp((int) Math.round(Math.min(25.0, Math.max(5.0, mspt - 45.0))));
        int other = Math.max(0, 100 - entityAi - chunkLoading - hopper - redstone);
        sender.sendMessage(PREFIX + ChatColor.GOLD + "Profiler (API estimate)");
        sender.sendMessage(ChatColor.GRAY + "Entity AI      " + entityAi + "%");
        sender.sendMessage(ChatColor.GRAY + "Redstone       " + redstone + "%");
        sender.sendMessage(ChatColor.GRAY + "Hopper         " + hopper + "%");
        sender.sendMessage(ChatColor.GRAY + "Chunk Loading  " + chunkLoading + "%");
        sender.sendMessage(ChatColor.GRAY + "Other          " + other + "%");
    }

    private void ping(CommandSender sender) {
        Bukkit.getOnlinePlayers().stream()
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(player -> sender.sendMessage(ChatColor.GRAY + player.getName() + " " + player.getPing() + "ms"));
    }

    private void worldInfo(Player player) {
        World world = player.getWorld();
        WorldBorder border = world.getWorldBorder();
        Difficulty difficulty = world.getDifficulty();
        senderLines(player,
                "ワールド名: " + world.getName(),
                "シード値: " + world.getSeed(),
                "難易度: " + difficulty.name(),
                "ワールドボーダー: " + (int) border.getSize(),
                "時間: " + world.getTime(),
                "天候: " + (world.hasStorm() ? "雨/雷雨" : "晴れ"));
    }

    private void deathLog(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!isDfAdmin(sender)) {
                deny(sender);
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(PREFIX + "/df deathlog <MCID>");
            return;
        }
        if (target == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "オンラインの対象プレイヤーが見つかりません。");
            return;
        }
        List<String> logs = deathLogs.read(target.getUniqueId());
        sender.sendMessage(PREFIX + target.getName() + " の死亡履歴");
        if (logs.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "履歴はありません。");
            return;
        }
        logs.stream().skip(Math.max(0, logs.size() - 10)).forEach(line -> sender.sendMessage(ChatColor.GRAY + line));
    }

    private void itemInfo(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            player.sendMessage(PREFIX + ChatColor.RED + "手にアイテムを持ってください。");
            return;
        }
        int maxDurability = item.getType().getMaxDurability();
        String durability = "なし";
        if (maxDurability > 0 && item.getItemMeta() instanceof Damageable damageable) {
            durability = (maxDurability - damageable.getDamage()) + "/" + maxDurability;
        }
        String enchants = item.getEnchantments().entrySet().stream()
                .map(entry -> entry.getKey().getKey().getKey() + " " + entry.getValue())
                .collect(Collectors.joining(", "));
        if (enchants.isBlank()) {
            enchants = "なし";
        }
        senderLines(player,
                "アイテム名: " + item.getType().name(),
                "耐久値: " + durability,
                "エンチャント: " + enchants,
                "NBT有無: " + (item.hasItemMeta() ? "あり" : "なし"));
    }

    @SuppressWarnings("deprecation")
    private void biome(Player player) {
        player.sendMessage(PREFIX + "Biome: " + player.getLocation().getBlock().getBiome().getKey().asString());
    }

    private void chunkLoader(CommandSender sender) {
        Bukkit.getWorlds().forEach(world -> sender.sendMessage(ChatColor.GRAY + world.getName() + ": " + world.getLoadedChunks().length + " chunks"));
    }

    private void nearEntity(Player player) {
        int radius = plugin.getConfig().getInt("settings.nearentity-radius", 16);
        player.getNearbyEntities(radius, radius, radius).stream()
                .sorted(Comparator.comparing(entity -> entity.getType().name()))
                .limit(50)
                .forEach(entity -> player.sendMessage(ChatColor.GRAY + entity.getType().name() + " @ "
                        + entity.getLocation().getBlockX() + ","
                        + entity.getLocation().getBlockY() + ","
                        + entity.getLocation().getBlockZ()));
    }

    private void lightCheck(Player player) {
        int radius = plugin.getConfig().getInt("settings.lightcheck-radius", 16);
        int duration = plugin.getConfig().getInt("settings.lightcheck-duration-seconds", 30);
        World world = player.getWorld();
        Location center = player.getLocation();
        List<Location> spots = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                Location loc = center.clone().add(x, 0, z);
                int y = world.getHighestBlockYAt(loc);
                Location spawn = new Location(world, loc.getBlockX() + 0.5, y + 1.1, loc.getBlockZ() + 0.5);
                if (spawn.getBlock().getLightLevel() <= 7) {
                    spots.add(spawn);
                }
            }
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> spots.forEach(loc ->
                world.spawnParticle(Particle.FLAME, loc, 1, 0.1, 0.05, 0.1, 0.0)), 0L, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, task::cancel, duration * 20L);
        player.sendMessage(PREFIX + spots.size() + " 箇所を " + duration + " 秒間表示します。");
    }

    private void measure(Player player, String[] args) {
        MeasureState state = measures.computeIfAbsent(player.getUniqueId(), ignored -> new MeasureState());
        if (args.length >= 2 && "pos1".equalsIgnoreCase(args[1])) {
            state.pos1 = player.getLocation();
            player.sendMessage(PREFIX + "pos1 を設定しました。");
            return;
        }
        if (args.length >= 2 && "pos2".equalsIgnoreCase(args[1])) {
            state.pos2 = player.getLocation();
            player.sendMessage(PREFIX + "pos2 を設定しました。");
            return;
        }
        if (state.pos1 == null || state.pos2 == null || !Objects.equals(state.pos1.getWorld(), state.pos2.getWorld())) {
            player.sendMessage(PREFIX + ChatColor.RED + "/df measure pos1 と /df measure pos2 を同じワールドで設定してください。");
            return;
        }
        int dx = Math.abs(state.pos1.getBlockX() - state.pos2.getBlockX()) + 1;
        int dy = Math.abs(state.pos1.getBlockY() - state.pos2.getBlockY()) + 1;
        int dz = Math.abs(state.pos1.getBlockZ() - state.pos2.getBlockZ()) + 1;
        double straight = state.pos1.distance(state.pos2);
        senderLines(player,
                "X距離: " + dx,
                "Y距離: " + dy,
                "Z距離: " + dz,
                "直線距離: " + format(straight),
                "体積: " + (dx * dy * dz));
    }

    @EventHandler
    void onChat(AsyncPlayerChatEvent event) {
        if (chatLocked && !isDfAdmin(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(PREFIX + ChatColor.RED + "現在チャットはロックされています。");
        }
    }

    @EventHandler
    void onSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && watching.containsKey(event.getPlayer().getUniqueId())) {
            stopWatching(event.getPlayer());
        }
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        watching.remove(event.getPlayer().getUniqueId());
        readonlyInventories.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && readonlyInventories.get(player.getUniqueId()) == event.getInventory()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && readonlyInventories.get(player.getUniqueId()) == event.getInventory()) {
            event.setCancelled(true);
        }
    }

    private void stopWatching(Player watcher) {
        WatchState state = watching.remove(watcher.getUniqueId());
        if (state == null) {
            return;
        }
        watcher.setSpectatorTarget(null);
        watcher.setGameMode(state.gameMode);
        watcher.teleport(state.location);
        watcher.removePotionEffect(PotionEffectType.INVISIBILITY);
        watcher.sendMessage(PREFIX + "観戦を解除しました。");
    }

    void shutdown() {
        for (BossBar bar : timers.values()) {
            bar.removeAll();
        }
        timers.clear();
        new ArrayList<>(watching.keySet()).forEach(uuid -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                stopWatching(player);
            }
        });
    }

    private boolean isDfAdmin(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.isOp() || operators.isOperator(player.getUniqueId());
        }
        return true;
    }

    private void playerOnly(CommandSender sender, PlayerAction action) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(PREFIX + "プレイヤーのみ実行できます。");
            return;
        }
        action.run(player);
    }

    private void senderLines(CommandSender sender, String... lines) {
        sender.sendMessage(PREFIX + ChatColor.GOLD + "Result");
        for (String line : lines) {
            sender.sendMessage(ChatColor.GRAY + line);
        }
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(PREFIX + ChatColor.RED + "このコマンドはDF管理者限定です。");
    }

    private double currentTps() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getTPS");
            double[] values = (double[]) method.invoke(Bukkit.getServer());
            return Math.min(20.0, values[0]);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return 20.0;
        }
    }

    private double currentMspt() {
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getAverageTickTime");
            Object value = method.invoke(Bukkit.getServer());
            if (value instanceof Number number) {
                return number.doubleValue();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return 1000.0 / Math.max(1.0, currentTps());
    }

    private ChatColor colorForTps(double tps) {
        if (tps >= 19.5) return ChatColor.GREEN;
        if (tps >= 18.5) return ChatColor.YELLOW;
        if (tps >= 16.0) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    private ChatColor colorForMspt(double mspt) {
        if (mspt <= 50.0) return ChatColor.GREEN;
        if (mspt <= 60.0) return ChatColor.YELLOW;
        if (mspt <= 80.0) return ChatColor.GOLD;
        return ChatColor.RED;
    }

    private int penalty(double value, double multiplier) {
        return value <= 0 ? 0 : (int) Math.round(value * multiplier);
    }

    private int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private int parseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean isInteger(String raw) {
        return parseInt(raw, Integer.MIN_VALUE) != Integer.MIN_VALUE;
    }

    private String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String formatSeconds(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        return h + "時間" + m + "分" + s + "秒";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> values = new ArrayList<>(PUBLIC_COMMANDS);
            if (isDfAdmin(sender)) {
                values.addAll(ADMIN_COMMANDS);
            }
            return filter(values, args[0]);
        }
        if (args.length == 2 && List.of("inventorycheck", "watching", "locate").contains(args[0].toLowerCase(Locale.ROOT))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
        }
        if (args.length == 2 && "op".equalsIgnoreCase(args[0])) {
            return filter(List.of("add", "delete", "list"), args[1]);
        }
        if (args.length == 2 && "scoreboard".equalsIgnoreCase(args[0])) {
            return filter(List.of("deathcount"), args[1]);
        }
        if (args.length == 3 && "timer".equalsIgnoreCase(args[0])) {
            return filter(List.of("red", "blue", "green", "yellow", "purple", "pink", "white"), args[2]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    @FunctionalInterface
    private interface PlayerAction {
        void run(Player player);
    }

    private static final class WatchState {
        private final Location location;
        private final GameMode gameMode;

        private WatchState(Location location, GameMode gameMode) {
            this.location = location;
            this.gameMode = gameMode;
        }
    }

    private static final class MeasureState {
        private Location pos1;
        private Location pos2;
    }
}
