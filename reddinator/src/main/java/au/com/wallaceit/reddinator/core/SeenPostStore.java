package au.com.wallaceit.reddinator.core;

import android.net.Uri;

import org.apache.commons.text.StringEscapeUtils;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Remembers content keys of posts the user has opened, so reposts can be filtered from feeds.
 * Thread-safe: filterFeed runs on widget service and AsyncTask threads.
 */
public class SeenPostStore {
    private static final int MAX_ENTRIES = 5000;
    private final File file;
    private final LinkedHashSet<String> seen = new LinkedHashSet<>(); // insertion order = age
    private final ExecutorService writer = Executors.newSingleThreadExecutor();

    public SeenPostStore(File file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    seen.add(line);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public synchronized boolean isSeen(JSONObject post) {
        String key = keyFor(post);
        return key != null && seen.contains(key);
    }

    public void markSeen(JSONObject post) {
        String key = keyFor(post);
        if (key == null) {
            return;
        }

        final String snapshot;
        synchronized (this) {
            seen.remove(key); // re-add to move to the newest position
            seen.add(key);
            Iterator<String> it = seen.iterator();
            while (seen.size() > MAX_ENTRIES && it.hasNext()) {
                it.next();
                it.remove();
            }
            StringBuilder sb = new StringBuilder(seen.size() * 17);
            for (String s : seen) {
                sb.append(s).append('\n');
            }
            snapshot = sb.toString();
        }
        writer.execute(() -> {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileWriter w = new FileWriter(tmp)) {
                w.write(snapshot);
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        });
    }

    // post is the reddit "data" object of a link (t3)
    static String keyFor(JSONObject post) {
        try {
            String raw;
            if (post.optBoolean("is_self", false)) {
                String body = normText(post.optString("title")) + "\n" + normText(post.optString("selftext"));
                if (body.trim().isEmpty()) {
                    return null;
                }

                raw = "s:" + body;
            } else {
                String url = StringEscapeUtils.unescapeHtml4(post.optString("url"));
                if (url.isEmpty()) {
                    return null;
                }

                raw = "u:" + normalizeUrl(url);
            }
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }

            return hex.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static String normText(String s) {
        return s.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private static String normalizeUrl(String url) {
        try {
            Uri uri = Uri.parse(url.trim());
            String host = uri.getHost();
            if (host == null) {
                return url.trim().toLowerCase();
            }

            host = host.toLowerCase();
            if (host.startsWith("www.")) {
                host = host.substring(4);
            } else if (host.startsWith("m.")) {
                host = host.substring(2);
            }

            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            TreeSet<String> params = new TreeSet<>();
            for (String name : uri.getQueryParameterNames()) {
                String n = name.toLowerCase();
                if (n.startsWith("utm_") || n.equals("fbclid") || n.equals("gclid") || n.equals("igshid")
                        || n.equals("ref") || n.equals("ref_src") || n.equals("ref_url")) {
                    continue;
                }
                for (String v : uri.getQueryParameters(name)) {
                    params.add(name + "=" + v);
                }
            }
            StringBuilder sb = new StringBuilder(host).append(path);
            if (!params.isEmpty()) {
                sb.append('?');
                boolean first = true;
                for (String p : params) {
                    if (!first) {
                        sb.append('&');
                    }

                    sb.append(p);
                    first = false;
                }
            }
            return sb.toString();
        } catch (Exception e) { // e.g. opaque URIs throw from getQueryParameterNames
            return url.trim().toLowerCase();
        }
    }
}