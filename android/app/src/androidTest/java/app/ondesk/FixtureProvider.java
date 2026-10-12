package app.ondesk;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Standalone synthetic provider in the test APK; no dependency on target-app classes. */
public final class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return uri.getLastPathSegment().endsWith(".png") ? "image/png" : "text/plain"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor result = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) if (columns[i].equals(OpenableColumns.DISPLAY_NAME)) row[i] = uri.getLastPathSegment();
        result.addRow(row); return result;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            new Thread(() -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    if ("slow.txt".equals(uri.getLastPathSegment())) {
                        try { Thread.sleep(1500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    }
                    if (getType(uri).equals("image/png")) {
                        try (InputStream in = getContext().getAssets().open("fixture-photo.png")) {
                            byte[] bytes = new byte[65536]; int n;
                            while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
                        }
                    } else out.write("abc".getBytes(StandardCharsets.UTF_8));
                } catch (IOException error) { /* Reader closure is expected when an import is cancelled. */ }
            }).start();
            return pipe[0];
        } catch (IOException error) { throw new java.io.FileNotFoundException("Fixture pipe unavailable"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
