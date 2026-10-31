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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        final List<Measurement> measurements = new ArrayList<>();
        final List<String> createdNames = new ArrayList<>();
        /** How many of the next single-name lookups fail, as a timed-out call would. */
        int lookupFailures = 0;

        FakeFactry() {
            super("localhost", 1, "collector", "token", false, false, null);
        }

        void add(String name, String uuid) {
            measurements.add(Measurement.newBuilder().setName(name).setUuid(uuid).setStatus("Active").build());
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
                if (measurements.stream().noneMatch(e -> e.getName().equals(m.getName()))) {
                    add(m.getName(), "new-" + m.getName());
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
}
