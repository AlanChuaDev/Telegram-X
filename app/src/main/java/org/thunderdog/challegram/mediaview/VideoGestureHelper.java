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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;
import org.thunderdog.challegram.tool.UI;

/**
 * Gesture layer for the media viewer video player:
 * - double tap on the left/right third: seek back/forward (chained taps accumulate)
 * - long press: temporary fast playback
 * - vertical swipe on the left/right half: brightness / volume
 * - horizontal swipe: scrub through the video
 * Also owns the overlay that draws the animations and the speed / settings menus.
 */
public final class VideoGestureHelper implements MediaCellViewDetector.GestureHandler {
  private static final int STATE_IDLE = 0;
  private static final int STATE_SEEK = 1;
  private static final int STATE_BRIGHTNESS = 2;
  private static final int STATE_VOLUME = 3;
  private static final int STATE_SPEED = 4;
  private static final int STATE_IGNORED = 5;

  private static final long SEEK_CHAIN_MS = 650l;
  private static final long MAX_SCRUB_SPAN_MS = 180_000l;
  private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f};

  private final MediaCellView cell;
  private final Context context;
  private final Overlay overlay;

  private int state = STATE_IDLE;
  private boolean gestureAvailable;
  private float startX, startY;

  // double tap seek chain
  private int chainZone;
  private long chainLastTapTime;
  private long chainAccumMs;

  // speed boost
  private float boostPreviousSpeed = 1f;

  // scrub
  private long scrubStartPosition, scrubDuration, scrubTarget;

  // brightness / volume
  private float levelStart;
  private int volumeMax;
  private boolean brightnessOverridden;
  private float originalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;

  public VideoGestureHelper (MediaCellView cell) {
    this.cell = cell;
    this.context = cell.getContext();
    this.overlay = new Overlay(context);
  }

  public View getOverlayView () {
    return overlay;
  }

  // ---------------------------------------------------------------- availability

  private boolean isUsable () {
    return !VideoPlayerPrefs.gesturesLocked && cell.canUseVideoGestures();
  }

  private boolean hasSeekableVideo () {
    VideoPlayerView p = cell.getVideoPlayerView();
    return p != null && p.getDurationMs() > 0 && cell.canSeekCurrentVideo();
  }

  // ---------------------------------------------------------------- double tap seek

  public boolean canSeekByTap () {
    return isUsable() && hasSeekableVideo();
  }

  public int getTapZone (float x) {
    float w = cell.getMeasuredWidth();
    if (w <= 0f) {
      return 0;
    }
    if (x < w / 3f) {
      return -1;
    }
    if (x > w * 2f / 3f) {
      return 1;
    }
    return 0;
  }

  public boolean isSeekChainActive (int zone) {
    return zone != 0 && zone == chainZone && SystemClock.uptimeMillis() - chainLastTapTime <= SEEK_CHAIN_MS;
  }

  public void onSeekTap (int zone, float x, float y) {
    VideoPlayerView p = cell.getVideoPlayerView();
    if (p == null || zone == 0) {
      return;
    }
    long now = SystemClock.uptimeMillis();
    if (!isSeekChainActive(zone)) {
      chainAccumMs = 0;
    }
    chainZone = zone;
    chainLastTapTime = now;
    long step = VideoPlayerPrefs.getSeekStepSeconds(context) * 1000l;
    chainAccumMs += step;
    p.seekByMs(zone * step);
    overlay.showSeekFlash(zone, chainAccumMs);
  }

  // ---------------------------------------------------------------- touch handling

  /**
   * Whether a horizontal drag starting at (x, y) is going to be used for scrubbing.
   * MediaView asks this to avoid switching to the next/previous media in that case.
   */
  public boolean wantsHorizontalDrag (float x, float y) {
    if (!VideoPlayerPrefs.isHorizontalSeekEnabled(context) || !isUsable() || !hasSeekableVideo()) {
      return false;
    }
    float w = cell.getMeasuredWidth();
    float h = cell.getMeasuredHeight();
    return x > Screen.dp(24f) && x < w - Screen.dp(24f) && y > h * 0.18f && y < h - Screen.dp(130f);
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    final int action = e.getActionMasked();

    if (e.getPointerCount() > 1) {
      if (state != STATE_IDLE && state != STATE_IGNORED) {
        finishGesture(state, false);
      }
      cancelLongPress();
      state = STATE_IGNORED;
      return false;
    }

    switch (action) {
      case MotionEvent.ACTION_DOWN: {
        cancelLongPress();
        if (state != STATE_IDLE && state != STATE_IGNORED) {
          finishGesture(state, false);
        }
        state = STATE_IDLE;
        startX = e.getX();
        startY = e.getY();
        gestureAvailable = isUsable() && !cell.getDetector().isZoomed();
        if (gestureAvailable) {
          overlay.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
        }
        return false;
      }
      case MotionEvent.ACTION_MOVE: {
        if (state == STATE_IGNORED || !gestureAvailable) {
          return false;
        }
        final float dx = e.getX() - startX;
        final float dy = e.getY() - startY;
        if (state == STATE_IDLE) {
          if (Math.max(Math.abs(dx), Math.abs(dy)) <= Screen.getTouchSlopBig()) {
            return false;
          }
          cancelLongPress();
          if (Math.abs(dx) > Math.abs(dy) * 1.2f) {
            if (wantsHorizontalDrag(startX, startY)) {
              beginScrub();
            } else {
              state = STATE_IGNORED;
              return false;
            }
          } else if (Math.abs(dy) > Math.abs(dx) * 1.2f) {
            if (startY < cell.getMeasuredHeight() * 0.12f || startY > cell.getMeasuredHeight() - Screen.dp(130f)) {
              state = STATE_IGNORED;
              return false;
            }
            beginLevel(startX < cell.getMeasuredWidth() / 2f ? STATE_BRIGHTNESS : STATE_VOLUME);
          } else {
            return false;
          }
          if (state == STATE_IDLE || state == STATE_IGNORED) {
            return false;
          }
        }
        switch (state) {
          case STATE_SEEK:
            moveScrub(e);
            break;
          case STATE_BRIGHTNESS:
          case STATE_VOLUME:
            moveLevel(e);
            break;
        }
        return true;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL: {
        cancelLongPress();
        int prev = state;
        state = STATE_IDLE;
        boolean owned = prev == STATE_SEEK || prev == STATE_BRIGHTNESS || prev == STATE_VOLUME || prev == STATE_SPEED;
        if (owned) {
          finishGesture(prev, action == MotionEvent.ACTION_UP);
        }
        return owned;
      }
    }
    return state != STATE_IDLE && state != STATE_IGNORED;
  }

  private final Runnable longPressRunnable = () -> {
    if (state == STATE_IDLE && gestureAvailable && cell.isPlaying()) {
      beginSpeedBoost();
    }
  };

  private void cancelLongPress () {
    overlay.removeCallbacks(longPressRunnable);
  }

  private void requestDisallow (boolean disallow) {
    ViewParent parent = cell.getParent();
    if (parent != null) {
      parent.requestDisallowInterceptTouchEvent(disallow);
    }
  }

  private void finishGesture (int finishedState, boolean commit) {
    switch (finishedState) {
      case STATE_SEEK: {
        VideoPlayerView p = cell.getVideoPlayerView();
        if (commit && p != null) {
          p.seekToMs(scrubTarget);
        }
        overlay.hideScrub();
        break;
      }
      case STATE_BRIGHTNESS:
      case STATE_VOLUME:
        overlay.endLevel();
        break;
      case STATE_SPEED: {
        VideoPlayerView p = cell.getVideoPlayerView();
        if (p != null) {
          p.setPlaybackSpeed(boostPreviousSpeed);
        }
        overlay.hideSpeed();
        break;
      }
    }
    requestDisallow(false);
  }

  /** Drops any running gesture and restores window brightness. */
  public void release () {
    cancelLongPress();
    if (state != STATE_IDLE && state != STATE_IGNORED) {
      finishGesture(state, false);
    }
    state = STATE_IDLE;
    restoreBrightness();
  }

  // ---------------------------------------------------------------- speed boost

  private void beginSpeedBoost () {
    VideoPlayerView p = cell.getVideoPlayerView();
    if (p == null) {
      return;
    }
    state = STATE_SPEED;
    boostPreviousSpeed = p.getPlaybackSpeed();
    float boost = boostPreviousSpeed >= 2f ? 3f : 2f;
    p.setPlaybackSpeed(boost);
    overlay.showSpeed(boost);
    overlay.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    requestDisallow(true);
  }

  // ---------------------------------------------------------------- scrubbing

  private void beginScrub () {
    VideoPlayerView p = cell.getVideoPlayerView();
    if (p == null || p.getDurationMs() <= 0) {
      state = STATE_IGNORED;
      return;
    }
    state = STATE_SEEK;
    scrubStartPosition = p.getPositionMs();
    scrubDuration = p.getDurationMs();
    scrubTarget = scrubStartPosition;
    requestDisallow(true);
  }

  private void moveScrub (MotionEvent e) {
    float w = Math.max(1f, cell.getMeasuredWidth());
    long span = Math.min(scrubDuration, MAX_SCRUB_SPAN_MS);
    long delta = (long) ((e.getX() - startX) / w * span);
    scrubTarget = Math.max(0, Math.min(scrubDuration, scrubStartPosition + delta));
    overlay.showScrub(scrubTarget, scrubDuration, scrubTarget - scrubStartPosition);
  }

  // ---------------------------------------------------------------- brightness / volume

  @Nullable
  private Activity activity () {
    try {
      return UI.getContext(context);
    } catch (Throwable t) {
      return null;
    }
  }

  private float readBrightness (Activity a) {
    float b = a.getWindow().getAttributes().screenBrightness;
    if (b < 0f) {
      try {
        b = Settings.System.getInt(a.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255f;
      } catch (Throwable t) {
        b = 0.5f;
      }
    }
    return Math.max(0.01f, Math.min(1f, b));
  }

  private void beginLevel (int newState) {
    if (newState == STATE_BRIGHTNESS) {
      Activity a = activity();
      if (a == null) {
        state = STATE_IGNORED;
        return;
      }
      levelStart = readBrightness(a);
      if (!brightnessOverridden) {
        originalBrightness = a.getWindow().getAttributes().screenBrightness;
        brightnessOverridden = true;
      }
    } else {
      AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      if (am == null) {
        state = STATE_IGNORED;
        return;
      }
      volumeMax = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
      levelStart = am.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) volumeMax;
    }
    state = newState;
    requestDisallow(true);
  }

  private void moveLevel (MotionEvent e) {
    float range = Math.max(1f, cell.getMeasuredHeight() * 0.6f);
    float value = Math.max(0f, Math.min(1f, levelStart + (startY - e.getY()) / range));
    if (state == STATE_BRIGHTNESS) {
      Activity a = activity();
      if (a != null) {
        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        lp.screenBrightness = Math.max(0.01f, value);
        a.getWindow().setAttributes(lp);
      }
      overlay.showLevel(true, value);
    } else {
      AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
      if (am != null) {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(value * volumeMax), 0);
      }
      overlay.showLevel(false, value);
    }
  }

  private void restoreBrightness () {
    if (brightnessOverridden) {
      brightnessOverridden = false;
      Activity a = activity();
      if (a != null) {
        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        lp.screenBrightness = originalBrightness;
        a.getWindow().setAttributes(lp);
      }
    }
  }

  // ---------------------------------------------------------------- menus

  private static String tr (String zh, String en) {
    return VideoPlayerPrefs.tr(zh, en);
  }

  private static String onOff (boolean on) {
    return on ? tr("开", "On") : tr("关", "Off");
  }

  public void showSpeedMenu () {
    final VideoPlayerView p = cell.getVideoPlayerView();
    if (p == null) {
      Toast.makeText(context, tr("请先开始播放视频", "Start playing the video first"), Toast.LENGTH_SHORT).show();
      return;
    }
    float current = p.getPlaybackSpeed();
    String[] labels = new String[SPEEDS.length];
    int checked = 2;
    for (int i = 0; i < SPEEDS.length; i++) {
      labels[i] = SPEEDS[i] == 1f ? tr("1x（正常）", "1x (Normal)") : VideoPlayerPrefs.formatSpeed(SPEEDS[i]);
      if (SPEEDS[i] == current) {
        checked = i;
      }
    }
    new AlertDialog.Builder(context)
      .setTitle(tr("播放倍速", "Playback speed"))
      .setSingleChoiceItems(labels, checked, (dialog, which) -> {
        p.setPlaybackSpeed(SPEEDS[which]);
        cell.dispatchPlaybackSpeedChanged(SPEEDS[which]);
        dialog.dismiss();
      })
      .show();
  }

  public void showMoreMenu () {
    final VideoPlayerView p = cell.getVideoPlayerView();
    String[] aspectNames = {tr("适应", "Fit"), tr("填充", "Fill"), tr("拉伸", "Stretch")};
    final String[] items = {
      tr("画面比例：", "Aspect ratio: ") + aspectNames[VideoPlayerPrefs.getAspectMode(context)],
      tr("双击跳转：", "Double-tap seek: ") + VideoPlayerPrefs.getSeekStepSeconds(context) + tr(" 秒", " s"),
      tr("循环播放：", "Loop: ") + onOff(VideoPlayerPrefs.loopEnabled),
      tr("手势锁定（防误触）：", "Lock gestures: ") + onOff(VideoPlayerPrefs.gesturesLocked),
      tr("横向滑动调进度：", "Swipe to seek: ") + onOff(VideoPlayerPrefs.isHorizontalSeekEnabled(context)),
      tr("记住播放位置：", "Remember position: ") + onOff(VideoPlayerPrefs.isRememberPositionEnabled(context))
    };
    new AlertDialog.Builder(context)
      .setTitle(tr("播放器设置", "Player settings"))
      .setItems(items, (dialog, which) -> {
        switch (which) {
          case 0:
            showAspectMenu();
            break;
          case 1:
            showSeekStepMenu();
            break;
          case 2:
            VideoPlayerPrefs.loopEnabled = !VideoPlayerPrefs.loopEnabled;
            if (p != null) {
              p.setUserLooping(VideoPlayerPrefs.loopEnabled);
            }
            toast(tr("循环播放：", "Loop: ") + onOff(VideoPlayerPrefs.loopEnabled));
            break;
          case 3:
            VideoPlayerPrefs.gesturesLocked = !VideoPlayerPrefs.gesturesLocked;
            toast(VideoPlayerPrefs.gesturesLocked ?
              tr("已锁定：双击跳转、滑动、长按手势已关闭", "Locked: double-tap, swipe and long-press gestures are off") :
              tr("已解锁手势", "Gestures unlocked"));
            break;
          case 4:
            VideoPlayerPrefs.setHorizontalSeekEnabled(context, !VideoPlayerPrefs.isHorizontalSeekEnabled(context));
            toast(tr("横向滑动调进度：", "Swipe to seek: ") + onOff(VideoPlayerPrefs.isHorizontalSeekEnabled(context)));
            break;
          case 5:
            VideoPlayerPrefs.setRememberPositionEnabled(context, !VideoPlayerPrefs.isRememberPositionEnabled(context));
            toast(tr("记住播放位置：", "Remember position: ") + onOff(VideoPlayerPrefs.isRememberPositionEnabled(context)));
            break;
        }
        dialog.dismiss();
      })
      .show();
  }

  private void showAspectMenu () {
    String[] names = {tr("适应（保持比例）", "Fit (keep ratio)"), tr("填充（裁剪边缘）", "Fill (crop edges)"), tr("拉伸（铺满屏幕）", "Stretch")};
    new AlertDialog.Builder(context)
      .setTitle(tr("画面比例", "Aspect ratio"))
      .setSingleChoiceItems(names, VideoPlayerPrefs.getAspectMode(context), (dialog, which) -> {
        VideoPlayerPrefs.setAspectMode(context, which);
        cell.applyAspectMode();
        dialog.dismiss();
      })
      .show();
  }

  private void showSeekStepMenu () {
    final int[] steps = {5, 10, 15, 30};
    String[] names = new String[steps.length];
    int checked = 1;
    int current = VideoPlayerPrefs.getSeekStepSeconds(context);
    for (int i = 0; i < steps.length; i++) {
      names[i] = steps[i] + tr(" 秒", " s");
      if (steps[i] == current) {
        checked = i;
      }
    }
    new AlertDialog.Builder(context)
      .setTitle(tr("双击跳转时长", "Double-tap seek step"))
      .setSingleChoiceItems(names, checked, (dialog, which) -> {
        VideoPlayerPrefs.setSeekStepSeconds(context, steps[which]);
        dialog.dismiss();
      })
      .show();
  }

  private void toast (String text) {
    Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
  }

  // ---------------------------------------------------------------- overlay

  private final class Overlay extends View {
    private static final long FLASH_HOLD_MS = 550l;
    private static final long FLASH_FADE_MS = 250l;
    private static final long RIPPLE_MS = 380l;
    private static final long LEVEL_HOLD_MS = 700l;
    private static final long LEVEL_FADE_MS = 250l;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private boolean flashActive;
    private int flashSide;
    private long flashStart;
    private long flashAccumMs;

    private boolean levelActive, levelTouching, levelIsBrightness;
    private float levelValue;
    private long levelEnd;

    private boolean speedActive;
    private float speedValue;

    private boolean scrubActive;
    private long scrubPos, scrubDur, scrubDelta;

    Overlay (Context context) {
      super(context);
      setWillNotDraw(false);
      setClickable(false);
      setFocusable(false);
      textPaint.setTypeface(Typeface.DEFAULT_BOLD);
      textPaint.setTextAlign(Paint.Align.CENTER);
    }

    void showSeekFlash (int side, long accumMs) {
      flashActive = true;
      flashSide = side;
      flashAccumMs = accumMs;
      flashStart = SystemClock.uptimeMillis();
      invalidate();
    }

    void showLevel (boolean isBrightness, float value) {
      levelActive = true;
      levelTouching = true;
      levelIsBrightness = isBrightness;
      levelValue = value;
      invalidate();
    }

    void endLevel () {
      levelTouching = false;
      levelEnd = SystemClock.uptimeMillis();
      invalidate();
    }

    void showSpeed (float speed) {
      speedActive = true;
      speedValue = speed;
      invalidate();
    }

    void hideSpeed () {
      speedActive = false;
      invalidate();
    }

    void showScrub (long pos, long dur, long delta) {
      scrubActive = true;
      scrubPos = pos;
      scrubDur = dur;
      scrubDelta = delta;
      invalidate();
    }

    void hideScrub () {
      scrubActive = false;
      invalidate();
    }

    @Override
    protected void onDraw (@NonNull Canvas c) {
      long now = SystemClock.uptimeMillis();
      boolean again = false;
      if (flashActive) {
        again |= drawSeekFlash(c, now);
      }
      if (levelActive) {
        again |= drawLevel(c, now);
      }
      if (speedActive) {
        drawSpeed(c);
      }
      if (scrubActive) {
        drawScrub(c);
      }
      if (again) {
        postInvalidateOnAnimation();
      }
    }

    private void setShadow (boolean on) {
      if (on) {
        textPaint.setShadowLayer(Screen.dp(2f), 0f, 0f, 0x99000000);
      } else {
        textPaint.clearShadowLayer();
      }
    }

    private boolean drawSeekFlash (Canvas c, long now) {
      long dt = now - flashStart;
      float a = dt < FLASH_HOLD_MS ? 1f : 1f - (dt - FLASH_HOLD_MS) / (float) FLASH_FADE_MS;
      if (a <= 0f) {
        flashActive = false;
        return false;
      }
      final float w = getWidth();
      final float h = getHeight();
      final float ripple = Math.min(1f, dt / (float) RIPPLE_MS);

      fillPaint.setColor(Color.argb((int) (70f * a * (1f - .4f * ripple)), 255, 255, 255));
      c.save();
      if (flashSide < 0) {
        c.clipRect(0f, 0f, w / 2f, h);
      } else {
        c.clipRect(w / 2f, 0f, w, h);
      }
      c.drawCircle(flashSide < 0 ? 0f : w, h / 2f, w * .35f + w * .25f * ripple, fillPaint);
      c.restore();

      final float cx = flashSide < 0 ? w * .25f : w * .75f;
      final float cy = h / 2f;
      final float s = Screen.dp(11f);
      final int lit = (int) ((dt / 110l) % 3);
      for (int i = 0; i < 3; i++) {
        int order = flashSide < 0 ? 2 - i : i;
        float alpha = order == lit ? a : a * .4f;
        fillPaint.setColor(Color.argb((int) (255f * alpha), 255, 255, 255));
        float x = cx + (i - 1) * s * 1.7f;
        path.reset();
        if (flashSide > 0) {
          path.moveTo(x - s * .5f, cy - s * .65f);
          path.lineTo(x + s * .5f, cy);
          path.lineTo(x - s * .5f, cy + s * .65f);
        } else {
          path.moveTo(x + s * .5f, cy - s * .65f);
          path.lineTo(x - s * .5f, cy);
          path.lineTo(x + s * .5f, cy + s * .65f);
        }
        path.close();
        c.drawPath(path, fillPaint);
      }

      textPaint.setColor(Color.argb((int) (255f * a), 255, 255, 255));
      textPaint.setTextSize(Screen.dp(15f));
      setShadow(true);
      c.drawText(flashAccumMs / 1000l + tr(" 秒", " seconds"), cx, cy + Screen.dp(38f), textPaint);
      setShadow(false);
      return true;
    }

    private void drawPill (Canvas c, float cx, float top, float width, float height, float alphaFactor) {
      rect.set(cx - width / 2f, top, cx + width / 2f, top + height);
      fillPaint.setColor(Color.argb((int) (150f * alphaFactor), 0, 0, 0));
      c.drawRoundRect(rect, height / 2f, height / 2f, fillPaint);
    }

    private boolean drawLevel (Canvas c, long now) {
      float a = 1f;
      if (!levelTouching) {
        long dt = now - levelEnd;
        a = dt < LEVEL_HOLD_MS ? 1f : 1f - (dt - LEVEL_HOLD_MS) / (float) LEVEL_FADE_MS;
        if (a <= 0f) {
          levelActive = false;
          return false;
        }
      }
      final float cx = getWidth() / 2f;
      final float top = Screen.dp(88f);
      final float width = Screen.dp(180f);
      final float height = Screen.dp(52f);
      drawPill(c, cx, top, width, height, a);

      textPaint.setColor(Color.argb((int) (255f * a), 255, 255, 255));
      textPaint.setTextSize(Screen.dp(14f));
      setShadow(false);
      String label = (levelIsBrightness ? tr("亮度 ", "Brightness ") : tr("音量 ", "Volume ")) + Math.round(levelValue * 100f) + "%";
      c.drawText(label, cx, top + Screen.dp(23f), textPaint);

      final float barLeft = cx - width / 2f + Screen.dp(20f);
      final float barRight = cx + width / 2f - Screen.dp(20f);
      final float barY = top + Screen.dp(35f);
      final float barH = Screen.dp(4f);
      rect.set(barLeft, barY, barRight, barY + barH);
      fillPaint.setColor(Color.argb((int) (90f * a), 255, 255, 255));
      c.drawRoundRect(rect, barH / 2f, barH / 2f, fillPaint);
      rect.set(barLeft, barY, barLeft + (barRight - barLeft) * levelValue, barY + barH);
      fillPaint.setColor(Color.argb((int) (255f * a), 255, 255, 255));
      c.drawRoundRect(rect, barH / 2f, barH / 2f, fillPaint);
      return true;
    }

    private void drawSpeed (Canvas c) {
      final float cx = getWidth() / 2f;
      final float top = Screen.dp(88f);
      drawPill(c, cx, top, Screen.dp(170f), Screen.dp(36f), 1f);
      textPaint.setColor(0xffffffff);
      textPaint.setTextSize(Screen.dp(14f));
      setShadow(false);
      c.drawText(">> " + VideoPlayerPrefs.formatSpeed(speedValue) + tr(" 倍速播放", " speed"), cx, top + Screen.dp(23f), textPaint);
    }

    private void drawScrub (Canvas c) {
      final float cx = getWidth() / 2f;
      final float cy = getHeight() / 2f;
      final float width = Screen.dp(190f);
      final float height = Screen.dp(96f);
      drawPill(c, cx, cy - height / 2f, width, height, 1f);
      rect.set(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f);

      textPaint.setColor(0xffffffff);
      textPaint.setTextSize(Screen.dp(26f));
      setShadow(false);
      c.drawText(Strings.buildDuration(Math.round(scrubPos / 1000.0)), cx, cy - Screen.dp(6f), textPaint);

      textPaint.setTextSize(Screen.dp(13f));
      long deltaSec = Math.round(scrubDelta / 1000.0);
      String sub = (deltaSec >= 0 ? "+" : "-") + Strings.buildDuration(Math.abs(deltaSec)) + "  /  " + Strings.buildDuration(Math.round(scrubDur / 1000.0));
      c.drawText(sub, cx, cy + Screen.dp(16f), textPaint);

      final float barLeft = cx - width / 2f + Screen.dp(20f);
      final float barRight = cx + width / 2f - Screen.dp(20f);
      final float barY = cy + Screen.dp(30f);
      final float barH = Screen.dp(3f);
      float progress = scrubDur > 0 ? Math.max(0f, Math.min(1f, scrubPos / (float) scrubDur)) : 0f;
      rect.set(barLeft, barY, barRight, barY + barH);
      fillPaint.setColor(0x59ffffff);
      c.drawRoundRect(rect, barH / 2f, barH / 2f, fillPaint);
      rect.set(barLeft, barY, barLeft + (barRight - barLeft) * progress, barY + barH);
      fillPaint.setColor(0xffffffff);
      c.drawRoundRect(rect, barH / 2f, barH / 2f, fillPaint);
    }
  }
}
