package nx.ext;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.Configuration;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NX GIF picker: searches Tenor and copies the chosen GIF's link. Pasted into a YouTube comment,
 * the link is posted as a GIF. Tenor closed its public API, so results are read from the data
 * Tenor embeds in its own web pages.
 */
public final class GifActivity extends Activity {
    /** Shows the picker; [onPick] gets the chosen GIF's link. */
    static View picker(final Activity activity, final java.util.function.Consumer<String> onPick) {
        return new Picker(activity, onPick).root;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_DeviceDefault : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(savedInstanceState);
        setTitle(Nx.t("GIF seç", "Pick a GIF"));
        setContentView(picker(this, link -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("GIF", link));
            Nx.toast(Nx.t("GIF bağlantısı kopyalandı. Yorum kutusuna yapıştırıp gönderin.",
                    "GIF link copied. Paste it into a comment and send it."));
            finish();
        }));
    }

    /** The picker UI; works in this activity and in a dialog over the comment box. */
    static final class Picker {
    private static final String TENOR = "https://tenor.com";
    private static final Pattern STORE = Pattern.compile(
            "<script id=\"store-cache\" type=\"text/x-cache\"[^>]*>(.*?)</script>", Pattern.DOTALL);

    static final class Item {
        final String id, preview;
        final int width, height;

        Item(String id, String preview, int width, int height) {
            this.id = id;
            this.preview = preview;
            this.width = width;
            this.height = height;
        }
    }

    private final Activity activity;
    private final java.util.function.Consumer<String> onPick;
    final LinearLayout root;
    private final List<Item> items = new ArrayList<>();
    private final ExecutorService search = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(4);
    private final LruCache<String, byte[]> cache = new LruCache<String, byte[]>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };
    private BaseAdapter adapter;
    private TextView status;
    private int generation;

    Picker(Activity activity, java.util.function.Consumer<String> onPick) {
        this.activity = activity;
        this.onPick = onPick;
        root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setPadding(dp(12), dp(8), dp(12), 0);

        TextView hint = new TextView(activity);
        hint.setText(Nx.t("Bir GIF'e dokunun; bağlantısı yorumunuza eklenir ve gönderince GIF olarak paylaşılır. Bağlantının yanına yazı da ekleyebilirsiniz.",
                "Tap a GIF to add its link to your comment; it is posted as a GIF. You can add text next to the link."));
        hint.setAlpha(0.75f);
        root.addView(hint);

        final EditText query = new EditText(activity);
        query.setTag(GifButton.R_PICKER, Boolean.TRUE);
        query.setHint(Nx.t("Tenor'da ara veya Tenor bağlantısı yapıştır", "Search Tenor or paste a Tenor link"));
        query.setSingleLine(true);
        query.setInputType(InputType.TYPE_CLASS_TEXT);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                load(query.getText().toString());
                return true;
            }
            return false;
        });
        root.addView(query);

        status = new TextView(activity);
        status.setPadding(0, dp(4), 0, dp(4));
        root.addView(status);

        GridView grid = new GridView(activity);
        grid.setNumColumns(2);
        grid.setHorizontalSpacing(dp(6));
        grid.setVerticalSpacing(dp(6));
        adapter = new Adapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            Item item = items.get(position);
            shutdown();
            onPick.accept(Gif.link(item.id));
        });
        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                shutdown();
            }
        });

        load("");
    }

    private void shutdown() {
        search.shutdownNow();
        images.shutdownNow();
    }

    private void load(final String input) {
        final int token = ++generation;
        status.setText(Nx.t("Yükleniyor…", "Loading…"));
        search.execute(() -> {
            List<Item> found;
            String error = null;
            try {
                found = find(input.trim());
            } catch (Throwable t) {
                found = new ArrayList<>();
                error = t.getMessage();
            }
            final List<Item> result = found;
            final String problem = error;
            activity.runOnUiThread(() -> {
                if (token != generation || activity.isFinishing()) return;
                items.clear();
                items.addAll(result);
                adapter.notifyDataSetChanged();
                if (problem != null) status.setText(Nx.t("Tenor'a ulaşılamadı: ", "Could not reach Tenor: ") + problem);
                else if (result.isEmpty()) status.setText(Nx.t("Sonuç yok", "No results"));
                else status.setText("");
            });
        });
    }

    // region Tenor

    private static List<Item> find(String input) throws Exception {
        if (input.isEmpty()) {
            JSONObject store = store(TENOR + "/");
            return items(store.optJSONObject("gifs") == null ? null
                    : store.getJSONObject("gifs").optJSONObject("featured") == null ? null
                    : store.getJSONObject("gifs").getJSONObject("featured").optJSONArray("results"));
        }
        String id = Gif.tenorId(input);
        if (id == null && input.matches("\\d{6,20}")) id = input;
        if (id != null) {
            JSONObject byId = store(TENOR + "/view/gif-" + id).optJSONObject("gifs");
            byId = byId == null ? null : byId.optJSONObject("byId");
            JSONArray all = new JSONArray();
            if (byId != null) {
                for (Iterator<String> it = byId.keys(); it.hasNext(); ) {
                    JSONArray r = byId.getJSONObject(it.next()).optJSONArray("results");
                    if (r != null && r.length() > 0) {
                        all.put(r.get(0));
                        break;
                    }
                }
            }
            return items(all);
        }
        StringBuilder slug = new StringBuilder();
        for (String word : input.toLowerCase().split("\\s+")) {
            if (word.isEmpty()) continue;
            if (slug.length() > 0) slug.append('-');
            slug.append(URLEncoder.encode(word, "UTF-8"));
        }
        JSONObject universal = store(TENOR + "/search/" + slug + "-gifs").optJSONObject("universal");
        JSONObject searches = universal == null ? null : universal.optJSONObject("search");
        if (searches == null || !searches.keys().hasNext()) return new ArrayList<>();
        return items(searches.getJSONObject(searches.keys().next()).optJSONArray("results"));
    }

    private static JSONObject store(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36");
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            String html = new String(read(c.getInputStream()), "UTF-8");
            Matcher m = STORE.matcher(html);
            if (!m.find()) throw new IllegalStateException(Nx.t("Tenor sayfa biçimi değişmiş", "Tenor changed its page format"));
            return new JSONObject(m.group(1));
        } finally {
            c.disconnect();
        }
    }

    private static List<Item> items(JSONArray results) {
        List<Item> out = new ArrayList<>();
        if (results == null) return out;
        for (int i = 0; i < results.length() && out.size() < 50; i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String id = r.optString("id");
            if (!id.matches("\\d{1,20}")) continue;
            JSONArray flags = r.optJSONArray("flags");
            if (flags != null && (flags.toString().contains("sticker") || flags.toString().contains("static"))) continue;
            JSONObject formats = r.optJSONObject("media_formats");
            if (formats == null) continue;
            // A still preview: decoding animated GIFs crashed on some devices and costs memory.
            JSONObject f = formats.optJSONObject("gifpreview");
            if (f == null) f = formats.optJSONObject("tinygifpreview");
            if (f == null) f = formats.optJSONObject("tinygif");
            if (f == null) continue;
            String url = f.optString("url");
            if (!url.matches("https://media\\d*\\.tenor\\.com/.*")) continue;
            JSONArray dims = f.optJSONArray("dims");
            out.add(new Item(id, url, dims == null ? 220 : dims.optInt(0, 220), dims == null ? 160 : dims.optInt(1, 160)));
        }
        return out;
    }

    private static byte[] read(InputStream stream) throws Exception {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    // endregion

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ImageView view = convertView instanceof ImageView ? (ImageView) convertView : new ImageView(activity);
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)));
            view.setBackgroundColor(0x22888888);
            final Item item = items.get(position);
            view.setTag(item.preview);
            view.setImageDrawable(null);
            showGif(view, item.preview);
            return view;
        }
    }

    private void showGif(final ImageView view, final String url) {
        images.execute(() -> {
            try {
                byte[] bytes = cache.get(url);
                if (bytes == null) {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(15000);
                    try {
                        bytes = read(c.getInputStream());
                    } finally {
                        c.disconnect();
                    }
                    cache.put(url, bytes);
                }
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
                android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
                options.inSampleSize = 1;
                while (bounds.outWidth / (options.inSampleSize * 2) >= 240) options.inSampleSize *= 2;
                final android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) return;
                activity.runOnUiThread(() -> {
                    if (url.equals(view.getTag())) view.setImageBitmap(bitmap);
                });
            } catch (Throwable ignored) {
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
    }
}
