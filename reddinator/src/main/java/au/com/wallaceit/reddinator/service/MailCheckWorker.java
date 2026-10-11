package au.com.wallaceit.reddinator.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;

import au.com.wallaceit.reddinator.R;
import au.com.wallaceit.reddinator.Reddinator;
import au.com.wallaceit.reddinator.activity.MessagesActivity;
import au.com.wallaceit.reddinator.core.RedditData;
import au.com.wallaceit.reddinator.core.Utilities;

public class MailCheckWorker extends Worker {
    private static final String TAG = "MailCheckWorker";
    private static final String CHANNEL_ID = "mail";
    private static final String KEY_ACTION = "action";
    public static final String MAIL_CHECK_COMPLETE = "reddinator.mail.check.complete";
    public static final String ACTIVITY_CHECK_ACTION = "reddinator.mail.check";
    public static final String NOTIFY_CHECK_ACTION = "reddinator.mail.check.notify";

    public static void checkMail(Context context, String action) {
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(MailCheckWorker.class)
                .setInputData(new Data.Builder().putString(KEY_ACTION, action).build())
                .build();
        // KEEP: if a check for this action is already queued/running, don't pile up another
        WorkManager.getInstance(context)
                .enqueueUniqueWork("mailcheck-" + action, ExistingWorkPolicy.KEEP, request);
    }

    public MailCheckWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        String action = getInputData().getString(KEY_ACTION);
        Reddinator global = (Reddinator) getApplicationContext();
        if (!global.mRedditData.isLoggedIn()
                || !(ACTIVITY_CHECK_ACTION.equals(action) || NOTIFY_CHECK_ACTION.equals(action))) {
            return Result.success();
        }

        int oldCount = global.mRedditData.getInboxCount();
        try {
            global.mRedditData.updateUserInfo();
        } catch (RedditData.RedditApiException e) {
            Log.w(TAG, "Mail check failed", e);
            return Result.failure();
        }
        // update stored unread messages if the count has changed
        int newCount = global.mRedditData.getInboxCount();
        if (newCount > 0 && newCount != oldCount) {
            try {
                JSONArray messages = global.mRedditData.getMessageFeed("unread", 25, null);
                global.setUnreadMessages(messages);
            } catch (RedditData.RedditApiException e) {
                // user may not be authorised for the new api scope, ignore
                Log.w(TAG, "Could not load unread messages", e);
            }
        } else if (oldCount > 0) {
            global.clearUnreadMessages();
        }

        if (ACTIVITY_CHECK_ACTION.equals(action)) {
            global.sendBroadcast(new Intent(MAIL_CHECK_COMPLETE)); // notify activity
        } else if (global.mRedditData.getInboxCount() > 0) {
            setNotification(global);
        }
        return Result.success();
    }

    private void setNotification(Reddinator global) {
        int numMessages = global.mRedditData.getInboxCount();
        Intent notifyIntent = new Intent(global, MessagesActivity.class);
        notifyIntent.setAction(MessagesActivity.ACTION_UNREAD);
        NotificationManager notificationManager = global.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, global.getString(R.string.mail_channel_name), NotificationManager.IMPORTANCE_DEFAULT));
        Notification notification = new NotificationCompat.Builder(global, CHANNEL_ID)
                .setContentTitle(global.getResources().getQuantityString(R.plurals.new_messages, numMessages, numMessages))
                .setContentText(global.getResources().getString(R.string.new_messages_text))
                .setLargeIcon(BitmapFactory.decodeResource(global.getResources(), R.drawable.reddinator_logo))
                .setSmallIcon(R.drawable.ic_notify)
                .setContentIntent(PendingIntent.getActivity(global, 0, notifyIntent, Utilities.pendingFlags(false)))
                .build();
        notificationManager.notify(1, notification);
    }
}