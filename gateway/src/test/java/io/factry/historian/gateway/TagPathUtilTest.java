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
        assertEquals("default/Temperature",
                TagPathUtil.buildStoredPath("default", "Temperature"));
    }

    @Test
    void buildStoredPath_nullProv_defaultsToDefault() {
        assertEquals("default/Temperature",
                TagPathUtil.buildStoredPath(null, "Temperature"));
    }

    @Test
    void buildStoredPath_nestedTag() {
        assertEquals("default/FactrySim/ff1",
                TagPathUtil.buildStoredPath("default", "FactrySim/ff1"));
    }

    // --- qualifiedPathToStoredPath (storage paths with prov: component) ---

    @Test
    void qualifiedPathToStoredPath_fullPath() {
        assertEquals("default/Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:timescale historian:/sys:Ignition-296a8ca4b6cd:/prov:default:/tag:Temperature"));
    }

    @Test
    void qualifiedPathToStoredPath_nestedTag() {
        assertEquals("default/Simulation/Pressure",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/sys:GW-01:/prov:default:/tag:Simulation/Pressure"));
    }

    @Test
    void qualifiedPathToStoredPath_withoutHistprov() {
        assertEquals("myProvider/Temp",
                TagPathUtil.qualifiedPathToStoredPath(
                        "sys:Ignition-abc:/prov:myProvider:/tag:Temp"));
    }

    @Test
    void qualifiedPathToStoredPath_noProv_tagOnly_returnsTagAsIs() {
        // No prov: → browse-originated, tag already contains full path
        assertEquals("default/Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:default/Temperature"));
    }

    @Test
    void qualifiedPathToStoredPath_bindingPath_provAlreadyInTag() {
        // Tag history binding: [Factry Historian]default/FactrySim/ff1
        // Ignition sends prov:default AND tag:default/FactrySim/ff1 — don't double the prefix
        assertEquals("default/FactrySim/ff1",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:Factry Historian:/prov:default:/tag:default/FactrySim/ff1"));
    }

    @Test
    void qualifiedPathToStoredPath_sysProvTag_tagAlreadyHasProvPrefix() {
        // Tag history binding from historian browse: [Factry Historian]default/Manual Test/ii1
        // Ignition sends sys: + prov:default + tag:default/Manual Test/ii1 — don't double the prefix
        assertEquals("default/Manual Test/ii1",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:Factry Historian:/sys:Ignition-FactryTest:/prov:default:/tag:default/Manual Test/ii1"));
    }

    // --- qualifiedPathToStoredPath with folder: components ---

    @Test
    void qualifiedPathToStoredPath_withFolders() {
        assertEquals("default/Simulation/Pressure",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/folder:default:/folder:Simulation:/tag:Pressure"));
    }

    @Test
    void qualifiedPathToStoredPath_withFolders_measurementCategory() {
        assertEquals("default/Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/folder:Measurements:/folder:default:/tag:Temperature"));
    }

    @Test
    void qualifiedPathToStoredPath_withFolders_assetCategory() {
        assertEquals("Plant/Line1/Motor1",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/folder:Assets:/folder:Plant:/folder:Line1:/tag:Motor1"));
    }

    // --- qualifiedPathToStoredPath with category prefixes (no folders) ---

    @Test
    void qualifiedPathToStoredPath_measurementCategory() {
        assertEquals("default/Temperature",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Measurements/default/Temperature"));
    }

    @Test
    void qualifiedPathToStoredPath_assetCategory() {
        assertEquals("Plant/Line1/Motor1",
                TagPathUtil.qualifiedPathToStoredPath(
                        "histprov:test:/tag:Assets/Plant/Line1/Motor1"));
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
        assertEquals("default/",
                TagPathUtil.parseFolderPrefix(
                        "histprov:Timescale historian:/folder:default"));
    }

    @Test
    void parseFolderPrefix_twoFolders() {
        assertEquals("default/Simulation/",
                TagPathUtil.parseFolderPrefix(
                        "histprov:Timescale historian:/folder:default:/folder:Simulation"));
    }

    @Test
    void parseFolderPrefix_threeFolders() {
        assertEquals("default/Simulation/Sub/",
                TagPathUtil.parseFolderPrefix(
                        "histprov:Timescale historian:/folder:default:/folder:Simulation:/folder:Sub"));
    }

    // --- extractCategory ---

    @Test
    void extractCategory_measurements() {
        assertEquals("Measurements", TagPathUtil.extractCategory("Measurements/default/Temp"));
    }

    @Test
    void extractCategory_assets() {
        assertEquals("Assets", TagPathUtil.extractCategory("Assets/Plant/Line1/Motor1"));
    }

    @Test
    void extractCategory_noCategory() {
        assertNull(TagPathUtil.extractCategory("default/Temp"));
    }

    @Test
    void extractCategory_null() {
        assertNull(TagPathUtil.extractCategory(null));
    }

    // --- stripCategory ---

    @Test
    void stripCategory_measurements() {
        assertEquals("default/Temp",
                TagPathUtil.stripCategory("Measurements/default/Temp"));
    }

    @Test
    void stripCategory_noCategory() {
        assertEquals("default/Temp",
                TagPathUtil.stripCategory("default/Temp"));
    }

    // --- roundtrip: storage → browse query → stored ---

    @Test
    void roundtrip_storageAndBrowseBack() {
        // Storage: QualifiedPath with prov: → measurement name
        String stored = TagPathUtil.qualifiedPathToStoredPath(
                "sys:GW-01:/prov:default:/tag:Simulation/Pressure");
        assertEquals("default/Simulation/Pressure", stored);

        // Browse: user selects tag → framework sends path with tag component
        String browseQuery = "histprov:test:/tag:" + stored;
        String roundtripped = TagPathUtil.qualifiedPathToStoredPath(browseQuery);
        assertEquals(stored, roundtripped);
    }

    @Test
    void roundtrip_storageAndFolderBrowseBack() {
        String stored = TagPathUtil.qualifiedPathToStoredPath(
                "sys:GW-01:/prov:default:/tag:Simulation/Pressure");
        assertEquals("default/Simulation/Pressure", stored);

        // Browse tree creates folder: components under Measurements category
        String browsePath = "histprov:test:/folder:Measurements:/folder:default:/folder:Simulation:/tag:Pressure";
        String result = TagPathUtil.qualifiedPathToStoredPath(browsePath);
        assertEquals(stored, result);
    }
}
