package io.factry.historian.gateway;

/**
 * Utility methods for converting between Ignition QualifiedPath strings
 * and Factry measurement names.
 * <p>
 * Measurement name format: {@code "collectorName/provider/tagPath"}
 * <p>
 * Example: {@code "Ignition/default/FactrySim/ff1"}
 * <p>
 * No Ignition SDK dependencies — pure string operations, easy to unit test.
 */
final class TagPathUtil {

    static final String CATEGORY_MEASUREMENTS = "Measurements";
    static final String CATEGORY_ASSETS = "Assets";

    private TagPathUtil() {}

    /**
     * Extract a named component value from a QualifiedPath string representation.
     * Components are separated by {@code ":/"}. The match must occur at a component
     * boundary (start of string or after {@code "/"}) to avoid false positives
     * like matching {@code "prov:"} inside {@code "histprov:"}.
     *
     * @param path   the full QualifiedPath string
     * @param prefix the component prefix including colon, e.g. {@code "sys:"}, {@code "prov:"}, {@code "tag:"}
     * @return the component value, or null if not found
     */
    static String extractComponent(String path, String prefix) {
        int searchFrom = 0;
        while (true) {
            int idx = path.indexOf(prefix, searchFrom);
            if (idx < 0) return null;
            // Must be at a component boundary: start of string or after "/"
            if (idx == 0 || path.charAt(idx - 1) == '/') {
                int start = idx + prefix.length();
                int end = path.indexOf(":/", start);
                return end >= 0 ? path.substring(start, end) : path.substring(start);
            }
            searchFrom = idx + 1;
        }
    }

    /**
     * Build the measurement name from components.
     * Format: {@code "collectorName/prov/tag"}
     *
     * @return measurement name, e.g. {@code "Ignition/default/FactrySim/ff1"}
     */
    static String buildStoredPath(String collectorName, String prov, String tag) {
        if (prov == null) prov = "default";
        return collectorName + "/" + prov + "/" + tag;
    }

    /**
     * Convert a QualifiedPath string to the Factry measurement name format.
     * <p>
     * Handles multiple input formats:
     * <ul>
     *   <li>Storage path: {@code sys:X:/prov:Y:/tag:Z} → {@code collectorName/Y/Z}</li>
     *   <li>Browse with folders: {@code histprov:xxx:/folder:A:/folder:B:/tag:C} → {@code A/B/C}
     *       (category prefix stripped if present)</li>
     *   <li>Browse without folders: {@code histprov:xxx:/tag:collectorName/prov/tag}
     *       → {@code collectorName/prov/tag} (already in correct format)</li>
     * </ul>
     *
     * @param qualifiedPathStr the QualifiedPath.toString() result
     * @param collectorName    the collector name from the JWT token
     * @return the Factry measurement name
     */
    static String qualifiedPathToStoredPath(String qualifiedPathStr, String collectorName) {
        String prov = extractComponent(qualifiedPathStr, "prov:");
        String tag = extractComponent(qualifiedPathStr, "tag:");

        // 1. Storage path with explicit prov: component → build collectorName/prov/tag
        if (prov != null && tag != null) {
            return buildStoredPath(collectorName, prov, tag);
        }

        if (tag != null) {
            // 2. Browse with folder: components → reconstruct from folders + tag leaf
            String folderPrefix = parseFolderPrefix(qualifiedPathStr);
            if (!folderPrefix.isEmpty()) {
                String strippedPrefix = stripCategory(folderPrefix);
                if (strippedPrefix.length() < folderPrefix.length()) {
                    String category = extractCategory(folderPrefix);
                    if (CATEGORY_ASSETS.equals(category)) {
                        return strippedPrefix + tag;
                    }
                    folderPrefix = strippedPrefix;
                }
                return folderPrefix + tag;
            }

            // 3. Browse without folders — tag already contains the full measurement name
            //    Strip category prefix if present
            String category = extractCategory(tag);
            if (category != null) {
                return stripCategory(tag);
            }

            return tag;
        }

        return qualifiedPathStr;
    }

    /**
     * Extract the category prefix from a display/browse path.
     * Returns "Measurements", "Assets", or null if no category prefix.
     */
    static String extractCategory(String displayPath) {
        if (displayPath == null) return null;
        if (displayPath.startsWith(CATEGORY_MEASUREMENTS + "/") || displayPath.equals(CATEGORY_MEASUREMENTS)) {
            return CATEGORY_MEASUREMENTS;
        }
        if (displayPath.startsWith(CATEGORY_ASSETS + "/") || displayPath.equals(CATEGORY_ASSETS)) {
            return CATEGORY_ASSETS;
        }
        return null;
    }

    /**
     * Strip the category prefix from a display/browse path.
     * {@code "Measurements/Ignition/default/Tag"} → {@code "Ignition/default/Tag"}
     */
    static String stripCategory(String displayPath) {
        if (displayPath == null) return null;
        String category = extractCategory(displayPath);
        if (category == null) return displayPath;
        if (displayPath.length() == category.length()) return "";
        return displayPath.substring(category.length() + 1); // +1 for the "/"
    }

    /**
     * Parse all {@code folder:} components from a QualifiedPath string
     * and join them into a slash-separated prefix.
     * <p>
     * {@code "histprov:xxx:/folder:Ignition:/folder:default"}
     * → {@code "Ignition/default/"}
     * <p>
     * Returns empty string if no folder components are found.
     */
    static String parseFolderPrefix(String qualifiedPathStr) {
        if (qualifiedPathStr == null || qualifiedPathStr.isEmpty()) {
            return "";
        }

        StringBuilder prefix = new StringBuilder();
        int idx = 0;
        while (true) {
            idx = qualifiedPathStr.indexOf("folder:", idx);
            if (idx < 0) break;
            // Ensure we're at a component boundary (start of string or after "/")
            if (idx > 0 && qualifiedPathStr.charAt(idx - 1) != '/') {
                idx += 7;
                continue;
            }
            int start = idx + 7; // "folder:".length()
            int end = qualifiedPathStr.indexOf(":/", start);
            String folderName = end >= 0
                    ? qualifiedPathStr.substring(start, end)
                    : qualifiedPathStr.substring(start);
            if (prefix.length() > 0) prefix.append("/");
            prefix.append(folderName);
            idx = start;
        }

        if (prefix.length() > 0) {
            prefix.append("/");
        }
        return prefix.toString();
    }
}
