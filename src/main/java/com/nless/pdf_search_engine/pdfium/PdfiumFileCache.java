package com.nless.pdf_search_engine.pdfium;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** 为只接受文件路径的 PDFium 动态库准备稳定的本地副本。 */
final class PdfiumFileCache {

    private PdfiumFileCache() {
    }

    static File copy(Context context, Uri uri, String fingerprint) throws Exception {
        File directory = new File(context.getCacheDir(), "pdfium_text_search");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("cannot create pdfium cache directory");
        }
        String name = sha256(fingerprint != null ? fingerprint : uri.toString()) + ".pdf";
        File target = new File(directory, name);
        if (target.isFile() && target.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            target.setLastModified(System.currentTimeMillis());
            return target;
        }

        File temporary = new File(directory, name + ".tmp-" + Long.toHexString(System.nanoTime()));
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             BufferedInputStream input = raw != null ? new BufferedInputStream(raw) : null;
             FileOutputStream fileOutput = new FileOutputStream(temporary);
             BufferedOutputStream output = new BufferedOutputStream(fileOutput)) {
            if (input == null) throw new IllegalStateException("cannot open PDF URI");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.flush();
            fileOutput.getFD().sync();
        } catch (Throwable error) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw error;
        }

        if (!temporary.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IllegalStateException("cannot commit PDFium cache file");
        }
        trim(directory, 4);
        return target;
    }

    private static void trim(File directory, int maxFiles) {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".pdf"));
        if (files == null || files.length <= maxFiles) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - maxFiles; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (byte item : hash) out.append(String.format("%02x", item & 0xff));
            return out.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
