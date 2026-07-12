package com.nless.pdf_search_engine.cache;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 构造用于缓存失效判断的轻量文档指纹。
 *
 * 除 URI、大小和修改时间外，还采样文件头尾各 64 KiB。这样即使某些
 * ContentProvider 不提供可靠修改时间，也能降低同 URI 内容被替换后误用旧缓存的概率。
 */
public final class PdfDocumentFingerprint {

    private static final int SAMPLE_BYTES = 64 * 1024;

    private PdfDocumentFingerprint() {
    }

    public static String build(Context context, Uri uri) {
        if (uri == null) return "uri=|sample=";

        Metadata metadata = readMetadata(context, uri);
        String sampleHash = sampleHash(context, uri, metadata.size);
        return "uri=" + uri
                + "|name=" + metadata.displayName
                + "|size=" + metadata.size
                + "|modified=" + metadata.modified
                + "|sample=" + sampleHash;
    }

    private static Metadata readMetadata(Context context, Uri uri) {
        long size = -1L;
        long modified = -1L;
        String displayName = "";

        if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
            File file = new File(uri.getPath());
            if (file.exists()) {
                size = file.length();
                modified = file.lastModified();
                displayName = file.getName();
            }
            return new Metadata(displayName, size, modified);
        }

        if (context == null) return new Metadata(displayName, size, modified);
        String[] projection = new String[]{
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = context.getContentResolver().query(
                uri, projection, null, null, null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                int modifiedIndex = cursor.getColumnIndex(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED
                );
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    modified = cursor.getLong(modifiedIndex);
                }
            }
        } catch (Throwable ignored) {
            try (Cursor cursor = context.getContentResolver().query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                    null,
                    null,
                    null
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex);
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex);
                    }
                }
            } catch (Throwable ignoredAgain) {
                // URI 仍然会参与指纹。
            }
        }
        return new Metadata(displayName, size, modified);
    }

    private static String sampleHash(Context context, Uri uri, long knownSize) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(uri.toString().getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(8).putLong(knownSize).array());

            if ("file".equalsIgnoreCase(uri.getScheme()) && uri.getPath() != null) {
                File file = new File(uri.getPath());
                if (file.isFile()) sampleFile(file, digest);
            } else if (context != null) {
                sampleContent(context, uri, knownSize, digest);
            }
            return hexPrefix(digest.digest(), 32);
        } catch (Throwable ignored) {
            return Integer.toHexString(uri.toString().hashCode());
        }
    }

    private static void sampleFile(File file, MessageDigest digest) throws Exception {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long length = input.length();
            byte[] buffer = new byte[SAMPLE_BYTES];
            int read = input.read(buffer);
            if (read > 0) digest.update(buffer, 0, read);
            if (length > SAMPLE_BYTES) {
                input.seek(Math.max(0L, length - SAMPLE_BYTES));
                read = input.read(buffer);
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
    }

    private static void sampleContent(
            Context context,
            Uri uri,
            long knownSize,
            MessageDigest digest
    ) throws Exception {
        try (ParcelFileDescriptor descriptor = context.getContentResolver()
                .openFileDescriptor(uri, "r")) {
            if (descriptor == null) return;
            long size = knownSize >= 0 ? knownSize : descriptor.getStatSize();
            try (FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
                byte[] buffer = new byte[SAMPLE_BYTES];
                int read = input.read(buffer);
                if (read > 0) digest.update(buffer, 0, read);

                if (size > SAMPLE_BYTES) {
                    try {
                        FileChannel channel = input.getChannel();
                        channel.position(Math.max(0L, size - SAMPLE_BYTES));
                        read = input.read(buffer);
                        if (read > 0) digest.update(buffer, 0, read);
                    } catch (Throwable ignored) {
                        // 不可 seek 的 provider 只使用文件头采样。
                    }
                }
            }
        }
    }

    private static String hexPrefix(byte[] bytes, int chars) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) output.append(String.format("%02x", item & 0xff));
        return output.substring(0, Math.min(chars, output.length()));
    }

    private static final class Metadata {
        final String displayName;
        final long size;
        final long modified;

        Metadata(String displayName, long size, long modified) {
            this.displayName = displayName != null ? displayName : "";
            this.size = size;
            this.modified = modified;
        }
    }
}
