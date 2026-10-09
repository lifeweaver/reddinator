package au.com.wallaceit.reddinator.core;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.Log;

import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Remembers perceptual (dHash) fingerprints of image posts the user has seen, so the same picture
 * posted under a different URL can be hidden. Matching is by Hamming distance, not equality.
 * Debug tuning: adb logcat -s SeenImages
 */
public class SeenImageStore {
    private static final String TAG = "SeenImages";
    private static final String PREF_KEY = "hideseenimagespref";
    private static final int MAX_ENTRIES = 5000;
    private static final int MAX_DISTANCE = 6; // bits out of 64 that may differ; untested, tune from logcat
    private static final int MAX_ID_CACHE = 2000;
    private static final int TIMEOUT_MS = 4000;
    private static final int PREFETCH_BUDGET_MS = 5000;

    private final File file;
    private final File imageCacheDir;
    private final SharedPreferences prefs;
    private final LinkedHashSet<Long> seen = new LinkedHashSet<>(); // insertion order = age
    // post id -> hash, so each post is fetched at most once per session
    private final Map<String, Long> hashById = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_ID_CACHE;
        }
    });
    private final ExecutorService fetchPool = Executors.newFixedThreadPool(4);
    private final ExecutorService marker = Executors.newSingleThreadExecutor(); // also owns all file writes

    public SeenImageStore(File file, File imageCacheDir, SharedPreferences prefs) {
        this.file = file;
        this.imageCacheDir = imageCacheDir;
        this.prefs = prefs;
        load();
    }

    private boolean enabled() {
        return prefs.getBoolean(PREF_KEY, true);
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    try {
                        seen.add(Long.parseUnsignedLong(line, 16));
                    } catch (NumberFormatException ignored) {
                        // skip corrupt line
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not load seen images", e);
        }
    }

    /** Cache lookup only, never touches the network. Call prefetch() first. */
    public boolean isSeen(JSONObject post) {
        if (!enabled()) {
            return false;
        }
        Long hash = hashById.get(post.optString("name"));
        if (hash == null) {
            return false;
        }
        int best = 64;
        synchronized (this) {
            for (long s : seen) {
                int d = Long.bitCount(s ^ hash);
                if (d < best) {
                    best = d;
                }
            }
        }
        if (best <= 16) {
            Log.d(TAG, post.optString("name") + " nearest seen image distance=" + best);
        }
        return best <= MAX_DISTANCE;
    }

    /** Hashes the image posts in a feed page in parallel. Blocks up to the budget; call off the UI thread. */
    public void prefetch(JSONArray feed) {
        if (!enabled()) {
            return;
        }
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < feed.length(); i++) {
            JSONObject wrapper = feed.optJSONObject(i);
            final JSONObject post = wrapper == null ? null : wrapper.optJSONObject("data");
            if (post == null || previewUrl(post) == null || hashById.containsKey(post.optString("name"))) {
                continue;
            }
            tasks.add(() -> {
                hashFor(post);
                return null;
            });
        }
        if (tasks.isEmpty()) {
            return;
        }
        try {
            fetchPool.invokeAll(tasks, PREFETCH_BUDGET_MS, TimeUnit.MILLISECONDS); // cancels whatever is unfinished
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void markSeenAll(List<JSONObject> posts) {
        if (!enabled()) {
            return;
        }
        final List<JSONObject> candidates = new ArrayList<>();
        for (JSONObject post : posts) {
            if (previewUrl(post) != null) {
                candidates.add(post);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        marker.execute(() -> {
            List<Long> hashes = new ArrayList<>();
            for (JSONObject post : candidates) {
                Long hash = hashFor(post);
                if (hash != null) {
                    hashes.add(hash);
                }
            }
            add(hashes);
        });
    }

    // Runs only on the marker thread, so file writes are serialized.
    private void add(List<Long> hashes) {
        if (hashes.isEmpty()) {
            return;
        }
        String snapshot;
        synchronized (this) {
            for (Long h : hashes) {
                seen.remove(h); // re-add to move to the newest position
                seen.add(h);
            }
            Iterator<Long> it = seen.iterator();
            while (seen.size() > MAX_ENTRIES && it.hasNext()) {
                it.next();
                it.remove();
            }
            StringBuilder sb = new StringBuilder(seen.size() * 17);
            for (long s : seen) {
                sb.append(Long.toHexString(s)).append('\n');
            }
            snapshot = sb.toString();
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileWriter w = new FileWriter(tmp)) {
            w.write(snapshot);
        } catch (IOException e) {
            Log.w(TAG, "Could not save seen images", e);
            return;
        }
        if (!tmp.renameTo(file)) {
            Log.w(TAG, "Could not replace seen images file");
        }
    }

    // May block on disk or network. Returns null when there is no usable hash.
    private Long hashFor(JSONObject post) {
        String id = post.optString("name");
        String url = previewUrl(post);
        if (id.isEmpty() || url == null) {
            return null;
        }
        Long cached = hashById.get(id);
        if (cached != null) {
            return cached;
        }
        Bitmap bitmap = null;
        File cacheFile = new File(imageCacheDir, id + "-preview.png"); // saved by SubredditFeedAdapter
        if (cacheFile.exists()) {
            bitmap = Utilities.decodeSampledBitmapFromFile(cacheFile.getPath(), 640, 640);
        }
        if (bitmap == null) {
            bitmap = download(url);
        }
        if (bitmap == null) {
            return null;
        }
        long hash = dHash(bitmap);
        bitmap.recycle();
        int ones = Long.bitCount(hash);
        if (ones < 8 || ones > 56) {
            return null; // flat image, would match almost anything
        }
        hashById.put(id, hash);
        return hash;
    }

    private static Bitmap download(String url) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            try (InputStream in = con.getInputStream()) {
                return BitmapFactory.decodeStream(in);
            }
        } catch (IOException e) {
            Log.d(TAG, "Could not download " + url + ": " + e.getMessage());
            return null;
        } finally {
            if (con != null) {
                con.disconnect();
            }
        }
    }

    // Scale to 72x64, then box-average 8x8 blocks into a 9x8 grayscale grid (avoids aliasing from a
    // single big bilinear shrink). One bit per horizontally adjacent pair: 8 rows x 8 = 64 bits.
    static long dHash(Bitmap src) {
        Bitmap small = Bitmap.createScaledBitmap(src, 72, 64, true);
        int[] px = new int[72 * 64];
        small.getPixels(px, 0, 72, 0, 0, 72, 64);
        if (small != src) {
            small.recycle();
        }
        int[] grid = new int[9 * 8];
        for (int gy = 0; gy < 8; gy++) {
            for (int gx = 0; gx < 9; gx++) {
                int sum = 0;
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int p = px[(gy * 8 + y) * 72 + gx * 8 + x];
                        sum += (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000;
                    }
                }
                grid[gy * 9 + gx] = sum;
            }
        }
        long hash = 0;
        for (int gy = 0; gy < 8; gy++) {
            for (int gx = 0; gx < 8; gx++) {
                hash = (hash << 1) | (grid[gy * 9 + gx] > grid[gy * 9 + gx + 1] ? 1 : 0);
            }
        }
        return hash;
    }

    // Same preview selection as SubredditFeedAdapter (3rd resolution, or the last if fewer).
    // Returns null for anything we don't hash: non-image posts, NSFW, galleries, videos.
    static String previewUrl(JSONObject post) {
        if (!"image".equals(post.optString("post_hint"))
                || post.optBoolean("over_18") || post.optBoolean("is_video") || post.optBoolean("is_gallery")) {
            return null;
        }
        JSONObject preview = post.optJSONObject("preview");
        JSONArray images = preview == null ? null : preview.optJSONArray("images");
        JSONObject image = (images == null || images.length() == 0) ? null : images.optJSONObject(0);
        if (image == null) {
            return null;
        }
        JSONObject chosen;
        JSONArray resolutions = image.optJSONArray("resolutions");
        if (resolutions != null && resolutions.length() > 0) {
            chosen = resolutions.optJSONObject(resolutions.length() < 3 ? resolutions.length() - 1 : 2);
        } else {
            chosen = image.optJSONObject("source");
        }
        if (chosen == null) {
            return null;
        }
        String url = StringEscapeUtils.unescapeHtml4(chosen.optString("url", ""));
        return url.isEmpty() ? null : url;
    }
}