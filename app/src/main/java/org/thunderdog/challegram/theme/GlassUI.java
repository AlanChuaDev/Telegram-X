package org.thunderdog.challegram.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import org.thunderdog.challegram.tool.Screen;

/**
 * "Glass" look used by the Glass* themes: a dark backdrop with soft coloured light blobs and
 * translucent rounded cards with a thin light border. The blobs are already blurry by nature
 * (radial gradients), so this works the same on every Android version and costs almost nothing.
 */
public final class GlassUI {
  private GlassUI () { }

  public static boolean isActive () {
    ThemeManager manager = ThemeManager.instance();
    return manager != null && isGlassTheme(manager.currentThemeId());
  }

  @SuppressWarnings("SwitchIntDef")
  public static boolean isGlassTheme (int themeId) {
    switch (themeId) {
      case ThemeId.GLASS_VIOLET:
      case ThemeId.GLASS_OCEAN:
      case ThemeId.GLASS_ROSE:
      case ThemeId.GLASS_EMERALD:
      case ThemeId.GLASS_AMBER:
        return true;
    }
    return false;
  }

  // three blob colours per theme: {base, blob1, blob2, blob3}
  private static int[] palette (int themeId) {
    switch (themeId) {
      case ThemeId.GLASS_OCEAN:
        return new int[] {0xFF080F1F, 0xFF2F6FDE, 0xFF1FA5C8, 0xFF5B55D6};
      case ThemeId.GLASS_ROSE:
        return new int[] {0xFF150A12, 0xFFD6457A, 0xFF8A4FD6, 0xFFE0703F};
      case ThemeId.GLASS_EMERALD:
        return new int[] {0xFF07140F, 0xFF1FA56E, 0xFF1F8FA5, 0xFF6BBF3A};
      case ThemeId.GLASS_AMBER:
        return new int[] {0xFF150F07, 0xFFE0903A, 0xFFD6553F, 0xFFB8A030};
      case ThemeId.GLASS_VIOLET:
      default:
        return new int[] {0xFF0D0A1F, 0xFF7A55D6, 0xFF2F6FDE, 0xFFC04FA8};
    }
  }

  // ---------------------------------------------------------------- cards

  private static final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private static final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private static final Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private static final RectF tmpRect = new RectF();

  static {
    fillPaint.setStyle(Paint.Style.FILL);
    fillPaint.setColor(0x18FFFFFF);
    borderPaint.setStyle(Paint.Style.STROKE);
    borderPaint.setColor(0x2CFFFFFF);
    highlightPaint.setStyle(Paint.Style.STROKE);
    highlightPaint.setColor(0x38FFFFFF);
  }

  /** Draws one translucent glass card. */
  public static void drawCard (Canvas c, float left, float top, float right, float bottom, float radius) {
    tmpRect.set(left, top, right, bottom);
    c.drawRoundRect(tmpRect, radius, radius, fillPaint);
    borderPaint.setStrokeWidth(Math.max(1f, Screen.dp(.75f)));
    tmpRect.inset(borderPaint.getStrokeWidth() / 2f, borderPaint.getStrokeWidth() / 2f);
    c.drawRoundRect(tmpRect, radius, radius, borderPaint);
    // thin brighter line along the top edge, like light catching the glass
    highlightPaint.setStrokeWidth(Math.max(1f, Screen.dp(.75f)));
    float inset = radius * .7f;
    c.drawLine(left + inset, top + highlightPaint.getStrokeWidth(), right - inset, top + highlightPaint.getStrokeWidth(), highlightPaint);
  }

  private static final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private static final Paint edgePaint = new Paint();

  static {
    ringPaint.setStyle(Paint.Style.STROKE);
    ringPaint.setColor(0x66FFFFFF);
    edgePaint.setColor(0x30FFFFFF);
  }

  /** Thin light ring around a round avatar. */
  public static void drawRing (Canvas c, float cx, float cy, float radius) {
    ringPaint.setStrokeWidth(Math.max(1f, Screen.dp(1.25f)));
    c.drawCircle(cx, cy, radius, ringPaint);
  }

  /** Colour for the top bar: a translucent dark tint instead of the flat opaque colour. */
  public static int headerColor (int fallback) {
    ThemeManager manager = ThemeManager.instance();
    if (manager == null || !isGlassTheme(manager.currentThemeId())) {
      return fallback;
    }
    int[] p = palette(manager.currentThemeId());
    int base = p[0], tint = p[1];
    int r = ((base >> 16 & 0xFF) * 6 + (tint >> 16 & 0xFF) * 4) / 10;
    int g = ((base >> 8 & 0xFF) * 6 + (tint >> 8 & 0xFF) * 4) / 10;
    int b = ((base & 0xFF) * 6 + (tint & 0xFF) * 4) / 10;
    return 0xC8000000 | (r << 16) | (g << 8) | b;
  }

  /** Light hairline along the bottom edge of the top bar. */
  public static void drawHeaderEdge (Canvas c, float width, float bottom) {
    float h = Math.max(1f, Screen.dp(.75f));
    c.drawRect(0f, bottom - h, width, bottom, edgePaint);
  }

  // ---------------------------------------------------------------- bars

  /** Translucent bar background (input bar etc.): dark tint with a light hairline on top. */
  public static Drawable barDrawable () {
    return new Drawable() {
      private final Paint paint = new Paint();

      @Override
      public void draw (Canvas c) {
        Rect b = getBounds();
        paint.setColor(headerColor(0xC0101828));
        c.drawRect(b, paint);
        paint.setColor(0x30FFFFFF);
        c.drawRect(b.left, b.top, b.right, b.top + Math.max(1, Screen.dp(.75f)), paint);
      }

      @Override
      public void setAlpha (int alpha) { }

      @Override
      public void setColorFilter (ColorFilter colorFilter) { }

      @Override
      public int getOpacity () {
        return PixelFormat.TRANSLUCENT;
      }
    };
  }

  // ---------------------------------------------------------------- backdrop

  /** Draws the glass backdrop (dark base plus soft coloured light blobs) into any canvas. */
  public static final class Backdrop {
    private final Paint basePaint = new Paint();
    private final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RadialGradient[] blobs;
    private int shaderThemeId = -1;
    private int shaderWidth, shaderHeight;

    private void build (int themeId, int w, int h) {
      int[] p = palette(themeId);
      float size = Math.max(w, h);
      float[][] centers = {
        {-.05f * w, .05f * h, .55f * size},
        {1.05f * w, .45f * h, .5f * size},
        {.1f * w, 1.02f * h, .55f * size}
      };
      blobs = new RadialGradient[3];
      for (int i = 0; i < 3; i++) {
        int color = p[i + 1];
        int center = (color & 0x00FFFFFF) | 0x99000000;
        int edge = color & 0x00FFFFFF;
        blobs[i] = new RadialGradient(centers[i][0], centers[i][1], centers[i][2], center, edge, Shader.TileMode.CLAMP);
      }
      basePaint.setColor(p[0]);
      shaderThemeId = themeId;
      shaderWidth = w;
      shaderHeight = h;
    }

    /** Returns true if something was drawn (a Glass theme is active). */
    public boolean draw (Canvas c, int w, int h) {
      ThemeManager manager = ThemeManager.instance();
      int themeId = manager != null ? manager.currentThemeId() : ThemeId.NONE;
      if (!isGlassTheme(themeId) || w <= 0 || h <= 0) {
        return false;
      }
      if (blobs == null || shaderThemeId != themeId || shaderWidth != w || shaderHeight != h) {
        build(themeId, w, h);
      }
      c.drawRect(0, 0, w, h, basePaint);
      for (RadialGradient blob : blobs) {
        blobPaint.setShader(blob);
        c.drawRect(0, 0, w, h, blobPaint);
      }
      return true;
    }
  }

  /** A view that paints the glass backdrop. Put it under a transparent list. Paints nothing for other themes. */
  public static final class BackdropView extends View {
    private final Backdrop backdrop = new Backdrop();

    public BackdropView (Context context) {
      super(context);
    }

    @Override
    protected void onDraw (Canvas c) {
      backdrop.draw(c, getMeasuredWidth(), getMeasuredHeight());
    }
  }
}
