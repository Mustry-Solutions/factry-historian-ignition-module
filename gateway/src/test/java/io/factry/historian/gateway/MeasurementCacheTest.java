package io.factry.historian.gateway;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.factry.historian.proto.CreateMeasurement;
import io.factry.historian.proto.CreateMeasurementsReply;
import io.factry.historian.proto.CreateMeasurementsRequest;
import io.factry.historian.proto.GetMeasurementsByFilterRequest;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.Measurements;
import io.factry.historian.proto.MetadataProperty;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MeasurementCacheTest {

    @Nested
    class BuildCreateMeasurement {

        private Struct configGroup(CreateMeasurement m) {
            assertTrue(m.hasAttributes(), "attributes struct should be set");
            Value config = m.getAttributes().getFieldsMap().get("Config");
            assertNotNull(config, "attributes should contain a 'Config' group");
            assertEquals(Value.KindCase.STRUCT_VALUE, config.getKindCase(), "'Config' should be a nested struct");
            return config.getStructValue();
        }

        @Test
        void mapsEngineeringSpecsIntoConfigGroupWithFactryKeys() {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("engUnit", "degC");
            metadata.put("engLow", "0.0");
            metadata.put("engHigh", "100.0");

            CreateMeasurement m = MeasurementCache.buildCreateMeasurement("[default]Tank/Temp", "number", metadata);

            Struct config = configGroup(m);
            assertEquals("degC", config.getFieldsOrThrow("UoM").getStringValue());
            assertEquals(Value.KindCase.NUMBER_VALUE, config.getFieldsOrThrow("ValueMin").getKindCase());
            assertEquals(0.0, config.getFieldsOrThrow("ValueMin").getNumberValue());
            assertEquals(Value.KindCase.NUMBER_VALUE, config.getFieldsOrThrow("ValueMax").getKindCase());
            assertEquals(100.0, config.getFieldsOrThrow("ValueMax").getNumberValue());

            // LimitLo/LimitHi have no plain Ignition tag property equivalent.
            assertFalse(config.containsFields("LimitLo"));
            assertFalse(config.containsFields("LimitHi"));

            // The known specs travel via Config only, not the metadata map.
            assertFalse(m.containsMetadata("engUnit"));
            assertFalse(m.containsMetadata("engLow"));
            assertFalse(m.containsMetadata("engHigh"));

            assertEquals("[default]Tank/Temp", m.getName());
            assertEquals("number", m.getDataType());
            assertTrue(m.getAutoOnboard());
        }

        @Test
        void unknownPropertiesGoToTheMetadataMap() {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("engUnit", "bar");
            metadata.put("documentation", "Boiler pressure");
            metadata.put("tooltip", "PT-101");

            CreateMeasurement m = MeasurementCache.buildCreateMeasurement("[default]Boiler/P", "number", metadata);

            MetadataProperty doc = m.getMetadataOrThrow("documentation");
            assertEquals(MetadataProperty.DataType.STRING, doc.getDataType());
            assertEquals("Boiler pressure", doc.getValue().getStringValue());
            assertEquals("PT-101", m.getMetadataOrThrow("tooltip").getValue().getStringValue());

            assertEquals("bar", configGroup(m).getFieldsOrThrow("UoM").getStringValue());
        }

        @Test
        void descriptionBecomesTheMeasurementDescription() {
            CreateMeasurement m = MeasurementCache.buildCreateMeasurement(
                    "[default]Tank/Level", "number", Map.of("description", "Tank level"));

            assertEquals("Tank level", m.getDescription());
            assertFalse(m.containsMetadata("description"));
            assertFalse(m.hasAttributes(), "no Config keys means no attributes struct");
        }

        @Test
        void nonNumericRangeValueFallsBackToMetadata() {
            CreateMeasurement m = MeasurementCache.buildCreateMeasurement(
                    "[default]Tank/Level", "number", Map.of("engLow", "n/a"));

            assertEquals("n/a", m.getMetadataOrThrow("engLow").getValue().getStringValue());
            assertFalse(m.hasAttributes());
        }

        @Test
        void noMetadataProducesABareRequest() {
            CreateMeasurement m = MeasurementCache.buildCreateMeasurement("[default]Tag", "string", null);

            assertEquals("[default]Tag", m.getName());
            assertEquals("string", m.getDataType());
            assertTrue(m.getAutoOnboard());
            assertFalse(m.hasAttributes());
            assertTrue(m.getMetadataMap().isEmpty());
            assertFalse(m.hasDescription());
        }
    }

    /**
     * Stands in for Factry: the gRPC channel is never used for measurements, because the calls the
     * cache makes for them are overridden. Like Factry, a create with a name that already exists
     * does not return the existing measurement.
     */
    static class FakeFactry extends FactryGrpcClient {
        static final String OWN_COLLECTOR = "collector";

        final List<Measurement> measurements = new ArrayList<>();
        final List<String> createdNames = new ArrayList<>();
        /**
         * Names another collector has in this collector's time-series database. Creating one of
         * them adds a Discovered duplicate, as Factry does; any other name gets an Active one.
         */
        final Set<String> sameDatabase = new HashSet<>();
        /** How many of the next single-name lookups fail, as a timed-out call would. */
        int lookupFailures = 0;

        FakeFactry() {
            super("localhost", 1, OWN_COLLECTOR, "token", false, false, null);
        }

        void add(String name, String uuid) {
            add(name, uuid, OWN_COLLECTOR);
        }

        void add(String name, String uuid, String collectorUUID) {
            add(name, uuid, collectorUUID, "Active");
        }

        void add(String name, String uuid, String collectorUUID, String status) {
            measurements.add(Measurement.newBuilder().setName(name).setUuid(uuid)
                    .setCollectorUUID(collectorUUID).setStatus(status).build());
        }

        @Override
        public Measurements getMeasurementsByFilterWithDeadline(GetMeasurementsByFilterRequest request) {
            if (lookupFailures > 0) {
                lookupFailures--;
                throw new RuntimeException("lookup timed out");
            }
            return getMeasurementsByFilter(request);
        }

        @Override
        public Measurements getMeasurementsByFilter(GetMeasurementsByFilterRequest request) {
            Measurements.Builder b = Measurements.newBuilder();
            for (Measurement m : measurements) {
                if (!request.hasKeyword() || m.getName().contains(request.getKeyword())) {
                    b.addMeasurements(m);
                }
            }
            return b.setTotal(b.getMeasurementsCount()).build();
        }

        @Override
        public CreateMeasurementsReply createMeasurements(CreateMeasurementsRequest request) {
            for (CreateMeasurement m : request.getMeasurementsList()) {
                createdNames.add(m.getName());
                if (measurements.stream().noneMatch(e -> e.getName().equals(m.getName())
                        && e.getCollectorUUID().equals(OWN_COLLECTOR))) {
                    add(m.getName(), "new-" + m.getName(), OWN_COLLECTOR,
                            sameDatabase.contains(m.getName()) ? "Discovered" : "Active");
                }
            }
            return CreateMeasurementsReply.getDefaultInstance();
        }
    }

    @Nested
    class ExistingMeasurementNotInCache {

        @Test
        void getOrCreateUsesTheExistingMeasurementInsteadOfCreating() {
            FakeFactry factry = new FakeFactry();
            MeasurementCache cache = new MeasurementCache();
            // Created in Factry after the cache was last refreshed.
            factry.add("[default]Tank/Temp", "existing-uuid");

            String uuid = cache.getOrCreateUUID("[default]Tank/Temp", factry, 21.5);

            assertEquals("existing-uuid", uuid);
            assertTrue(factry.createdNames.isEmpty(), "must not ask Factry to create an existing name");
            assertEquals("existing-uuid", cache.getUUID("[default]Tank/Temp"));
        }

        @Test
        void batchCreatesOnlyTheNamesFactryDoesNotHave() {
            FakeFactry factry = new FakeFactry();
            MeasurementCache cache = new MeasurementCache();
            factry.add("[default]Tank/Temp", "existing-uuid");

            Map<String, Object> values = new LinkedHashMap<>();
            values.put("[default]Tank/Temp", 21.5);
            values.put("[default]Tank/Level", 3.2);
            Map<String, String> uuids = cache.batchGetOrCreateUUIDs(values, factry);

            assertEquals("existing-uuid", uuids.get("[default]Tank/Temp"));
            assertEquals("new-[default]Tank/Level", uuids.get("[default]Tank/Level"));
            assertEquals(List.of("[default]Tank/Level"), factry.createdNames);
        }

        @Test
        void batchCreatesWhenTheLookupFails() {
            FakeFactry factry = new FakeFactry();
            MeasurementCache cache = new MeasurementCache();
            // The lookup before create fails; the polling after the create succeeds.
            factry.lookupFailures = 1;

            Map<String, String> uuids = cache.batchGetOrCreateUUIDs(
                    Map.of("[default]Tank/Level", 3.2), factry);

            assertEquals("new-[default]Tank/Level", uuids.get("[default]Tank/Level"));
            assertEquals(List.of("[default]Tank/Level"), factry.createdNames);
        }

        @Test
        void aNameThatOnlyContainsTheTagPathDoesNotCount() {
            FakeFactry factry = new FakeFactry();
            MeasurementCache cache = new MeasurementCache();
            // The keyword filter matches substrings; only an exact name is the same measurement.
            factry.add("[default]Tank/Temp2", "other-uuid");

            String uuid = cache.getOrCreateUUID("[default]Tank/Temp", factry, 21.5);

            assertEquals("new-[default]Tank/Temp", uuid);
            assertEquals(List.of("[default]Tank/Temp"), factry.createdNames);
        }
    }

    /**
     * Each Site has its own collector and time-series database, and measurement names are only
     * unique per database. Another Site's measurement with the same name must never take the
     * place of this collector's.
     */
    @Nested
    class SameNameAtAnotherSite {

        static final String NAME = "default/Pump1/Flow";
        static final String OTHER_SITE = "site-b-collector";

        /** Both Sites have the measurement; Factry lists them in either order. */
        private FakeFactry bothSites(boolean otherSiteLast) {
            FakeFactry factry = new FakeFactry();
            if (otherSiteLast) {
                factry.add(NAME, "own-uuid");
                factry.add(NAME, "other-site-uuid", OTHER_SITE);
            } else {
                factry.add(NAME, "other-site-uuid", OTHER_SITE);
                factry.add(NAME, "own-uuid");
            }
            return factry;
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void refreshResolvesTheNameToThisCollectorsMeasurement(boolean otherSiteLast) {
            FakeFactry factry = bothSites(otherSiteLast);
            MeasurementCache cache = new MeasurementCache();

            cache.refresh(factry);

            assertEquals("own-uuid", cache.getUUID(NAME));
            assertEquals("own-uuid", cache.getMeasurementByName(NAME).getUuid());
            assertEquals("own-uuid", cache.getWriteUUID(NAME, factry));
            // Browse still lists both.
            assertEquals(2, cache.getAllMeasurements().size());
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void writeAfterRefreshUsesThisCollectorsMeasurement(boolean otherSiteLast) {
            FakeFactry factry = bothSites(otherSiteLast);
            MeasurementCache cache = new MeasurementCache();
            cache.refresh(factry);

            assertEquals("own-uuid", cache.batchGetOrCreateUUIDs(Map.of(NAME, 1.0), factry).get(NAME));
            assertEquals("own-uuid", cache.getOrCreateUUID(NAME, factry, 1.0));
            assertTrue(factry.createdNames.isEmpty());
        }

        @ParameterizedTest
        @ValueSource(booleans = {true, false})
        void lookupBeforeCreateUsesThisCollectorsMeasurement(boolean otherSiteLast) {
            FakeFactry factry = bothSites(otherSiteLast);

            assertEquals("own-uuid",
                    new MeasurementCache().batchGetOrCreateUUIDs(Map.of(NAME, 1.0), factry).get(NAME));
            MeasurementCache cache = new MeasurementCache();
            assertEquals("own-uuid", cache.getOrCreateUUID(NAME, factry, 1.0));
            assertEquals("own-uuid", cache.getUUID(NAME));
            assertTrue(factry.createdNames.isEmpty());
        }

        @Test
        void writeCreatesThisCollectorsMeasurementWhenOnlyAnotherSiteHasTheName() {
            FakeFactry factry = new FakeFactry();
            factry.add(NAME, "other-site-uuid", OTHER_SITE);
            MeasurementCache cache = new MeasurementCache();
            cache.refresh(factry);

            Map<String, String> uuids = cache.batchGetOrCreateUUIDs(Map.of(NAME, 1.0), factry);

            assertEquals("new-" + NAME, uuids.get(NAME));
            assertEquals(List.of(NAME), factry.createdNames);
            // From now on reads of this tag show this Site's history.
            assertEquals("new-" + NAME, cache.getUUID(NAME));
        }

        @Test
        void arrayWriteCreatesThisCollectorsMeasurementWhenOnlyAnotherSiteHasTheName() {
            FakeFactry factry = new FakeFactry();
            factry.add(NAME, "other-site-uuid", OTHER_SITE);
            MeasurementCache cache = new MeasurementCache();
            cache.refresh(factry);

            // The array path writes only on a cache hit and creates the measurement otherwise.
            assertNull(cache.getWriteUUID(NAME, factry));
            assertEquals("new-" + NAME, cache.getOrCreateUUID(NAME, factry, "[]number"));
            assertEquals("new-" + NAME, cache.getWriteUUID(NAME, factry));
        }

        @Test
        void sameDatabaseNameIsTriedOnceThenWrittenAsBefore() {
            // Another collector writes to this collector's database under the same name. Factry
            // only adds a Discovered duplicate, so this collector gets no measurement to use.
            FakeFactry factry = new FakeFactry();
            factry.add(NAME, "other-uuid", OTHER_SITE);
            factry.sameDatabase.add(NAME);
            MeasurementCache cache = new MeasurementCache();
            cache.refresh(factry);

            assertEquals("other-uuid", cache.batchGetOrCreateUUIDs(Map.of(NAME, 1.0), factry).get(NAME));
            assertEquals("other-uuid", cache.batchGetOrCreateUUIDs(Map.of(NAME, 1.0), factry).get(NAME));
            assertEquals("other-uuid", cache.getWriteUUID(NAME, factry));
            assertEquals(List.of(NAME), factry.createdNames, "asks Factry only once");
        }
    }
}
