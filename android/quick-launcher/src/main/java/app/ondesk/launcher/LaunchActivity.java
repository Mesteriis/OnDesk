package app.ondesk.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

/** Optional entry point for gesture apps that can only launch a package. */
public final class LaunchActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            startActivity(new Intent().setClassName("app.ondesk", BuildConfig.TARGET_ACTIVITY));
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, "Установите OnDesk 0.2.0 или новее", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
