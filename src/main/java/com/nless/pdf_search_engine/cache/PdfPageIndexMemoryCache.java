package com.nless.pdf_search_engine.cache;

import com.nless.pdf_search_engine.index.PdfPageIndex;

import java.util.LinkedHashMap;
import java.util.Map;

/** 小型页面索引 LRU，避免连续查询重复反序列化。 */
public final class PdfPageIndexMemoryCache {

    private final int maxEntries;
    private final LinkedHashMap<String, PdfPageIndex> values;

    public PdfPageIndexMemoryCache(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
        this.values = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PdfPageIndex> eldest) {
                return size() > PdfPageIndexMemoryCache.this.maxEntries;
            }
        };
    }

    public synchronized PdfPageIndex get(String key) {
        return key != null ? values.get(key) : null;
    }

    public synchronized void put(String key, PdfPageIndex value) {
        if (key != null && value != null) values.put(key, value);
    }

    public synchronized void clear() {
        values.clear();
    }
}
