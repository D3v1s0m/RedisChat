package dev.unnm3d.redischat.chat;

import dev.unnm3d.redischat.Config;
import dev.unnm3d.redischat.Permission;
import dev.unnm3d.redischat.RedisChat;
import dev.unnm3d.redischat.redis.ChatPacket;
import net.kyori.adventure.platform.bukkit.BukkitAudiences;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

public class ChatListener implements Listener {
    private final BukkitAudiences bukkitAudiences;
    private final RedisChat plugin;

    public ChatListener(RedisChat plugin) {
        this.plugin = plugin;
        this.bukkitAudiences = BukkitAudiences.create(plugin);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onChat(AsyncPlayerChatEvent event) {
        if (event.isCancelled()) return;
        event.setCancelled(true);
        Bukkit.getScheduler().runTaskAsynchronously(RedisChat.getInstance(), () -> {
            long init = System.currentTimeMillis();

            List<Config.ChatFormat> chatFormatList = RedisChat.config.getChatFormats(event.getPlayer());
            if (chatFormatList.isEmpty()) return;
            if (!event.getPlayer().hasPermission(Permission.REDIS_CHAT_ADMIN.getPermission()))
                if (plugin.getRedisDataManager().isRateLimited(event.getPlayer().getName())) {
                    RedisChat.config.sendMessage(event.getPlayer(), RedisChat.config.rate_limited);
                    return;
                }
            if (RedisChat.config.debug)
                Bukkit.getLogger().info("Rate limit: " + (System.currentTimeMillis() - init) + " ms ");

            Component formatted = TextParser.parse(event.getPlayer(), chatFormatList.get(0).format());

            //Check for minimessage tags permission
            String message = event.getMessage();
            boolean parsePlaceholders = true;
            if (!event.getPlayer().hasPermission(Permission.REDIS_CHAT_USE_FORMATTING.getPermission())) {
                message = TextParser.purgeTags(message);
                parsePlaceholders = false;
            }
            if (message.trim().equals("")) return;
            message = TextParser.sanitize(message);

            long tFormat = System.currentTimeMillis() - init;
            init = System.currentTimeMillis();
            //Check inv update
            if (message.contains("<inv>")) {
                plugin.getRedisDataManager().addInventory(event.getPlayer().getName(), event.getPlayer().getInventory().getContents());
            }
            if (message.contains("<item>")) {
                plugin.getRedisDataManager().addItem(event.getPlayer().getName(), event.getPlayer().getInventory().getItemInMainHand());
            }
            if (message.contains("<ec>")) {
                plugin.getRedisDataManager().addEnderchest(event.getPlayer().getName(), event.getPlayer().getEnderChest().getContents());
            }
            long tInv = System.currentTimeMillis() - init;


            //Parse into minimessage (placeholders, tags and mentions)
            init = System.currentTimeMillis();
            Component toBeReplaced = TextParser.parse(event.getPlayer(), message, parsePlaceholders, TextParser.getCustomTagResolver(event.getPlayer(), chatFormatList.get(0)));

            //Put message into format
            formatted = formatted.replaceText(
                    builder -> builder.match("%message%").replacement(toBeReplaced)
            );
            long tParse = System.currentTimeMillis() - init;
            init = System.currentTimeMillis();

            // Send to other servers
            plugin.getRedisDataManager().sendObjectPacket(new ChatPacket(event.getPlayer().getName(), MiniMessage.miniMessage().serialize(formatted)));
            plugin.getRedisDataManager().setRateLimit(event.getPlayer().getName(), RedisChat.config.rate_limit_time_seconds);

            long tRedis = System.currentTimeMillis() - init;
            if (RedisChat.config.debug)
                Bukkit.getLogger().info(" Format: " + tFormat + " Inv: " + tInv + " Parse: " + tParse + " Send: " + tRedis + " Total: " + (tFormat + tInv + tParse + tRedis) + " ms");

        });
    }

    public void onPublicChat(String serializedText) {
        bukkitAudiences.all().sendMessage(MiniMessage.miniMessage().deserialize(serializedText));
    }

    public void onPrivateChat(String senderName, String receiverName, String text) {
        Player p = Bukkit.getPlayer(receiverName);
        if (p != null)
            if (p.isOnline()) {
                List<Config.ChatFormat> chatFormatList = RedisChat.config.getChatFormats(p);
                if (chatFormatList.isEmpty()) return;
                Component formatted = TextParser.parse(null, chatFormatList.get(0).receive_private_format().replace("%receiver%", receiverName).replace("%sender%", senderName));
                Component toBeReplaced = TextParser.parse(null, text);
                //Put message into format
                formatted = formatted.replaceText(
                        builder -> builder.match("%message%").replacement(toBeReplaced)
                );
                RedisChat.config.sendMessage(p, formatted);
            }

    }

    public void onSenderPrivateChat(CommandSender sender, Component formatted) {
        RedisChat.config.sendMessage(sender, formatted);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        RedisChat.getInstance().getRedisDataManager().addPlayerName(event.getPlayer().getName());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        RedisChat.getInstance().getRedisDataManager().removePlayerName(event.getPlayer().getName());
    }

    public void onSpyPrivateChat(String receiverName, String senderName, Player watcher, String deserialize) {
        Component formatted = MiniMessage.miniMessage().deserialize(RedisChat.config.spychat_format.replace("%receiver%", receiverName).replace("%sender%", senderName));

        //Parse into minimessage (placeholders, tags and mentions)
        Component toBeReplaced = TextParser.parse(deserialize);
        //Put message into format
        formatted = formatted.replaceText(
                builder -> builder.match("%message%").replacement(toBeReplaced)
        );
        RedisChat.config.sendMessage(watcher, formatted);
    }
}
