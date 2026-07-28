package com.example.mlb;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Shared, TTL'd, background-refreshed cache backing every MLB_* cell
 * function.
 *
 * <p>Cell functions must never block on network I/O (LibreOffice recalculates
 * every open sheet's formulas on the UI thread). This cache implements the
 * cache + background-fetch + sentinel-return pattern: {@link #get} always
 * returns immediately.
 * <ul>
 *   <li>Fresh or stale-but-present data -&gt; {@link Result#ready}. A stale
 *       entry also kicks off a silent background refresh.</li>
 *   <li>No data yet, no fetch in flight -&gt; a background fetch is started
 *       and {@link Result#loading} is returned.</li>
 *   <li>The most recent attempt failed and nothing is cached yet -&gt;
 *       {@link Result#error}, until a cooldown elapses and the key is
 *       retried automatically on the next call.</li>
 * </ul>
 * Concurrent identical requests are de-duplicated via {@link #inFlight}: only
 * the first caller for a given key starts a fetch.
 */
final class MlbCache {

    /** Outcome of a cache lookup, handed back to the calling cell function. */
    static final class Result {
        static final String READY = "READY";
        static final String LOADING = "LOADING";
        static final String ERROR = "ERROR";

        final String status;
        final Object data;
        final String error;

        private Result(String status, Object data, String error) {
            this.status = status;
            this.data = data;
            this.error = error;
        }

        static Result ready(Object data) {
            return new Result(READY, data, null);
        }

        static Result loading() {
            return new Result(LOADING, null, null);
        }

        static Result error(String message) {
            return new Result(ERROR, null, message);
        }
    }

    /** Fetches one value; runs on a background thread. */
    interface Fetcher {
        Object fetch() throws Exception;
    }

    private static final class Entry {
        final Object data;
        final long fetchedAt;
        final long ttlMillis;

        Entry(Object data, long fetchedAt, long ttlMillis) {
            this.data = data;
            this.fetchedAt = fetchedAt;
            this.ttlMillis = ttlMillis;
        }

        boolean isFresh() {
            return System.currentTimeMillis() - fetchedAt < ttlMillis;
        }
    }

    private static final class ErrorInfo {
        final long at;
        final String message;

        ErrorInfo(long at, String message) {
            this.at = at;
            this.message = message;
        }
    }

    private static final int MAX_ENTRIES = 1000;
    private static final long RETRY_COOLDOWN_MS = 15000;

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<String, Entry>();
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<String, Boolean>();
    private static final Map<String, ErrorInfo> ERRORS = new ConcurrentHashMap<String, ErrorInfo>();

    private static volatile String lastError = "";

    private static final ExecutorService EXEC = Executors.newFixedThreadPool(2, new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "mlb-addin-fetch");
            t.setDaemon(true);
            return t;
        }
    });

    private MlbCache() {
    }

    static Result get(String key, long ttlMillis, Fetcher fetcher) {
        Entry e = CACHE.get(key);
        if (e != null) {
            if (!e.isFresh()) {
                triggerFetch(key, ttlMillis, fetcher);
            }
            return Result.ready(e.data);
        }

        ErrorInfo err = ERRORS.get(key);
        if (err != null && System.currentTimeMillis() - err.at < RETRY_COOLDOWN_MS) {
            return Result.error(err.message);
        }

        triggerFetch(key, ttlMillis, fetcher);
        return Result.loading();
    }

    private static void triggerFetch(final String key, final long ttlMillis, final Fetcher fetcher) {
        if (IN_FLIGHT.putIfAbsent(key, Boolean.TRUE) != null) {
            return; // a fetch for this key is already running
        }
        EXEC.submit(new Runnable() {
            public void run() {
                try {
                    Object data = fetcher.fetch();
                    CACHE.put(key, new Entry(data, System.currentTimeMillis(), ttlMillis));
                    ERRORS.remove(key);
                    evictIfNeeded();
                } catch (Exception ex) {
                    String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                    lastError = msg;
                    ERRORS.put(key, new ErrorInfo(System.currentTimeMillis(), msg));
                } finally {
                    IN_FLIGHT.remove(key);
                }
            }
        });
    }

    private static void evictIfNeeded() {
        if (CACHE.size() <= MAX_ENTRIES) {
            return;
        }
        for (Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator(); it.hasNext(); ) {
            if (!it.next().getValue().isFresh()) {
                it.remove();
            }
        }
        while (CACHE.size() > MAX_ENTRIES) {
            String oldestKey = null;
            long oldestAt = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> en : CACHE.entrySet()) {
                if (en.getValue().fetchedAt < oldestAt) {
                    oldestAt = en.getValue().fetchedAt;
                    oldestKey = en.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            CACHE.remove(oldestKey);
        }
    }

    /** Clears every cached entry (and any recorded errors); returns the count cleared. */
    static int clear() {
        int n = CACHE.size();
        CACHE.clear();
        ERRORS.clear();
        return n;
    }

    static String lastError() {
        return lastError;
    }
}
