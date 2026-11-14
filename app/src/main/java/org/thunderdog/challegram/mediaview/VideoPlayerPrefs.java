/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 14/11/2026
 */
package org.thunderdog.challegram.mediaview;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lightweight settings storage for the enhanced video player.
 * Uses plain SharedPreferences so it does not depend on the app's main settings database.
 */
public final class VideoPlayerPrefs {
  private VideoPlayerPrefs () { }

  public static final int ASPECT_FIT = 0;
  public static final int ASPECT_FILL = 1;
  public static final int ASPECT_STRETCH = 2;

  private static final String FILE = "tgx_video_player";
  private static final String K_SEEK_STEP = "seek_step_sec";
  private static final String K_ASPECT = "aspect_mode";
  private static final String K_REMEMBER = "remember_position";
  private static final String K_HSEEK = "horizontal_seek";
  private static final String K_POSITIONS = "positions";
  private static final int MAX_POSITIONS = 100;

  // Session-only flags (reset when the app process restarts)
  public static boolean gesturesLocked;
  public static boolean loopEnabled;

  private static SharedPreferences prefs;

  private static SharedPreferences prefs (Context context) {
    if (prefs == null) {
      prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
    return prefs;
  }

  // Double-tap seek step, seconds

  public static int getSeekStepSeconds (Context context) {
    return prefs(context).getInt(K_SEEK_STEP, 10);
  }

  public static void setSeekStepSeconds (Context context, int seconds) {
    prefs(context).edit().putInt(K_SEEK_STEP, seconds).apply();
  }

  // Aspect ratio mode

  public static int getAspectMode (Context context) {
    return prefs(context).getInt(K_ASPECT, ASPECT_FIT);
  }

  public static void setAspectMode (Context context, int mode) {
    prefs(context).edit().putInt(K_ASPECT, mode).apply();
  }

  // Remember playback position

  public static boolean isRememberPositionEnabled (Context context) {
    return prefs(context).getBoolean(K_REMEMBER, true);
  }

  public static void setRememberPositionEnabled (Context context, boolean enabled) {
    prefs(context).edit().putBoolean(K_REMEMBER, enabled).apply();
  }

  // Horizontal swipe to seek

  public static boolean isHorizontalSeekEnabled (Context context) {
    return prefs(context).getBoolean(K_HSEEK, true);
  }

  public static void setHorizontalSeekEnabled (Context context, boolean enabled) {
    prefs(context).edit().putBoolean(K_HSEEK, enabled).apply();
  }

  // Remembered positions: "key=positionMs;key=positionMs", oldest first

  private static LinkedHashMap<String, Long> readPositions (SharedPreferences p) {
    LinkedHashMap<String, Long> map = new LinkedHashMap<>();
    String raw = p.getString(K_POSITIONS, null);
    if (raw != null && !raw.isEmpty()) {
      for (String part : raw.split(";")) {
        int i = part.lastIndexOf('=');
        if (i > 0) {
          try {
            map.put(part.substring(0, i), Long.parseLong(part.substring(i + 1)));
          } catch (NumberFormatException ignored) { }
        }
      }
    }
    return map;
  }

  public static long getRememberedPosition (Context context, @Nullable String key) {
    if (key == null || key.isEmpty() || !isRememberPositionEnabled(context)) {
      return 0;
    }
    Long value = readPositions(prefs(context)).get(key);
    return value != null ? value : 0;
  }

  public static void rememberPosition (Context context, @Nullable String key, long positionMs, long durationMs) {
    if (key == null || key.isEmpty() || !isRememberPositionEnabled(context)) {
      return;
    }
    SharedPreferences p = prefs(context);
    LinkedHashMap<String, Long> map = readPositions(p);
    map.remove(key);
    // Do not remember the very beginning or the very end of a video
    if (durationMs > 0 && positionMs >= 5000 && durationMs - positionMs > 5000) {
      map.put(key, positionMs);
    }
    while (map.size() > MAX_POSITIONS) {
      Iterator<String> it = map.keySet().iterator();
      it.next();
      it.remove();
    }
    StringBuilder b = new StringBuilder();
    for (Map.Entry<String, Long> entry : map.entrySet()) {
      if (b.length() > 0) {
        b.append(';');
      }
      b.append(entry.getKey()).append('=').append(entry.getValue());
    }
    p.edit().putString(K_POSITIONS, b.toString()).apply();
  }

  // Tiny helper for in-app texts: Chinese when the system language is Chinese, English otherwise.

  public static String tr (String zh, String en) {
    return "zh".equals(Locale.getDefault().getLanguage()) ? zh : en;
  }

  public static String formatSpeed (float speed) {
    if (speed == (float) (int) speed) {
      return ((int) speed) + "x";
    }
    return String.valueOf(speed) + "x";
  }
}
