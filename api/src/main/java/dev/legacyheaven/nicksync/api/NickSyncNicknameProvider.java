package dev.legacyheaven.nicksync.api;

import org.bukkit.entity.Player;

public interface NickSyncNicknameProvider {
    String getNickname(Player player);

    void setNickname(Player player, String nickname);
}