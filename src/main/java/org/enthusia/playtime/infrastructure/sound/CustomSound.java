package org.enthusia.playtime.infrastructure.sound;

import java.util.regex.Pattern;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

/** Resource-pack events do not exist in Bukkit's server-side sound registry. */
public final class CustomSound {
 private static final Pattern KEY = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");
 private CustomSound() {}
 public static boolean play(Player player, String key, float volume, float pitch) {
  if (key == null || !KEY.matcher(key).matches()) return false;
  player.playSound(player.getLocation(), key, volume, pitch);
  return true;
 }
 public static boolean play(Player player, String key, SoundCategory category, float volume, float pitch) {
  if (key == null || !KEY.matcher(key).matches()) return false;
  player.playSound(player.getLocation(), key, category, volume, pitch);
  return true;
 }
}
