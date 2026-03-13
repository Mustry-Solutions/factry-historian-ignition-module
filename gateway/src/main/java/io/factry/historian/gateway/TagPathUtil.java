package io.factry.historian.gateway;

/**
 * Utility methods for converting between different tag path formats.
 * <p>
 * Stored format: {@code "Ignition-296a8ca4b6cd:[default]FactrySim/ii2"}
 * <p>
 * Display format (for browse tree): {@code "Ignition-296a8ca4b6cd/default/FactrySim/ii2"}
 * <p>
 * No Ignition SDK dependencies — pure string operations, easy to unit test.
 */
final class TagPathUtil {

    static final String CATEGORY_MEASUREMENTS = "Measurements";
    static final String CATEGORY_CALCULATIONS = "Calculations";
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
     * Build the stored tag path from individual components.
     * Format: {@code "sys:[prov]tag"}
     *
     * @return stored path, e.g. {@code "Ignition-abc:[default]Temperature"}
     */
    static String buildStoredPath(String sys, String prov, String tag) {
        if (prov == null) prov = "default";
        return sys + ":[" + prov + "]" + tag;
    }

    /**
     * Convert a QualifiedPath string to the stored tag_path format.
     * Handles both direct paths ({@code sys:/prov:/tag:}) and browse-originated
     * paths ({@code histprov:xxx:/tag:SysName/ProvName/TagPath}).
     *
     * @param qualifiedPathStr the QualifiedPath.toString() result
     * @return the stored path, e.g. {@code "Ignition-abc:[default]Temperature"}
     */
    static String qualifiedPathToStoredPath(String qualifiedPathStr) {
        String sys = extractComponent(qualifiedPathStr, "sys:");
        String prov = extractComponent(qualifiedPathStr, "prov:");
        String tag = extractComponent(qualifiedPathStr, "tag:");

        // Direct query with sys/prov/tag components
        if (sys != null && tag != null) {
            return buildStoredPath(sys, prov, tag);
        }

        // Browse-originated: "histprov:xxx:/tag:Category/..."
        if (tag != null) {
            String category = extractCategory(tag);
            if (category != null) {
                String stripped = stripCategory(tag);
                if (CATEGORY_CALCULATIONS.equals(category) || CATEGORY_ASSETS.equals(category)) {
                    // Calculations and Assets use their name directly (no sys:[prov] conversion)
                    return stripped;
                }
                // Measurements: strip category prefix, then apply existing sys/prov/tag conversion
                tag = stripped;
            }

            int firstSlash = tag.indexOf('/');
            if (firstSlash >= 0) {
                String sysName = tag.substring(0, firstSlash);
                String rest = tag.substring(firstSlash + 1);
                int secondSlash = rest.indexOf('/');
                if (secondSlash >= 0) {
                    String provName = rest.substring(0, secondSlash);
                    String tagPath = rest.substring(secondSlash + 1);
                    return buildStoredPath(sysName, provName, tagPath);
                }
            }
        }

        return qualifiedPathStr;
    }

    /**
     * Convert a stored path to a slash-separated display path for the browse tree.
     * <p>
     * {@code "Ignition-xxx:[default]FactrySim/ii2"}
     * → {@code "Ignition-xxx/default/FactrySim/ii2"}
     */
    static String storedPathToDisplayPath(String storedPath) {
        int colonIdx = storedPath.indexOf(":[");
        if (colonIdx >= 0) {
            String sys = storedPath.substring(0, colonIdx);
            int closeBracket = storedPath.indexOf(']', colonIdx);
            if (closeBracket >= 0) {
                String prov = storedPath.substring(colonIdx + 2, closeBracket);
                String tag = storedPath.substring(closeBracket + 1);
                return sys + "/" + prov + "/" + tag;
            }
        }
        return storedPath;
    }

    /**
     * Extract the category prefix from a display/browse path.
     * Returns "Measurements", "Calculations", "Assets", or null if no category prefix.
     */
    static String extractCategory(String displayPath) {
        if (displayPath == null) return null;
        if (displayPath.startsWith(CATEGORY_MEASUREMENTS + "/") || displayPath.equals(CATEGORY_MEASUREMENTS)) {
            return CATEGORY_MEASUREMENTS;
        }
        if (displayPath.startsWith(CATEGORY_CALCULATIONS + "/") || displayPath.equals(CATEGORY_CALCULATIONS)) {
            return CATEGORY_CALCULATIONS;
        }
        if (displayPath.startsWith(CATEGORY_ASSETS + "/") || displayPath.equals(CATEGORY_ASSETS)) {
            return CATEGORY_ASSETS;
        }
        return null;
    }

    /**
     * Strip the category prefix from a display/browse path.
     * {@code "Measurements/Ignition-xxx/default/Tag"} → {@code "Ignition-xxx/default/Tag"}
     * {@code "Calculations/Avg_Temperature"} → {@code "Avg_Temperature"}
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
     * {@code "histprov:xxx:/folder:Ignition-abc:/folder:default"}
     * → {@code "Ignition-abc/default/"}
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
