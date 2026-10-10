package nx.ext;

import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.icu.text.CompactDecimalFormat;
import android.util.TypedValue;
import android.view.View;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Return YouTube Dislike: shows like and dislike counts estimated by the community API at
 * returnyoutubedislike.com. YouTube's action bar shows icon-only buttons, so the counts are drawn
 * over the button views, which YouTube tags with accessibility ids such as "id.video.dislike".
 */
@SuppressWarnings("unused")
public final class Ryd {
    private Ryd() {
    }

    private static final String API = "https://returnyoutubedislikeapi.com/votes?videoId=";
    private static final String TAG_NAME = "elements_accessibility_view_tag_id";
    private static final String LIKE_ID = "id.video.like", DISLIKE_ID = "id.video.dislike";

    static final class Votes {
        final long likes, dislikes;

        Votes(long likes, long dislikes) {
            this.likes = likes;
            this.dislikes = dislikes;
        }
    }

    private static final Map<String, Votes> cache = new LinkedHashMap<String, Votes>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Votes> eldest) {
            return size() > 50;
        }
    };
    private static final Map<View, Count> counts = new WeakHashMap<>();
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static int tagId = -1;

    private static boolean enabled() {
        return Features.has(Features.RYD) && Nx.bool(NxSettings.RYD_ENABLED);
    }

    private static final java.util.Set<String> pending = new java.util.HashSet<>();

    /** Loads the votes of a video; called as soon as its id is known. */
    static void fetch(final String videoId) {
        if (videoId == null || !enabled()) return;
        synchronized (cache) {
            if (cache.containsKey(videoId) || !pending.add(videoId)) return;
        }
        executor.execute(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API + videoId).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setRequestProperty("Accept", "application/json");
                try {
                    if (c.getResponseCode() != 200) return;
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    try (InputStream in = c.getInputStream()) {
                        byte[] buffer = new byte[4096];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    }
                    JSONObject json = new JSONObject(out.toString("UTF-8"));
                    Votes votes = new Votes(json.optLong("likes", -1), json.optLong("dislikes", -1));
                    synchronized (cache) {
                        cache.put(videoId, votes);
                    }
                    Nx.runOnMain(Ryd::refreshAll);
                } finally {
                    c.disconnect();
                }
            } catch (Throwable t) {
                Nx.log("RYD fetch failed", t);
            } finally {
                synchronized (cache) {
                    pending.remove(videoId);
                }
            }
        });
    }

    private static Votes current() {
        String id = Video.videoId();
        if (id == null) return null;
        synchronized (cache) {
            return cache.get(id);
        }
    }

    /** Injection point: a Litho view gets its accessibility description (buttons get theirs then). */
    public static void onContentDescription(View host, CharSequence description) {
        try {
            if (host == null || !enabled()) return;
            if (tagId == -1) {
                tagId = host.getResources().getIdentifier(TAG_NAME, "id", host.getContext().getPackageName());
            }
            if (tagId == 0) return;
            Object tag = host.getTag(tagId);
            String id = tag == null ? null : tag.toString();
            boolean like = id != null && id.startsWith(LIKE_ID);
            boolean dislike = id != null && id.startsWith(DISLIKE_ID);
            Count existing = counts.get(host);
            if (!like && !dislike) {
                if (existing != null) {
                    host.getOverlay().remove(existing);
                    counts.remove(host);
                }
                return;
            }
            if (existing == null) {
                existing = new Count(host);
                counts.put(host, existing);
                host.getOverlay().add(existing);
                final Count count = existing;
                host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> count.fit());
            }
            existing.dislike = dislike;
            existing.fit();
            existing.invalidateSelf();
        } catch (Throwable t) {
            Nx.log("RYD button failed", t);
        }
    }

    private static void refreshAll() {
        for (Count c : counts.values()) c.invalidateSelf();
    }

    static String format(long value) {
        try {
            return CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(value);
        } catch (Throwable t) {
            return String.valueOf(value);
        }
    }

    /** The count drawn below the icon of a like or dislike button. */
    private static final class Count extends Drawable {
        private final View host;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean dislike;

        Count(View host) {
            this.host = host;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10,
                    host.getResources().getDisplayMetrics()));
        }

        void fit() {
            setBounds(0, 0, host.getWidth(), host.getHeight());
        }

        @Override
        public void draw(Canvas canvas) {
            if (!enabled()) return;
            Votes votes = current();
            if (votes == null) return;
            long value = dislike ? votes.dislikes : votes.likes;
            if (value < 0) return;
            boolean dark = (host.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            paint.setColor(dark ? 0xFFF1F1F1 : 0xFF0F0F0F);
            float x = getBounds().width() / 2f;
            float y = getBounds().height() - paint.descent() - TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 1, host.getResources().getDisplayMetrics());
            canvas.drawText(format(value), x, y, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
