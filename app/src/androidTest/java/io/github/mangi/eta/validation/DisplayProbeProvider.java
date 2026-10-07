package io.github.mangi.eta.validation;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import org.json.JSONObject;

public class DisplayProbeProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        SharedPreferences state = getContext().getSharedPreferences("display_probe", Context.MODE_PRIVATE);
        MatrixCursor result = new MatrixCursor(new String[] { "state" });
        result.addRow(new Object[] { new JSONObject(state.getAll()).toString() });
        return result;
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/eta-display-probe"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
}
