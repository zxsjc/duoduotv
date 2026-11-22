package xiao.bu.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * 央视频频道流目录：从央视频官方页面配置取「频道 pid → 直播流 ID(cnlid)」的最新映射，
 * 让流 ID 自动跟随官方变更，不必再改 ChannelCatalog 里的静态值。
 *
 * 央视频会给频道换直播流 ID（pid 不变），旧 ID 会让 get_live_info 返回
 * data.code=8「节目已结束」。这里缓存映射并定期刷新，解析时优先用缓存值。
 */
final class YangshipinChannelDirectory {
    private static final String TAG = "YspDirectory";
    private static final String PREFS = "yangshipin_directory";
    private static final String KEY_MAP = "pid_cnlid";
    private static final String KEY_AT = "updated_at";
    private static final String CONFIG_URL =
            "https://capi.yangshipin.cn/api/oms/pc/page/PG00000004?";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
    /** 缓存有效期：超过就在后台刷新。 */
    private static final long MAX_AGE_MS = 60L * 60 * 1000;
    /** 刷新失败后的重试间隔，避免解析失败时反复打接口。 */
    private static final long RETRY_MS = 10L * 60 * 1000;
    /** 首次解析时最多等待后台刷新多久（毫秒）。 */
    private static final long FIRST_WAIT_MS = 2500L;

    /** 与配置文件里每条频道记录的形态一致：控制字符之后到引号之间的频道名。 */
    private static final Pattern NAME = Pattern.compile("([^\"\\x00-\\x1F]{2,26})\"");
    private static final Pattern PID = Pattern.compile("\\d{9,12}");
    private static final Pattern CNLID = Pattern.compile("(\\d{10})[\\s\\S]{0,8}?J");

    private static final Map<String, String> PID_TO_CNLID = new HashMap<String, String>();
    private static final Object LOAD_LOCK = new Object();
    private static volatile long updatedAt;
    private static Context appContext;
    private static volatile boolean refreshing;
    private static volatile long lastAttemptAt;
    private static volatile boolean firstWaitDone;

    private YangshipinChannelDirectory() {
    }

    static synchronized void initialize(Context context) {
        if (context == null) {
            return;
        }
        appContext = context.getApplicationContext();
        loadFromPrefs();
        long age = System.currentTimeMillis() - updatedAt;
        if (updatedAt <= 0L || age > MAX_AGE_MS) {
            refreshAsync(false);
        }
    }

    /**
     * 解析某个 pid 当前应使用的流 ID：缓存里有最新值就用它，没有才用静态兜底值。
     */
    static String resolve(String pid, String fallback) {
        if (pid == null || pid.length() == 0) {
            return fallback;
        }
        boolean empty;
        synchronized (YangshipinChannelDirectory.class) {
            empty = PID_TO_CNLID.isEmpty();
        }
        if (empty) {
            waitFirstLoad();
        }
        String cached;
        synchronized (YangshipinChannelDirectory.class) {
            cached = PID_TO_CNLID.get(pid);
            long age = System.currentTimeMillis() - updatedAt;
            if (appContext != null && (PID_TO_CNLID.isEmpty() || age > MAX_AGE_MS)) {
                refreshAsync(false);
            }
        }
        if (cached != null && cached.length() > 0) {
            if (!cached.equals(fallback)) {
                Log.i(TAG, "pid " + pid + " 使用最新流ID " + cached + "（内置值 " + fallback + "）");
            }
            return cached;
        }
        return fallback;
    }

    /** 解析失败时调用：按重试间隔拉一次最新映射，下次解析就能用新值。 */
    static void refreshAfterFailure() {
        refreshAsync(false);
    }

    /**
     * 缓存为空时的首次解析：等后台刷新一下（主线程不发网络请求），
     * 等不到就先用内置值，解析失败后再由 refreshAfterFailure 兜底。
     */
    private static void waitFirstLoad() {
        if (firstWaitDone || appContext == null) {
            return;
        }
        firstWaitDone = true;
        refreshAsync(false);
        synchronized (LOAD_LOCK) {
            long deadline = System.currentTimeMillis() + FIRST_WAIT_MS;
            while (PID_TO_CNLID.isEmpty() && System.currentTimeMillis() < deadline) {
                try {
                    LOAD_LOCK.wait(200L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int size;
        synchronized (YangshipinChannelDirectory.class) {
            size = PID_TO_CNLID.size();
        }
        Log.i(TAG, "首次解析前等待结束，已载入 " + size + " 个频道映射");
    }

    private static void loadFromPrefs() {
        try {
            SharedPreferences prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            updatedAt = prefs.getLong(KEY_AT, 0L);
            String raw = prefs.getString(KEY_MAP, "");
            if (raw == null || raw.length() == 0) {
                return;
            }
            JSONObject json = new JSONObject(raw);
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String value = json.optString(key, "");
                if (key.length() > 0 && value.length() > 0) {
                    PID_TO_CNLID.put(key, value);
                }
            }
            Log.i(TAG, "载入本地缓存 " + PID_TO_CNLID.size() + " 个频道映射");
        } catch (Exception error) {
            Log.w(TAG, "读取本地频道映射失败", error);
        }
    }

    private static void refreshAsync(boolean force) {
        if (appContext == null || refreshing) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && lastAttemptAt > 0L && now - lastAttemptAt < RETRY_MS) {
            return;
        }
        if (!force) {
            long age = now - updatedAt;
            if (updatedAt > 0L && age >= 0L && age <= MAX_AGE_MS) {
                return;
            }
        }
        refreshing = true;
        lastAttemptAt = now;
        final Context context = appContext;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, String> latest = fetch(15000, 20000);
                    if (latest.isEmpty()) {
                        Log.w(TAG, "接口未返回可用频道映射");
                        synchronized (LOAD_LOCK) {
                            LOAD_LOCK.notifyAll();
                        }
                        return;
                    }
                    apply(context, latest);
                } catch (Exception error) {
                    Log.w(TAG, "刷新央视频频道映射失败", error);
                    synchronized (LOAD_LOCK) {
                        LOAD_LOCK.notifyAll();
                    }
                } finally {
                    refreshing = false;
                }
            }
        }, "ysp-directory").start();
    }

    private static Map<String, String> fetch(int connectMs, int readMs) throws Exception {
        URL url = new URL(CONFIG_URL + System.currentTimeMillis());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(connectMs);
        connection.setReadTimeout(readMs);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", "https://www.yangshipin.cn/");
        connection.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            InputStream stream = connection.getInputStream();
            String encoding = connection.getContentEncoding();
            if (encoding != null && encoding.toLowerCase(Locale.US).contains("gzip")) {
                stream = new GZIPInputStream(stream);
            }
            String text = readAll(stream);
            return parse(text);
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        try {
            while ((read = stream.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        } finally {
            stream.close();
        }
        return new String(output.toByteArray(), "UTF-8");
    }

    /** 解析页面配置里每条频道记录的 pid 与 cnlid。 */
    private static Map<String, String> parse(String text) {
        Map<String, String> result = new HashMap<String, String>();
        String[] segments = text.split("tv-channel-list");
        for (int index = 1; index < segments.length; index++) {
            String segment = segments[index];
            Matcher name = NAME.matcher(segment);
            if (!name.find()) {
                continue;
            }
            String tail = segment.substring(name.end());
            Matcher pid = PID.matcher(tail);
            if (!pid.find()) {
                continue;
            }
            Matcher cnlid = CNLID.matcher(tail);
            if (!cnlid.find()) {
                continue;
            }
            String key = pid.group();
            String value = cnlid.group(1);
            if (result.containsKey(key)) {
                continue;
            }
            result.put(key, value);
        }
        return result;
    }

    private static void apply(Context context, Map<String, String> latest) throws Exception {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, String> entry : latest.entrySet()) {
            json.put(entry.getKey(), entry.getValue());
        }
        long now = System.currentTimeMillis();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MAP, json.toString())
                .putLong(KEY_AT, now)
                .apply();
        int changed = 0;
        String sample = "";
        synchronized (YangshipinChannelDirectory.class) {
            for (Map.Entry<String, String> entry : latest.entrySet()) {
                String previous = PID_TO_CNLID.put(entry.getKey(), entry.getValue());
                if (previous != null && !previous.equals(entry.getValue())) {
                    changed++;
                    if (sample.length() == 0) {
                        sample = entry.getKey() + " " + previous + " → " + entry.getValue();
                    }
                }
            }
            updatedAt = now;
        }
        Log.i(TAG, "已更新 " + latest.size() + " 个频道映射，变更 " + changed + " 条"
                + (sample.length() > 0 ? "（例：" + sample + "）" : ""));
        synchronized (LOAD_LOCK) {
            LOAD_LOCK.notifyAll();
        }
    }
}
