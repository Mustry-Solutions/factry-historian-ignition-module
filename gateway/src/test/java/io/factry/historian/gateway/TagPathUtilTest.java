package io.factry.historian.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TagPathUtilTest {

    private static final String COLLECTOR = "Ignition";

    // --- extractComponent ---

    @Test
    void extractComponent_sys() {
        assertEquals("Ignition-abc",
                TagPathUtil.extractComponent(
                        "histprov:test:/sys:Ignition-abc:/prov:default:/tag:Temp", "sys:"));
    }

    @Test
    void extractComponent_prov_notMatchingHistprov() {
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

    // --- buildStoredPath ---

    @Test
    void buildStoredPath_standard() {
        assertEquals("Ignition/default/Temperature",
                TagPathUtil.buildStoredPath("Ignition", "default", "Temperature"));
    }

    @Test
    void buildStoredPath_nullProv_defaultsToDefault() {
        assertEquals("Ignition/default/Temperature",
                TagPathUtil.buildStoredPath("Ignition", null, "Temperature"));
    }

    @Test
    void buildStoredPath_nestedTag() {
        assertEquals("Ignition/default/FactrySim/ff1",
                TagPathUtil.buildStoredPath("Ignition", "default", "FactrySim/ff1"));
    }

    // --- qualifiedPathToStoredPath (storage paths with prov: component) ---

    @Test
    void qualifiedPathToStoredPath_fullPath() {
        assertEquals("Ignition/default/Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:timescale historian:/sys:Ignition-296a8ca4b6cd:/prov:default:/tag:Temperature",
                        COLLECTOR));
    }

    @Test
    void qualifiedPathToStoredPath_nestedTag() {
        assertEquals("Ignition/default/Simulation/Pressure",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/sys:GW-01:/prov:default:/tag:Simulation/Pressure",
                        COLLECTOR));
    }

    @Test
    void qualifiedPathToStoredPath_withoutHistprov() {
        assertEquals("Ignition/myProvider/Temp",
                TagPathUtil.qualifiedPathToStoredPath(
                        "sys:Ignition-abc:/prov:myProvider:/tag:Temp",
                        COLLECTOR));
    }

    @Test
    void qualifiedPathToStoredPath_noProv_tagOnly_returnsTagAsIs() {
        // No prov: → browse-originated, tag already contains full path
        assertEquals("Ignition/default/Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Ignition/default/Temperature",
                        COLLECTOR));
    }

    // --- qualifiedPathToStoredPath with folder: components ---

    @Test
    void qualifiedPathToStoredPath_withFolders() {
        assertEquals("Ignition/default/Simulation/Pressure",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/folder:Ignition:/folder:default:/folder:Simulation:/tag:Pressure",
                        COLLECTOR));
    }

    @Test
    void qualifiedPathToStoredPath_withFolders_measurementCategory() {
        assertEquals("Ignition/default/Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/folder:Measurements:/folder:Ignition:/folder:default:/tag:Temperature",
                        COLLECTOR));
    }

    @Test
    void qualifiedPathToStoredPath_withFolders_assetCategory() {
        assertEquals("Plant/Line1/Motor1",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/folder:Assets:/folder:Plant:/folder:Line1:/tag:Motor1",
                        COLLECTOR));
    }

    // --- qualifiedPathToStoredPath with category prefixes (no folders) ---

    @Test
    void qualifiedPathToStoredPath_measurementCategory() {
        assertEquals("Ignition/default/Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Measurements/Ignition/default/Temperature",
                        COLLECTOR));
    }

    @Test
    void qualifiedPathToStoredPath_assetCategory() {
        assertEquals("Plant/Line1/Motor1",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Assets/Plant/Line1/Motor1",
                        COLLECTOR));
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
        assertEquals("Ignition/",
                TagPathUtil.parseFolderPrefix(
                        "histprov:Timescale historian:/folder:Ignition"));
    }

    @Test
    void parseFolderPrefix_twoFolders() {
        assertEquals("Ignition/default/",
                TagPathUtil.parseFolderPrefix(
                        "histprov:Timescale historian:/folder:Ignition:/folder:default"));
    }

    @Test
    void parseFolderPrefix_threeFolders() {
        assertEquals("Ignition/default/Simulation/",
                TagPathUtil.parseFolderPrefix(
                        "histprov:Timescale historian:/folder:Ignition:/folder:default:/folder:Simulation"));
    }

    // --- extractCategory ---

    @Test
    void extractCategory_measurements() {
        assertEquals("Measurements", TagPathUtil.extractCategory("Measurements/Ignition/default/Temp"));
    }

    @Test
    void extractCategory_assets() {
        assertEquals("Assets", TagPathUtil.extractCategory("Assets/Plant/Line1/Motor1"));
    }

    @Test
    void extractCategory_noCategory() {
        assertNull(TagPathUtil.extractCategory("Ignition/default/Temp"));
    }

    @Test
    void extractCategory_null() {
        assertNull(TagPathUtil.extractCategory(null));
    }

    // --- stripCategory ---

    @Test
    void stripCategory_measurements() {
        assertEquals("Ignition/default/Temp",
                TagPathUtil.stripCategory("Measurements/Ignition/default/Temp"));
    }

    @Test
    void stripCategory_noCategory() {
        assertEquals("Ignition/default/Temp",
                TagPathUtil.stripCategory("Ignition/default/Temp"));
    }

    // --- roundtrip: storage → browse query → stored ---

    @Test
    void roundtrip_storageAndBrowseBack() {
        // Storage: QualifiedPath with prov: → measurement name
        String stored = TagPathUtil.qualifiedPathToStoredPath(
                "sys:GW-01:/prov:default:/tag:Simulation/Pressure", COLLECTOR);
        assertEquals("Ignition/default/Simulation/Pressure", stored);

        // Browse: user selects tag → framework sends path with tag component
        String browseQuery = "histprov:test:/tag:" + stored;
        String roundtripped = TagPathUtil.qualifiedPathToStoredPath(browseQuery, COLLECTOR);
        assertEquals(stored, roundtripped);
    }

    @Test
    void roundtrip_storageAndFolderBrowseBack() {
        String stored = TagPathUtil.qualifiedPathToStoredPath(
                "sys:GW-01:/prov:default:/tag:Simulation/Pressure", COLLECTOR);
        assertEquals("Ignition/default/Simulation/Pressure", stored);

        // Browse tree creates folder: components under Measurements category
        String browsePath = "histprov:test:/folder:Measurements:/folder:Ignition:/folder:default:/folder:Simulation:/tag:Pressure";
        String result = TagPathUtil.qualifiedPathToStoredPath(browsePath, COLLECTOR);
        assertEquals(stored, result);
    }
}
