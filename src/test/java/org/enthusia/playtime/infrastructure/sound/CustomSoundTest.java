package org.enthusia.playtime.infrastructure.sound;
import org.junit.jupiter.api.Test;
import org.bukkit.entity.Player;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
class CustomSoundTest {
 @Test void sendsExactCustomKeyWithOriginalGainAndPitchOnce() {
  var calls = new ArrayList<Object[]>();
  Player player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p,m,a) -> {
   if (m.getName().equals("playSound")) { calls.add(a); return null; }
   if (m.getName().equals("getLocation")) return null;
   throw new UnsupportedOperationException(m.getName());
  });
  assertTrue(CustomSound.play(player, "enthusia_theme:ui.reward", 0.7f, 1.2f));
  assertEquals(1,calls.size()); assertEquals("enthusia_theme:ui.reward",calls.getFirst()[1]);
  assertEquals(0.7f,calls.getFirst()[2]); assertEquals(1.2f,calls.getFirst()[3]);
 }
 @Test void legacyBlankAndMalformedValuesRemainWithOriginalResolver() {
  for (String s : new String[]{"ENTITY_PLAYER_LEVELUP", "", "Bad Namespace:ui.reward", "enthusia_theme:", ":reward", "theme:reward!", "theme:reward:extra"})
   assertFalse(CustomSound.play(null,s,1f,1f),s);
  assertFalse(CustomSound.play(null,null,1f,1f));
 }
}
