package com.nless.pdf_search_engine.cache;

import android.content.Context;
import android.graphics.RectF;

import com.nless.pdf_search_engine.ocr.OcrPageResult;
import com.nless.pdf_search_engine.ocr.OcrTextBlock;

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
 * 只持久化 OCR 文本和几何信息，不缓存页面 Bitmap。
 *
 * <p>单页写入采用临时文件、fsync、备份替换和 CRC32 校验。应用被杀或文件损坏时，
 * 缓存会自动删除并由上层重新 OCR，不会终止整个全文任务。</p>
 */
public class OcrDiskCache {

    private static final int MAGIC = 0x504F4352; // POCR
    private static final int FORMAT_VERSION = 2;
    private static final int MAX_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_BLOCKS = 20_000;
    private static final int MAX_STRING_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ARRAY_LENGTH = 2_000_000;

    private final File directory;
    private long maxBytes;

    public OcrDiskCache(Context context, long maxBytes) {
        File base = context.getNoBackupFilesDir();
        directory = new File(base, "pdf_search_ocr_cache");
        this.maxBytes = Math.max(1L, maxBytes);
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        recoverBackupsAndRemoveTemporaryFiles();
    }

    public synchronized void setMaxBytes(long maxBytes) {
        this.maxBytes = Math.max(1L, maxBytes);
        trimToSize();
    }

    public synchronized OcrPageResult get(String key) {
        if (key == null || key.isEmpty()) return null;
        File file = fileForKey(key);
        recoverBackup(file);
        if (!file.isFile() || file.length() < 16 || file.length() > MAX_FILE_BYTES) return null;

        try {
            byte[] fileBytes = readAll(file);
            int payloadLength = fileBytes.length - 8;
            if (payloadLength <= 0) throw new EOFException("short OCR cache");
            try (DataInputStream trailer = new DataInputStream(
                    new ByteArrayInputStream(fileBytes, payloadLength, 8))) {
                int storedLength = trailer.readInt();
                long storedCrc = trailer.readInt() & 0xffffffffL;
                if (storedLength != payloadLength) throw new EOFException("bad OCR length");
                CRC32 crc = new CRC32();
                crc.update(fileBytes, 0, payloadLength);
                if (crc.getValue() != storedCrc) throw new EOFException("bad OCR crc");
            }

            try (DataInputStream input = new DataInputStream(
                    new BufferedInputStream(new ByteArrayInputStream(
                            fileBytes, 0, payloadLength)))) {
                if (input.readInt() != MAGIC) throw new EOFException("bad OCR magic");
                if (input.readInt() != FORMAT_VERSION) throw new EOFException("bad OCR version");
                String storedKey = readString(input);
                if (!key.equals(storedKey)) throw new EOFException("wrong OCR key");

                int bitmapWidth = input.readInt();
                int bitmapHeight = input.readInt();
                float pageWidth = input.readFloat();
                float pageHeight = input.readFloat();
                int blockCount = input.readInt();
                if (bitmapWidth <= 0 || bitmapHeight <= 0
                        || pageWidth <= 0f || pageHeight <= 0f
                        || blockCount < 0 || blockCount > MAX_BLOCKS) {
                    throw new EOFException("bad OCR page metadata");
                }

                List<OcrTextBlock> blocks = new ArrayList<>(blockCount);
                for (int i = 0; i < blockCount; i++) {
                    String text = readString(input);
                    RectF rect = new RectF(
                            input.readFloat(),
                            input.readFloat(),
                            input.readFloat(),
                            input.readFloat()
                    );
                    float score = input.readFloat();
                    float[] quad = readFloatArray(input);
                    float[] tokenBoxes = readFloatArray(input);
                    int[] tokenStarts = readIntArray(input);
                    int[] tokenEnds = readIntArray(input);
                    blocks.add(new OcrTextBlock(
                            text,
                            rect,
                            score,
                            quad,
                            tokenBoxes,
                            tokenStarts,
                            tokenEnds
                    ));
                }

                // 作为 LRU 时间戳使用。
                //noinspection ResultOfMethodCallIgnored
                file.setLastModified(System.currentTimeMillis());
                return new OcrPageResult(
                        bitmapWidth,
                        bitmapHeight,
                        pageWidth,
                        pageHeight,
                        blocks
                );
            }
        } catch (Throwable error) {
            deleteQuietly(file);
            deleteQuietly(backupFor(file));
            return null;
        }
    }

    public synchronized void put(String key, OcrPageResult result) {
        if (key == null || key.isEmpty() || result == null) return;
        if (!directory.exists() && !directory.mkdirs()) return;

        File target = fileForKey(key);
        File temporary = new File(
                directory,
                target.getName() + ".tmp-" + Long.toHexString(System.nanoTime())
        );
        try {
            byte[] payload = serialize(key, result);
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
        } catch (Throwable error) {
            deleteQuietly(temporary);
            return;
        }

        if (!commit(target, temporary)) return;
        trimToSize();
    }

    public synchronized void clear() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) deleteQuietly(file);
    }

    public synchronized long sizeBytes() {
        long total = 0L;
        File[] files = directory.listFiles(file ->
                file.isFile() && file.getName().endsWith(".pocr"));
        if (files == null) return 0L;
        for (File file : files) total += Math.max(0L, file.length());
        return total;
    }

    private byte[] serialize(String key, OcrPageResult result) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(bytes))) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT_VERSION);
            writeString(output, key);
            output.writeInt(result.bitmapWidth);
            output.writeInt(result.bitmapHeight);
            output.writeFloat(result.pageWidth);
            output.writeFloat(result.pageHeight);

            List<OcrTextBlock> blocks = result.blocks != null
                    ? result.blocks
                    : new ArrayList<>();
            output.writeInt(Math.min(blocks.size(), MAX_BLOCKS));
            for (int i = 0; i < blocks.size() && i < MAX_BLOCKS; i++) {
                OcrTextBlock block = blocks.get(i);
                if (block == null || block.rectInBitmap == null) {
                    writeString(output, "");
                    output.writeFloat(0f);
                    output.writeFloat(0f);
                    output.writeFloat(0f);
                    output.writeFloat(0f);
                    output.writeFloat(0f);
                    writeFloatArray(output, null);
                    writeFloatArray(output, null);
                    writeIntArray(output, null);
                    writeIntArray(output, null);
                    continue;
                }
                writeString(output, block.text != null ? block.text : "");
                output.writeFloat(block.rectInBitmap.left);
                output.writeFloat(block.rectInBitmap.top);
                output.writeFloat(block.rectInBitmap.right);
                output.writeFloat(block.rectInBitmap.bottom);
                output.writeFloat(block.score);
                writeFloatArray(output, block.quadInBitmap);
                writeFloatArray(output, block.tokenBoxesInLine);
                writeIntArray(output, block.tokenUtf16Starts);
                writeIntArray(output, block.tokenUtf16Ends);
            }
            output.flush();
        }
        return bytes.toByteArray();
    }

    private void trimToSize() {
        File[] files = directory.listFiles(file ->
                file.isFile() && file.getName().endsWith(".pocr"));
        if (files == null || files.length == 0) return;

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

    private void recoverBackupsAndRemoveTemporaryFiles() {
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

    private File backupFor(File target) {
        return new File(target.getAbsolutePath() + ".bak");
    }

    private File fileForKey(String key) {
        return new File(directory, sha256(key) + ".pocr");
    }

    private static byte[] readAll(File file) throws Exception {
        int length = (int) file.length();
        byte[] values = new byte[length];
        try (FileInputStream raw = new FileInputStream(file);
             BufferedInputStream input = new BufferedInputStream(raw)) {
            int offset = 0;
            while (offset < values.length) {
                int read = input.read(values, offset, values.length - offset);
                if (read < 0) throw new EOFException("truncated OCR cache");
                offset += read;
            }
        }
        return values;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte item : hash) builder.append(String.format("%02x", item & 0xff));
            return builder.toString();
        } catch (Throwable ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) bytes = Arrays.copyOf(bytes, MAX_STRING_BYTES);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new EOFException("bad string");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeFloatArray(DataOutputStream output, float[] values) throws Exception {
        if (values == null) {
            output.writeInt(-1);
            return;
        }
        if (values.length > MAX_ARRAY_LENGTH) throw new IllegalArgumentException("float array too large");
        output.writeInt(values.length);
        for (float value : values) output.writeFloat(value);
    }

    private static float[] readFloatArray(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length < 0) return null;
        if (length > MAX_ARRAY_LENGTH) throw new EOFException("bad float array");
        float[] values = new float[length];
        for (int i = 0; i < length; i++) values[i] = input.readFloat();
        return values;
    }

    private static void writeIntArray(DataOutputStream output, int[] values) throws Exception {
        if (values == null) {
            output.writeInt(-1);
            return;
        }
        if (values.length > MAX_ARRAY_LENGTH) throw new IllegalArgumentException("int array too large");
        output.writeInt(values.length);
        for (int value : values) output.writeInt(value);
    }

    private static int[] readIntArray(DataInputStream input) throws Exception {
        int length = input.readInt();
        if (length < 0) return null;
        if (length > MAX_ARRAY_LENGTH) throw new EOFException("bad int array");
        int[] values = new int[length];
        for (int i = 0; i < length; i++) values[i] = input.readInt();
        return values;
    }

    private static boolean deleteQuietly(File file) {
        try {
            return file == null || !file.exists() || file.delete();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
