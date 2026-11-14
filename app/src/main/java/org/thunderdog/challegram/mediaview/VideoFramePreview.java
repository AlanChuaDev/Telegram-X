package org.thunderdog.challegram.mediaview;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.loader.ImageGalleryFile;
import org.thunderdog.challegram.mediaview.data.MediaItem;
import org.thunderdog.challegram.tool.Screen;

/**
 * Grabs still frames of the video that is open in the viewer, for the preview window
 * shown while the progress bar (or the horizontal swipe) is being dragged.
 * <p>
 * Frames are decoded from the local file on a background thread, one request at a time:
 * while a frame is being decoded only the latest requested position is kept.
 * Works only for the part of the video that is already on the device, for anything else
 * the callback gets available = false.
 */
final class VideoFramePreview {
  interface Callback {
    /** Called on the main thread. frame == null && available == true is never reported. */
    void onPreviewFrame (@Nullable Bitmap frame, boolean available);
  }

  private final Callback callback;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());

  // main thread only: results of older requests are dropped
  private int generation;

  private final Object lock = new Object();
  private @Nullable HandlerThread thread;
  private @Nullable Handler worker;
  private boolean scheduled;
  private long pendingPositionMs = -1;
  private @Nullable String pendingPath;
  private boolean pendingComplete;
  private int pendingGeneration;

  // worker thread only
  private @Nullable MediaMetadataRetriever retriever;
  private @Nullable String retrieverPath;
  private boolean retrieverComplete;

  VideoFramePreview (Callback callback) {
    this.callback = callback;
  }

  /** Main thread. Asks for the frame at positionMs of a video that lasts durationMs. */
  void request (@Nullable MediaItem item, long positionMs, long durationMs) {
    generation++;
    if (item == null || durationMs <= 0 || positionMs < 0) {
      callback.onPreviewFrame(null, false);
      return;
    }

    String path = null;
    boolean complete = false;
    boolean available = false;

    TdApi.File file = item.getTargetFile();
    if (file != null && file.local != null && file.local.path != null && !file.local.path.isEmpty()) {
      path = file.local.path;
      complete = file.local.isDownloadingCompleted;
      if (complete) {
        available = true;
      } else {
        // The file is still being downloaded: only a prefix of it from the very start is usable.
        long size = file.size > 0 ? file.size : file.expectedSize;
        long prefix = file.local.downloadOffset == 0 ? file.local.downloadedPrefixSize : 0;
        if (size > 0 && prefix > 0) {
          long bytePosition = (long) ((double) positionMs / (double) durationMs * (double) size);
          available = bytePosition < (long) (prefix * 0.9);
        }
      }
    } else {
      ImageGalleryFile gallery = item.getSourceGalleryFile();
      String galleryPath = gallery != null ? gallery.getFilePath() : null;
      if (galleryPath != null && !galleryPath.isEmpty()) {
        path = galleryPath;
        complete = true;
        available = true;
      }
    }

    if (!available || path == null) {
      callback.onPreviewFrame(null, false);
      return;
    }

    synchronized (lock) {
      if (thread == null) {
        HandlerThread t = new HandlerThread("VideoFramePreview");
        t.start();
        thread = t;
        worker = new Handler(t.getLooper());
        scheduled = false;
      }
      pendingPath = path;
      pendingComplete = complete;
      pendingPositionMs = positionMs;
      pendingGeneration = generation;
      if (scheduled) {
        return;
      }
      scheduled = true;
      worker.post(this::process);
    }
  }

  /** Main thread. Drops everything that is queued or being decoded. */
  void cancel () {
    generation++;
    synchronized (lock) {
      pendingPositionMs = -1;
    }
  }

  /** Main thread. Cancels and stops the background thread; the next request() starts it again. */
  void stop () {
    generation++;
    synchronized (lock) {
      pendingPositionMs = -1;
      scheduled = false;
      final HandlerThread t = thread;
      final Handler w = worker;
      thread = null;
      worker = null;
      if (t != null && w != null) {
        w.post(this::closeRetriever);
        t.quitSafely();
      }
    }
  }

  // ---------------------------------------------------------------- worker thread

  private void process () {
    final String path;
    final boolean complete;
    final long positionMs;
    final int requestGeneration;
    synchronized (lock) {
      if (Thread.currentThread() != thread) {
        return;
      }
      path = pendingPath;
      complete = pendingComplete;
      positionMs = pendingPositionMs;
      requestGeneration = pendingGeneration;
      pendingPositionMs = -1;
    }

    final Bitmap frame = path != null && positionMs >= 0 ? decode(path, complete, positionMs) : null;
    final boolean skipped = path == null || positionMs < 0;
    if (!skipped) {
      mainHandler.post(() -> {
        if (requestGeneration == generation) {
          callback.onPreviewFrame(frame, frame != null);
        }
      });
    }

    synchronized (lock) {
      if (Thread.currentThread() != thread) {
        return;
      }
      if (pendingPositionMs >= 0 && worker != null) {
        worker.post(this::process);
      } else {
        scheduled = false;
      }
    }
  }

  private @Nullable Bitmap decode (String path, boolean complete, long positionMs) {
    try {
      if (retriever == null || !path.equals(retrieverPath) || retrieverComplete != complete) {
        closeRetriever();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
          r.setDataSource(path);
        } catch (Throwable t) {
          try {
            r.release();
          } catch (Throwable ignored) { }
          return null;
        }
        retriever = r;
        retrieverPath = path;
        retrieverComplete = complete;
      }
      Bitmap raw = retriever.getFrameAtTime(positionMs * 1000l, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
      if (raw == null) {
        return null;
      }
      return scale(raw);
    } catch (Throwable t) {
      closeRetriever();
      return null;
    }
  }

  private static Bitmap scale (Bitmap raw) {
    final float maxWidth = Screen.dp(170f);
    final float maxHeight = Screen.dp(130f);
    final int w = raw.getWidth();
    final int h = raw.getHeight();
    if (w <= 0 || h <= 0) {
      return raw;
    }
    final float scale = Math.min(maxWidth / (float) w, maxHeight / (float) h);
    if (scale >= 1f) {
      return raw;
    }
    Bitmap out = Bitmap.createScaledBitmap(raw, Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
    if (out != raw) {
      raw.recycle();
    }
    return out;
  }

  private void closeRetriever () {
    MediaMetadataRetriever r = retriever;
    retriever = null;
    retrieverPath = null;
    if (r != null) {
      try {
        r.release();
      } catch (Throwable ignored) { }
    }
  }
}
