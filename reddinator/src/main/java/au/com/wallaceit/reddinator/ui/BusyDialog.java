package au.com.wallaceit.reddinator.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import au.com.wallaceit.reddinator.R;

/**
 * Replacement for the deprecated ProgressDialog: an indeterminate spinner plus a message.
 * Like ProgressDialog.show(), it is not cancelable unless asked.
 */
public class BusyDialog {
    private final AlertDialog dialog;
    private final TextView messageView;

    private BusyDialog(Context context, CharSequence title, CharSequence message, boolean cancelable) {
        @SuppressLint("InflateParams")
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_busy, null);
        messageView = view.findViewById(R.id.busy_message);
        messageView.setText(message);
        dialog = new AlertDialog.Builder(context)
                .setTitle(title)
                .setView(view)
                .setCancelable(cancelable)
                .create();
    }

    public static BusyDialog show(Context context, CharSequence title, CharSequence message) {
        return show(context, title, message, false);
    }

    public static BusyDialog show(Context context, CharSequence title, CharSequence message, boolean cancelable) {
        BusyDialog busy = new BusyDialog(context, title, message, cancelable);
        busy.dialog.show();
        return busy;
    }

    public void setTitle(CharSequence title) {
        dialog.setTitle(title);
    }

    public void setMessage(CharSequence message) {
        messageView.setText(message);
    }

    public void dismiss() {
        dialog.dismiss();
    }

    public void cancel() {
        dialog.cancel();
    }
}