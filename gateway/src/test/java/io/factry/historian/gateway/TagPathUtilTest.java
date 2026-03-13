package io.factry.historian.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TagPathUtilTest {

    // --- extractComponent ---

    @Test
    void extractComponent_sys() {
        assertEquals("Ignition-abc",
                TagPathUtil.extractComponent(
                        "histprov:test:/sys:Ignition-abc:/prov:default:/tag:Temp", "sys:"));
    }

    @Test
    void extractComponent_prov_notMatchingHistprov() {
        // "prov:" must NOT match the "prov" inside "histprov:"
        assertEquals("default",
                TagPathUtil.extractComponent(
                        "histprov:timescale historian:/sys:Ignition-abc:/prov:default:/tag:Temp", "prov:"));
    }

    @Test
    void extractComponent_tag() {
        assertEquals("FactrySim/ii2",
                TagPathUtil.extractComponent(
                        "histprov:test:/sys:Ignition-abc:/prov:default:/tag:FactrySim/ii2", "tag:"));
    }

    @Test
    void extractComponent_tagAtEnd() {
        assertEquals("Temperature",
                TagPathUtil.extractComponent(
                        "sys:Ignition-abc:/prov:default:/tag:Temperature", "tag:"));
    }

    @Test
    void extractComponent_notFound() {
        assertNull(TagPathUtil.extractComponent("sys:Ignition-abc:/tag:Temp", "prov:"));
    }

    @Test
    void extractComponent_atStartOfString() {
        assertEquals("Ignition-abc",
                TagPathUtil.extractComponent("sys:Ignition-abc:/prov:default", "sys:"));
    }

    // --- qualifiedPathToStoredPath ---

    @Test
    void qualifiedPathToStoredPath_fullPath() {
        assertEquals("Ignition-296a8ca4b6cd:[default]Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:timescale historian:/sys:Ignition-296a8ca4b6cd:/prov:default:/tag:Temperature"));
    }

    @Test
    void qualifiedPathToStoredPath_nestedTag() {
        assertEquals("GW-01:[default]Simulation/Pressure",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/sys:GW-01:/prov:default:/tag:Simulation/Pressure"));
    }

    @Test
    void qualifiedPathToStoredPath_withoutHistprov() {
        assertEquals("Ignition-abc:[myProvider]Temp",
                TagPathUtil.qualifiedPathToStoredPath(
                        "sys:Ignition-abc:/prov:myProvider:/tag:Temp"));
    }

    @Test
    void qualifiedPathToStoredPath_noProv_defaultsToDefault() {
        assertEquals("Ignition-abc:[default]Temp",
                TagPathUtil.qualifiedPathToStoredPath(
                        "sys:Ignition-abc:/tag:Temp"));
    }

    @Test
    void qualifiedPathToStoredPath_browseOriginated() {
        // Browse paths have: histprov:xxx:/tag:SysName/ProvName/TagPath
        assertEquals("Ignition-abc:[default]Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Ignition-abc/default/Temperature"));
    }

    @Test
    void qualifiedPathToStoredPath_browseOriginatedNested() {
        assertEquals("Ignition-abc:[default]Simulation/Pressure",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Ignition-abc/default/Simulation/Pressure"));
    }

    // --- storedPathToDisplayPath ---

    @Test
    void storedPathToDisplayPath_standard() {
        assertEquals("Ignition-296a8ca4b6cd/default/Temperature",
                TagPathUtil.storedPathToDisplayPath(
                        "Ignition-296a8ca4b6cd:[default]Temperature"));
    }

    @Test
    void storedPathToDisplayPath_nested() {
        assertEquals("Ignition-296a8ca4b6cd/default/Simulation/Pressure",
                TagPathUtil.storedPathToDisplayPath(
                        "Ignition-296a8ca4b6cd:[default]Simulation/Pressure"));
    }

    @Test
    void storedPathToDisplayPath_deeplyNested() {
        assertEquals("Ignition-296a8ca4b6cd/default/FactrySim/ii2",
                TagPathUtil.storedPathToDisplayPath(
                        "Ignition-296a8ca4b6cd:[default]FactrySim/ii2"));
    }

    @Test
    void storedPathToDisplayPath_unknownFormat_passthrough() {
        assertEquals("some-other-format",
                TagPathUtil.storedPathToDisplayPath("some-other-format"));
    }

    // --- parseFolderPrefix ---

    @Test
    void parseFolderPrefix_null_returnsEmpty() {
        assertEquals("", TagPathUtil.parseFolderPrefix(null));
    }

    @Test
    void parseFolderPrefix_empty_returnsEmpty() {
        assertEquals("", TagPathUtil.parseFolderPrefix(""));
    }

    @Test
    void parseFolderPrefix_noFolderComponent_returnsEmpty() {
        assertEquals("", TagPathUtil.parseFolderPrefix("histprov:Timescale historian"));
    }

    @Test
    void parseFolderPrefix_singleFolder() {
        assertEquals("Ignition-296a8ca4b6cd/",
                TagPathUtil.parseFolderPrefix(
                        "histprov:Timescale historian:/folder:Ignition-296a8ca4b6cd"));
    }

    @Test
    void parseFolderPrefix_twoFolders() {
        assertEquals("Ignition-296a8ca4b6cd/default/",
                TagPathUtil.parseFolderPrefix(
                        "histprov:Timescale historian:/folder:Ignition-296a8ca4b6cd:/folder:default"));
    }

    @Test
    void parseFolderPrefix_threeFolders() {
        assertEquals("Ignition-296a8ca4b6cd/default/Simulation/",
                TagPathUtil.parseFolderPrefix(
                        "histprov:Timescale historian:/folder:Ignition-296a8ca4b6cd:/folder:default:/folder:Simulation"));
    }

    @Test
    void parseFolderPrefix_folderWithDashes() {
        assertEquals("my-gateway-01/",
                TagPathUtil.parseFolderPrefix("histprov:test:/folder:my-gateway-01"));
    }

    // --- extractCategory ---

    @Test
    void extractCategory_measurements() {
        assertEquals("Measurements", TagPathUtil.extractCategory("Measurements/Ignition-abc/default/Temp"));
    }

    @Test
    void extractCategory_calculations() {
        assertEquals("Calculations", TagPathUtil.extractCategory("Calculations/Avg_Temperature"));
    }

    @Test
    void extractCategory_assets() {
        assertEquals("Assets", TagPathUtil.extractCategory("Assets/Plant/Line1/Motor1"));
    }

    @Test
    void extractCategory_noCategory() {
        assertNull(TagPathUtil.extractCategory("Ignition-abc/default/Temp"));
    }

    @Test
    void extractCategory_null() {
        assertNull(TagPathUtil.extractCategory(null));
    }

    @Test
    void extractCategory_exactMatch() {
        assertEquals("Measurements", TagPathUtil.extractCategory("Measurements"));
    }

    // --- stripCategory ---

    @Test
    void stripCategory_measurements() {
        assertEquals("Ignition-abc/default/Temp",
                TagPathUtil.stripCategory("Measurements/Ignition-abc/default/Temp"));
    }

    @Test
    void stripCategory_calculations() {
        assertEquals("Avg_Temperature",
                TagPathUtil.stripCategory("Calculations/Avg_Temperature"));
    }

    @Test
    void stripCategory_assets() {
        assertEquals("Plant/Line1/Motor1",
                TagPathUtil.stripCategory("Assets/Plant/Line1/Motor1"));
    }

    @Test
    void stripCategory_noCategory() {
        assertEquals("Ignition-abc/default/Temp",
                TagPathUtil.stripCategory("Ignition-abc/default/Temp"));
    }

    @Test
    void stripCategory_exactMatch() {
        assertEquals("", TagPathUtil.stripCategory("Measurements"));
    }

    // --- qualifiedPathToStoredPath with category prefixes ---

    @Test
    void qualifiedPathToStoredPath_measurementCategory() {
        assertEquals("Ignition-abc:[default]Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Measurements/Ignition-abc/default/Temperature"));
    }

    @Test
    void qualifiedPathToStoredPath_calculationCategory() {
        assertEquals("Avg_Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Calculations/Avg_Temperature"));
    }

    @Test
    void qualifiedPathToStoredPath_assetCategory() {
        assertEquals("Plant/Line1/Motor1",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Assets/Plant/Line1/Motor1"));
    }

    // --- roundtrip: store → display → browse query → stored ---

    @Test
    void roundtrip_storeAndBrowseBack() {
        String stored = "Ignition-abc:[default]Simulation/Pressure";

        // Store → display path (for browse tree)
        String display = TagPathUtil.storedPathToDisplayPath(stored);
        assertEquals("Ignition-abc/default/Simulation/Pressure", display);

        // User selects from browse tree → framework sends histprov path with tag component
        String browseQuery = "histprov:test:/tag:" + display;
        String roundtripped = TagPathUtil.qualifiedPathToStoredPath(browseQuery);
        assertEquals(stored, roundtripped);
    }
}
