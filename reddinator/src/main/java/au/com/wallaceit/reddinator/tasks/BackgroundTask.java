/*
 * Copyright 2013 Michael Boyde Wallace (http://wallaceit.com.au)
 * This file is part of Reddinator.
 *
 * Reddinator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Reddinator is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Reddinator (COPYING). If not, see <http://www.gnu.org/licenses/>.
 */
package au.com.wallaceit.reddinator.tasks;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drop-in replacement for the deprecated android.os.AsyncTask, with the same callbacks and
 * the same semantics the app relies on:
 * - all tasks run one at a time on a single shared background thread (like AsyncTask.execute());
 * - callbacks other than doInBackground run on the main thread;
 * - after cancel(), onPostExecute is skipped and onCancelled(result) is called instead;
 * - an exception thrown from doInBackground is not swallowed; it crashes the app, as before.
 */
public abstract class BackgroundTask<Params, Progress, Result> {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "BackgroundTask"));
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile boolean finished = false;
    private final Object lock = new Object();
    private Thread worker; // guarded by lock; non-null only while doInBackground is running

    protected void onPreExecute() {
    }

    protected abstract Result doInBackground(Params... params);

    protected void onProgressUpdate(Progress... values) {
    }

    protected void onPostExecute(Result result) {
    }

    protected void onCancelled(Result result) {
    }

    @SafeVarargs
    protected final void publishProgress(Progress... values) {
        if (!isCancelled()) {
            MAIN.post(() -> onProgressUpdate(values));
        }
    }

    public final boolean isCancelled() {
        return cancelled.get();
    }

    public final boolean cancel(boolean mayInterruptIfRunning) {
        if (finished) {
            return false;
        }
        cancelled.set(true);
        if (mayInterruptIfRunning) {
            synchronized (lock) {
                if (worker != null) {
                    worker.interrupt();
                }
            }
        }
        return true;
    }

    /**
     * Call from the main thread, like AsyncTask.execute().
     */
    @SafeVarargs
    public final BackgroundTask<Params, Progress, Result> execute(Params... params) {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Task can only be executed once");
        }
        onPreExecute();
        EXECUTOR.execute(() -> {
            Result result = null;
            synchronized (lock) {
                worker = Thread.currentThread();
            }
            try {
                if (!isCancelled()) {
                    result = doInBackground(params);
                }
            } finally {
                synchronized (lock) {
                    worker = null;
                }
                Thread.interrupted(); // a late cancel(true) must not leak into the next queued task
            }
            final Result r = result;
            MAIN.post(() -> finish(r));
        });
        return this;
    }

    private void finish(Result result) {
        if (isCancelled()) {
            onCancelled(result);
        } else {
            onPostExecute(result);
        }
        finished = true;
    }
}