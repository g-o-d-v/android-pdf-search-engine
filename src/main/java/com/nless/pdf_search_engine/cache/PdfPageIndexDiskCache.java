package com.nless.pdf_search_engine.cache;

import android.content.Context;
import android.graphics.RectF;

import com.nless.pdf_search_engine.core.PdfSearchSource;
import com.nless.pdf_search_engine.core.PdfSearchVersions;
import com.nless.pdf_search_engine.index.PdfPageIndex;
import com.nless.pdf_search_engine.index.PdfTextToken;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 统一页面索引的持久化缓存。
 *
 * 文件包含 CRC32；写入采用 tmp -> target，失败时自动保留/恢复旧文件。
 */
public final class PdfPageIndexDiskCache {

    private static final int MAGIC = 0x50494458; // PIDX
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TOKENS = 5_000_000;

    private final File directory;
    private long maxBytes;

    public PdfPageIndexDiskCache(Context context, long maxBytes) {
        directory = new File(context.getNoBackupFilesDir(), "pdf_search_page_index");
        this.maxBytes = Math.max(1L, maxBytes);
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        recoverBackupsAndRemoveTemps();
    }

    public synchronized void setMaxBytes(long value) {
        maxBytes = Math.max(1L, value);
        trimToSize();
    }

    public synchronized PdfPageIndex get(String key) {
        if (key == null || key.isEmpty()) return null;
        File target = fileForKey(key);
        recoverBackup(target);
        if (!target.isFile() || target.length() <= 12 || target.length() > MAX_FILE_BYTES) {
            return null;
        }

        try {
            byte[] fileBytes = readAll(target);
            if (fileBytes.length < 12) throw new EOFException("short index cache");
            int payloadLength = fileBytes.length - 8;
            DataInputStream trailer = new DataInputStream(
                    new ByteArrayInputStream(fileBytes, payloadLength, 8)
            );
            int storedPayloadLength = trailer.readInt();
            long storedCrc = trailer.readInt() & 0xffffffffL;
            if (storedPayloadLength != payloadLength) throw new EOFException("bad length");
            CRC32 crc = new CRC32();
            crc.update(fileBytes, 0, payloadLength);
            if (crc.getValue() != storedCrc) throw new EOFException("bad crc");

            try (DataInputStream input = new DataInputStream(
                    new BufferedInputStream(new ByteArrayInputStream(fileBytes, 0, payloadLength)))) {
                if (input.readInt() != MAGIC) throw new EOFException("bad magic");
                if (input.readInt() != PdfSearchVersions.PAGE_INDEX_SERIALIZATION_VERSION) {
                    throw new EOFException("bad version");
                }
                String storedKey = readString(input, MAX_TEXT_BYTES);
                if (!key.equals(storedKey)) throw new EOFException("wrong key");

                int pageIndex = input.readInt();
                float pageWidth = input.readFloat();
                float pageHeight = input.readFloat();
                PdfSearchSource source = sourceFromOrdinal(input.readInt());
                int status = input.readInt();
                int rawChars = input.readInt();
                int visibleChars = input.readInt();
                int validBoxes = input.readInt();
                float confidence = input.readFloat();
                String originalText = readString(input, MAX_TEXT_BYTES);
                int tokenCount = input.readInt();
                if (tokenCount < 0 || tokenCount > MAX_TOKENS) {
                    throw new EOFException("bad token count");
                }

                List<PdfTextToken> tokens = new ArrayList<>(tokenCount);
                for (int i = 0; i < tokenCount; i++) {
                    String text = readString(input, 1024 * 1024);
                    int originalStart = input.readInt();
                    int originalEnd = input.readInt();
                    int lineIndex = input.readInt();
                    float tokenConfidence = input.readFloat();
                    boolean hasRect = input.readBoolean();
                    RectF rect = null;
                    if (hasRect) {
                        rect = new RectF(
                                input.readFloat(),
                                input.readFloat(),
                                input.readFloat(),
                                input.readFloat()
                        );
                    }
                    tokens.add(new PdfTextToken(
                            text,
                            originalStart,
                            originalEnd,
                            rect,
                            lineIndex,
                            tokenConfidence
                    ));
                }

                //noinspection ResultOfMethodCallIgnored
                target.setLastModified(System.currentTimeMillis());
                return new PdfPageIndex(
                        pageIndex,
                        pageWidth,
                        pageHeight,
                        source,
                        originalText,
                        tokens,
                        status,
                        rawChars,
                        visibleChars,
                        validBoxes,
                        confidence
                );
            }
        } catch (Throwable error) {
            deleteQuietly(target);
            deleteQuietly(backupFor(target));
            return null;
        }
    }

    public synchronized void put(String key, PdfPageIndex page) {
        if (key == null || key.isEmpty() || page == null) return;
        if (!directory.exists() && !directory.mkdirs()) return;

        File target = fileForKey(key);
        File temporary = new File(target.getAbsolutePath() + ".tmp-" + Long.toHexString(System.nanoTime()));
        try {
            byte[] payload = serialize(key, page);
            if (payload.length + 8 > MAX_FILE_BYTES) return;
            CRC32 crc = new CRC32();
            crc.update(payload);
            try (FileOutputStream fileOutput = new FileOutputStream(temporary);
                 DataOutputStream output = new DataOutputStream(
                         new BufferedOutputStream(fileOutput))) {
                output.write(payload);
                output.writeInt(payload.length);
                output.writeInt((int) crc.getValue());
                output.flush();
                fileOutput.getFD().sync();
            }
            if (!commit(target, temporary)) return;
            trimToSize();
        } catch (Throwable error) {
            deleteQuietly(temporary);
        }
    }

    public synchronized void clear() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) deleteQuietly(file);
    }

    public synchronized long sizeBytes() {
        long total = 0L;
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".pidx"));
        if (files != null) for (File file : files) total += Math.max(0L, file.length());
        return total;
    }

    private byte[] serialize(String key, PdfPageIndex page) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(bytes))) {
            output.writeInt(MAGIC);
            output.writeInt(PdfSearchVersions.PAGE_INDEX_SERIALIZATION_VERSION);
            writeString(output, key);
            output.writeInt(page.pageIndex);
            output.writeFloat(page.pageWidth);
            output.writeFloat(page.pageHeight);
            output.writeInt(page.source.ordinal());
            output.writeInt(page.status);
            output.writeInt(page.rawCharacterCount);
            output.writeInt(page.visibleCharacterCount);
            output.writeInt(page.validBoxCount);
            output.writeFloat(page.confidence);
            writeString(output, page.originalText);
            output.writeInt(Math.min(page.tokens.size(), MAX_TOKENS));
            for (int i = 0; i < page.tokens.size() && i < MAX_TOKENS; i++) {
                PdfTextToken token = page.tokens.get(i);
                writeString(output, token != null ? token.text : "");
                output.writeInt(token != null ? token.originalStart : 0);
                output.writeInt(token != null ? token.originalEnd : 0);
                output.writeInt(token != null ? token.lineIndex : 0);
                output.writeFloat(token != null ? token.confidence : -1f);
                boolean hasRect = token != null && token.rectInPdfPoint != null;
                output.writeBoolean(hasRect);
                if (hasRect) {
                    output.writeFloat(token.rectInPdfPoint.left);
                    output.writeFloat(token.rectInPdfPoint.top);
                    output.writeFloat(token.rectInPdfPoint.right);
                    output.writeFloat(token.rectInPdfPoint.bottom);
                }
            }
            output.flush();
        }
        return bytes.toByteArray();
    }

    private boolean commit(File target, File temporary) {
        File backup = backupFor(target);
        deleteQuietly(backup);
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(backup)) {
            deleteQuietly(temporary);
            return false;
        }
        if (temporary.renameTo(target)) {
            deleteQuietly(backup);
            return true;
        }
        if (hadTarget && backup.exists()) {
            //noinspection ResultOfMethodCallIgnored
            backup.renameTo(target);
        }
        deleteQuietly(temporary);
        return false;
    }

    private void recoverBackupsAndRemoveTemps() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.contains(".tmp-")) {
                deleteQuietly(file);
            } else if (name.endsWith(".bak")) {
                File target = new File(directory, name.substring(0, name.length() - 4));
                if (!target.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.renameTo(target);
                } else {
                    deleteQuietly(file);
                }
            }
        }
    }

    private void recoverBackup(File target) {
        File backup = backupFor(target);
        if (!target.exists() && backup.exists()) {
            //noinspection ResultOfMethodCallIgnored
            backup.renameTo(target);
        }
    }

    private void trimToSize() {
        File[] files = directory.listFiles(file -> file.isFile() && file.getName().endsWith(".pidx"));
        if (files == null) return;
        long total = 0L;
        for (File file : files) total += Math.max(0L, file.length());
        if (total <= maxBytes) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {
            if (total <= maxBytes) break;
            long length = Math.max(0L, file.length());
            if (deleteQuietly(file)) total -= length;
        }
    }

    private File fileForKey(String key) {
        return new File(directory, sha256(key) + ".pidx");
    }

    private File backupFor(File target) {
        return new File(target.getAbsolutePath() + ".bak");
    }

    private static byte[] readAll(File file) throws Exception {
        int length = (int) file.length();
        byte[] values = new byte[length];
        try (FileInputStream raw = new FileInputStream(file);
             BufferedInputStream input = new BufferedInputStream(raw)) {
            int offset = 0;
            while (offset < values.length) {
                int read = input.read(values, offset, values.length - offset);
                if (read < 0) throw new EOFException("truncated file");
                offset += read;
            }
        }
        return values;
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_TEXT_BYTES) throw new IllegalArgumentException("text too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maxBytes) throws Exception {
        int length = input.readInt();
        if (length < 0 || length > maxBytes) throw new EOFException("bad string length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static PdfSearchSource sourceFromOrdinal(int ordinal) {
        PdfSearchSource[] values = PdfSearchSource.values();
        if (ordinal < 0 || ordinal >= values.length) return PdfSearchSource.OCR;
        return values[ordinal];
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte item : hash) output.append(String.format("%02x", item & 0xff));
            return output.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean deleteQuietly(File file) {
        try {
            return file == null || !file.exists() || file.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
