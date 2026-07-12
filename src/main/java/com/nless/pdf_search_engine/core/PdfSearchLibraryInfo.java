package com.nless.pdf_search_engine.core;

import android.os.Build;

import com.nless.pdf_search_engine.BuildConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Build and compatibility information for diagnostics and release reports.
 */
public final class PdfSearchLibraryInfo {

    private static final List<String> PACKAGED_ABIS = parsePackagedAbis();

    private PdfSearchLibraryInfo() {
    }

    /** Returns the semantic version embedded by Gradle. */
    public static String getVersionName() {
        return BuildConfig.LIBRARY_VERSION;
    }

    /** Returns the Maven artifact id embedded by Gradle. */
    public static String getArtifactId() {
        return BuildConfig.LIBRARY_ARTIFACT;
    }

    /**
     * Returns the ABIs physically packaged by this release.
     *
     * <p>The returned list is immutable and follows the order configured in
     * {@code SUPPORTED_ABIS}.</p>
     */
    public static List<String> getPackagedAbis() {
        return PACKAGED_ABIS;
    }

    /** Returns whether the current device advertises a packaged ABI. */
    public static boolean isCurrentDeviceAbiSupported() {
        for (String deviceAbi : Build.SUPPORTED_ABIS) {
            if (PACKAGED_ABIS.contains(deviceAbi)) {
                return true;
            }
        }
        return false;
    }

    /** Returns the cache/protocol versions useful in diagnostics. */
    public static String getIndexVersionSummary() {
        return PdfSearchVersions.pageIndexNamespace();
    }

    private static List<String> parsePackagedAbis() {
        String raw = BuildConfig.LIBRARY_ABIS;
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        for (String token : raw.split(",")) {
            String abi = token.trim();
            if (!abi.isEmpty() && !result.contains(abi)) {
                result.add(abi);
            }
        }
        return Collections.unmodifiableList(result);
    }
}
