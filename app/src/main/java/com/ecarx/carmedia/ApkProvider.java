package com.ecarx.carmedia;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 在线更新 APK 分享 Provider（新增）。
 * <p>
 * 作用：让系统安装器通过 content:// URI 读取缓存目录里的更新包，
 * 避免 targetSdk>=24 下 Uri.fromFile(file://) 触发 FileUriExposedException。
 * 机制与 ArtworkContentProvider 相同（ContentProvider 跨进程共享文件）。
 */
public class ApkProvider extends ContentProvider {
    private static final String TAG = "ApkProvider";

    public static final String AUTHORITY = "com.ecarx.carmedia.apk";
 /** 更新包文件名（与 MainActivity.installApkBytes 写入的缓存文件名一致） */
    public static final String APK_NAME = "carmedia_update.apk";

    /** 构造更新包 URI：content://com.ecarx.carmedia.apk/carmedia_update.apk */
    public static Uri buildApkUri() {
        return Uri.parse("content://" + AUTHORITY + "/" + APK_NAME);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        // 只允许读取固定的更新包文件名，防路径穿越
        if (name == null || !name.equals(APK_NAME) || name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("Invalid filename: " + name);
        }
        File f = new File(getContext().getCacheDir(), APK_NAME);
        if (!f.exists()) {
            Log.w(TAG, "apk not found: " + f.getAbsolutePath());
            throw new FileNotFoundException("apk not found");
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
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
}
