/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.multiuser;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.util.FunctionalUtils;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

public class BroadcastWaiter implements Closeable {
    private final Context mContext;
    private final String mTag;
    private final int mTimeoutInSecond;
    private final Set<String> mActions;

    private final List<BroadcastReceiver> mBroadcastReceivers = new ArrayList<>();

    private final Map<String, Semaphore> mSemaphoresMap = new ConcurrentHashMap<>();
    private Semaphore getSemaphore(final String action, final int userId) {
        final String key = action + userId;
        return mSemaphoresMap.computeIfAbsent(key, (String absentKey) -> new Semaphore(0));
    }

    public BroadcastWaiter(Context context, String tag, int timeoutInSecond, String... actions) {
        mContext = context;
        mTag = tag + "_" + BroadcastWaiter.class.getSimpleName();
        mTimeoutInSecond = timeoutInSecond;

        mActions = new HashSet<>(Arrays.asList(actions));
        mActions.forEach(this::registerBroadcastReceiver);
    }

    private void registerBroadcastReceiver(String action) {
        Log.d(mTag, "#registerBroadcastReceiver for " + action);

        final IntentFilter filter = new IntentFilter(action);
        if (action.equals(Intent.ACTION_MEDIA_MOUNTED)) {
            filter.addDataScheme(ContentResolver.SCHEME_FILE);
        }

        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (action.equals(intent.getAction())) {
                    final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE,
                            getSendingUserId());
                    final String data = intent.getDataString();
                    Log.d(mTag, "Received " + action + " for user " + userId
                            + (!TextUtils.isEmpty(data) ? " with " + data : ""));
                    getSemaphore(action, userId).release();
                }
            }
        };

        mContext.registerReceiverForAllUsers(receiver, filter, null, null);
        mBroadcastReceivers.add(receiver);
    }

    @Override
    public void close() {
        mBroadcastReceivers.forEach(mContext::unregisterReceiver);
    }

    private boolean waitActionForUser(String action, int userId) {
        Log.d(mTag, "#waitActionForUser(action: " + action + ", userId: " + userId + ")");

        if (!mActions.contains(action)) {
            Log.d(mTag, "No broadcast receivers registered for " + action);
            return false;
        }

        final long startTime = SystemClock.elapsedRealtime();
        try {
            final boolean doneBeforeTimeout = getSemaphore(action, userId)
                    .tryAcquire(1, mTimeoutInSecond, SECONDS);
            if (!doneBeforeTimeout) {
                Log.e(mTag, action + " broadcast wasn't received for user " + userId
                        + " in " + mTimeoutInSecond + " seconds");
                return false;
            }
        } catch (InterruptedException e) {
            Log.e(mTag, "Interrupted while waiting " + action + " for user " + userId);
            return false;
        }
        final long elapsedTime = SystemClock.elapsedRealtime() - startTime;
        Log.d(mTag, action + " broadcast received for user " + userId
                + " in " + elapsedTime + " ms");
        return true;
    }

    public String runThenWaitForBroadcasts(int userId, FunctionalUtils.ThrowingRunnable runnable,
            String... actions) {
        for (String action : actions) {
            getSemaphore(action, userId).drainPermits();
        }
        runnable.run();
        for (String action : actions) {
            if (!waitActionForUser(action, userId)) {
                return action;
            }
        }
        return null;
    }
}
