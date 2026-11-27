package com.ecarx.carmedia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 封面图 ContentProvider（移植自 MediaBridge / carMedia_fixed_icon_artwork）。
 *
 * EAS 小卡片/仪表盘通过
 *   content://com.ecarx.carmedia.artwork/artwork_<hash>.png
 * 读取封面文件（文件实体在 ArtworkHelper.getArtworkDir = filesDir/artwork）。
 *
 * 安全：openFile 拒绝含 ".." 或 "/" 的文件名（路径穿越防护）。
 * Manifest 注册：exported=true + grantUriPermissions=true
 * （系统 UI 进程需要跨进程读我们的 filesDir 文件，必须 exported）。
 */
public class ArtworkContentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.ecarx.carmedia.artwork";
    private static final String TAG = "ArtworkProvider";

    public static Uri buildUri(String fileName) {
        return Uri.parse("content://" + AUTHORITY + "/" + fileName);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String fileName = uri.getLastPathSegment();
        if (fileName != null
                && !fileName.contains("..")
                && !fileName.contains("/")) {
            File file = new File(getArtworkDir(getContext()), fileName);
            if (file.exists()) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            throw new FileNotFoundException("Artwork not found: " + fileName);
        }
        throw new FileNotFoundException("Invalid filename: " + fileName);
    }

    private File getArtworkDir(Context context) {
        return ArtworkHelper.getArtworkDir(context);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }
}
