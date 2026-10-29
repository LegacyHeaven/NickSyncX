package dev.legacyheaven.nicksync;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class NickSyncPlaceholderExpansion extends PlaceholderExpansion {
    private final LegacyNickSyncPlugin plugin;

    public NickSyncPlaceholderExpansion(LegacyNickSyncPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "nicksyncname";
    }

    @Override
    public String getAuthor() {
        return "LegacyHeaven";
    }

    @Override
    @SuppressWarnings("deprecation")
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return params == null || params.isEmpty() ? plugin.placeholderName(player) : null;
    }
}