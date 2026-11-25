package io.factry.historian.gateway;

import ch.qos.logback.classic.Level;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.inductiveautomation.historian.common.model.LegacyAggregateAdapter;
import com.inductiveautomation.historian.common.model.TimeWindow;
import com.inductiveautomation.historian.common.model.data.MetadataPoint;
import com.inductiveautomation.historian.common.model.options.AggregatedQueryKey;
import com.inductiveautomation.historian.common.model.options.AggregatedQueryOptions;
import com.inductiveautomation.ignition.common.FillMode;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.config.BasicPropertySet;
import com.inductiveautomation.ignition.common.config.PropertySet;
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode;
import com.inductiveautomation.ignition.common.sqltags.history.InterpolationMode;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.tags.config.properties.TagHistoryProps;
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps;
import io.factry.historian.proto.CreateMeasurement;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Buckets without samples, read the way a chart reads them (FillMode.DERIVED): an analog tag
 * gets the straight line between its neighbours, as Ignition's own historians draw it, and a
 * discrete tag holds its value (issue #83).
 */
class AnalogFillTest {

    private static final long T0 = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli();
    private static final String PATH = "sys:gw:/prov:default:/tag:out";

    private final TwaFakeFactry factry = new TwaFakeFactry();
    private final MeasurementCache cache = new MeasurementCache();

    private FactryQueryEngine engine() {
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setDelimiter("/");
        cache.refresh(factry);
        FactryQueryEngine engine = new FactryQueryEngine(TwaFakeFactry.gatewayContext(), "Factry", settings,
                factry, cache, new HistorianMetrics());
        engine.now = () -> T0 + 3_600_000;
        return engine;
    }

    /** 0 at 0 s and 60 at 60 s: a tag stored once a minute, read in 12 s buckets. */
    private void minuteTag(String datatype, String ignitionDataType) {
        String u = ignitionDataType == null ? factry.addMeasurement("default/out", datatype)
                : factry.addMeasurement("default/out", datatype, ignitionDataType);
        factry.add(u, T0, 0, "Good");
        factry.add(u, T0 + 60_000, 60, "Good");
    }

    /** Six 12 s buckets, one column, as Ignition builds the dataset. */
    private List<Object> column(AggregationMode mode) throws Exception {
        AverageHistoryQuery.Dataset ds = AverageHistoryQuery.wide(engine(), T0, T0 + 72_000, 6, mode,
                FillMode.DERIVED, "out");
        assertNull(ds.error, ds::toString);
        List<Object> out = new ArrayList<>();
        for (int r = 0; r < ds.rows.size(); r++) {
            assertEquals(T0 + r * 12_000L, ds.time(r), ds::toString);
            assertTrue(ds.qualities.get(r)[1].isGood(), ds::toString);
            out.add(((Number) ds.rows.get(r)[1]).doubleValue());
        }
        return out;
    }

    /** MinMax as the engine hands it to Ignition: per bucket a minimum, then a maximum. */
    private List<Object> minMax() {
        FactryQueryEngineAverageTest.Recorder rec = new FactryQueryEngineAverageTest.Recorder();
        engine().query(AggregatedQueryOptions.builder()
                .startTime(T0).endTime(T0 + 72_000)
                .withTimeWindow(TimeWindow.ofMillis(12_000))
                .addQueryKey(AggregatedQueryKey.of(QualifiedPath.parseSafe(PATH),
                        LegacyAggregateAdapter.of(AggregationMode.MinMax), FillMode.DERIVED))
                .build(), rec);
        assertNull(rec.error);
        List<Object> out = new ArrayList<>();
        for (var p : rec.points) {
            assertTrue(p.quality().isGood());
            out.add(((Number) p.value()).doubleValue());
        }
        return out;
    }

    private static final List<Object> LINE = List.of(0.0, 12.0, 24.0, 36.0, 48.0, 60.0);
    private static final List<Object> HELD = List.of(0.0, 0.0, 0.0, 0.0, 0.0, 60.0);

    private void assertDrawn(List<Object> expected) throws Exception {
        assertEquals(expected, column(AggregationMode.LastValue), "LastValue");
        assertEquals(expected, column(AggregationMode.Average), "Average");
        assertEquals(expected, column(AggregationMode.SimpleAverage), "SimpleAverage");
        List<Object> pairs = new ArrayList<>();
        expected.forEach(v -> pairs.addAll(List.of(v, v)));
        assertEquals(pairs, minMax(), "MinMax");
    }

    @Test
    void aNumberTag_isDrawnOnAStraightLineThroughEmptyBuckets() throws Exception {
        minuteTag("number", null);
        // Before the fix every empty bucket held 0: a staircase that lags the SQL historian's line.
        assertDrawn(LINE);
    }

    @Test
    void aDiscreteTag_holdsItsValue() throws Exception {
        minuteTag("number", null);
        cache.storeMetadata("default/out", Map.of(MeasurementCache.DEADBAND_STYLE, "Discrete"));
        assertDrawn(HELD);
    }

    @Test
    void anAnalogStyle_drawsAnIntegerTagOnALine() throws Exception {
        minuteTag("number", "Int4");
        cache.storeMetadata("default/out", Map.of(MeasurementCache.DEADBAND_STYLE, "Analog_Compressed"));
        assertDrawn(LINE);
    }

    @Test
    void anIntegerTagWithAutoStyle_holdsItsValue() throws Exception {
        minuteTag("number", "Int4");
        cache.storeMetadata("default/out", Map.of(MeasurementCache.DEADBAND_STYLE, "Auto"));
        assertDrawn(HELD);
    }

    @Test
    void aBooleanTag_holdsItsValue() throws Exception {
        String u = factry.addMeasurement("default/out", "boolean");
        factry.add(u, T0, false, "Good");
        factry.add(u, T0 + 60_000, true, "Good");
        cache.storeMetadata("default/out", Map.of(MeasurementCache.DEADBAND_STYLE, "Analog"));
        assertEquals(List.of(0.0, 0.0, 0.0, 0.0, 0.0, 1.0), column(AggregationMode.LastValue));
    }

    @Test
    void theStyleOnTheMeasurement_countsWhenThisGatewayHasNoMetadata() throws Exception {
        // Another gateway's tag, or this one's before Ignition sends the metadata.
        String u = factry.addMeasurement("default/out", "number", "Float8");
        factry.setSetting(u, MeasurementCache.DEADBAND_STYLE_SETTING, "Discrete");
        factry.add(u, T0, 0, "Good");
        factry.add(u, T0 + 60_000, 60, "Good");
        assertDrawn(HELD);
    }

    @Test
    void theMetadata_winsOverTheStyleOnTheMeasurement() throws Exception {
        String u = factry.addMeasurement("default/out", "number", "Float8");
        factry.setSetting(u, MeasurementCache.DEADBAND_STYLE_SETTING, "Discrete");
        factry.add(u, T0, 0, "Good");
        factry.add(u, T0 + 60_000, 60, "Good");
        cache.storeMetadata("default/out", Map.of(MeasurementCache.DEADBAND_STYLE, "Analog_Compressed"));
        assertDrawn(LINE);
    }

    @Test
    void ignitionIsToldTheStyle_forTheAggregatesItComputesItself() {
        String u = factry.addMeasurement("default/out", "number", "Float8");
        factry.setSetting(u, MeasurementCache.DEADBAND_STYLE_SETTING, "Discrete");
        FactryQueryEngine engine = engine();
        var node = engine.lookupNode(QualifiedPath.parseSafe(PATH)).orElseThrow();
        PropertySet props = engine.getProcessorProperties(node).orElseThrow();
        // HistorianPointQueryExecutor turns this into FillMode.PREV, so its own interpolator holds.
        assertEquals(InterpolationMode.Discrete, props.get(TagHistoryProps.HistoricalDeadbandStyle));
    }

    /** Six 12 s LastValue buckets as read, with "Bad" for a row that is not Good. */
    private List<Object> lastValues(long end, long now) throws Exception {
        FactryQueryEngine engine = engine();
        engine.now = () -> now;
        AverageHistoryQuery.Dataset ds = AverageHistoryQuery.wide(engine, T0, end, (int) ((end - T0) / 12_000),
                AggregationMode.LastValue, FillMode.DERIVED, "out");
        assertNull(ds.error, ds::toString);
        List<Object> out = new ArrayList<>();
        for (int r = 0; r < ds.rows.size(); r++) {
            out.add(ds.qualities.get(r)[1].isGood() ? ((Number) ds.rows.get(r)[1]).doubleValue() : "Bad");
        }
        return out;
    }

    @Test
    void anEmptyBucketAfterABucketWithASpread_getsOneValueForMinAndMax() throws Exception {
        String u = factry.addMeasurement("default/out", "number");
        factry.add(u, T0, 0, "Good");
        factry.add(u, T0 + 5_000, 100, "Good");
        factry.add(u, T0 + 60_000, 100, "Good");
        // The line runs from 100 at 5 s, not from the first bucket's minimum: no band of 20/100,
        // 40/100, ... that never happened.
        assertEquals(List.of(0.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0, 100.0),
                minMax());
        assertEquals(List.of(100.0, 100.0, 100.0, 100.0, 100.0, 100.0), column(AggregationMode.LastValue));
    }

    @Test
    void badTimeBetweenTwoGoodBuckets_isNotBridged() throws Exception {
        String u = factry.addMeasurement("default/out", "number");
        factry.add(u, T0, 10, "Good");
        factry.add(u, T0 + 30_000, 99, "Bad");
        factry.add(u, T0 + 60_000, 60, "Good");
        // Empty, Bad, empty: the first empty bucket holds (a Bad sample follows), the rest is Bad.
        assertEquals(Arrays.asList(10.0, 10.0, "Bad", "Bad", "Bad", 60.0), lastValues(T0 + 72_000, T0 + 3_600_000));
    }

    @Test
    void theRangeStart_isOnTheLineFromTheSampleBeforeIt() throws Exception {
        String u = factry.addMeasurement("default/out", "number");
        factry.add(u, T0 - 24_000, 0, "Good");
        factry.add(u, T0 + 36_000, 60, "Good");
        assertEquals(Arrays.asList(24.0, 36.0, 48.0, 60.0, 60.0, 60.0), lastValues(T0 + 72_000, T0 + 3_600_000));
    }

    @Test
    void aPastRangeEnd_isOnTheLineToTheSampleAfterIt() throws Exception {
        String u = factry.addMeasurement("default/out", "number");
        factry.add(u, T0 + 36_000, 0, "Good");
        factry.add(u, T0 + 96_000, 60, "Good");
        assertEquals(Arrays.asList("Bad", "Bad", "Bad", 0.0, 12.0, 24.0), lastValues(T0 + 72_000, T0 + 3_600_000));
    }

    @Test
    void towardNow_theLastValueHolds() throws Exception {
        String u = factry.addMeasurement("default/out", "number");
        factry.add(u, T0, 0, "Good");
        factry.add(u, T0 + 24_000, 24, "Good");
        // No extrapolation past the last sample, and nothing after now.
        assertEquals(Arrays.asList(0.0, 12.0, 24.0, 24.0, 24.0, "Bad"), lastValues(T0 + 72_000, T0 + 60_000));
    }

    @Test
    void aBadStretch_isNotBridged() throws Exception {
        String u = factry.addMeasurement("default/out", "number");
        factry.add(u, T0 - 30_000, 5, "Good");
        factry.add(u, T0 + 13_000, 7, "Good");
        factry.add(u, T0 + 26_000, 99, "Bad");
        factry.add(u, T0 + 49_000, 9, "Good");
        // On the line from 5 at -30 s to 7 at 13 s, Bad through Bad time, and held after the last
        // sample, since nothing follows the range.
        assertEquals(Arrays.asList(5 + 2 * 30.0 / 43, 7.0, "Bad", "Bad", 9.0, 9.0),
                lastValues(T0 + 72_000, T0 + 3_600_000));
    }

    // The write side: the style is stored on the measurement and kept up to date.

    @Test
    void theStoredStyle_isTheConcreteOne() {
        // Another gateway may not know the type, so Auto is stored as what it means for the tag.
        assertEquals("Discrete", MeasurementCache.buildCreateMeasurement("default/out", "number",
                Map.of(MeasurementCache.IGNITION_DATA_TYPE, "Int4", MeasurementCache.DEADBAND_STYLE, "Auto"))
                .getSettings().getFieldsOrThrow(MeasurementCache.DEADBAND_STYLE_SETTING).getStringValue());
        assertEquals("Analog_Compressed", MeasurementCache.buildCreateMeasurement("default/out", "number",
                Map.of(MeasurementCache.IGNITION_DATA_TYPE, "Float8", MeasurementCache.DEADBAND_STYLE, "Auto"))
                .getSettings().getFieldsOrThrow(MeasurementCache.DEADBAND_STYLE_SETTING).getStringValue());
    }

    @Test
    void aNewMeasurement_getsTheStyle() {
        CreateMeasurement m = MeasurementCache.buildCreateMeasurement("default/out", "number",
                Map.of(MeasurementCache.IGNITION_DATA_TYPE, "Float8", MeasurementCache.DEADBAND_STYLE, "Discrete"));
        assertEquals("Discrete", m.getSettings().getFieldsOrThrow(MeasurementCache.DEADBAND_STYLE_SETTING).getStringValue());
        assertEquals("Float8", m.getSettings().getFieldsOrThrow(MeasurementCache.IGNITION_DATA_TYPE_SETTING).getStringValue());
    }

    private static Struct settings(String... keyValues) {
        Struct.Builder b = Struct.newBuilder();
        for (int i = 0; i < keyValues.length; i += 2) {
            b.putFields(keyValues[i], Value.newBuilder().setStringValue(keyValues[i + 1]).build());
        }
        return b.build();
    }

    private static MetadataPoint metadata(String tag, InterpolationMode style) {
        return metadata(tag, style, null);
    }

    private static MetadataPoint metadata(String tag, InterpolationMode style, DataType type) {
        BasicPropertySet ps = new BasicPropertySet();
        ps.set(TagHistoryProps.HistoricalDeadbandStyle, style);
        if (type != null) {
            ps.set(WellKnownTagProps.DataType, type);
        }
        return MetadataPoint.from(ps, QualifiedPath.parseSafe("sys:gw:/prov:default:/tag:" + tag));
    }

    private static FactryStorageEngine storage(FactryGrpcClient client, MeasurementCache cache) {
        FactryHistorianSettings settings = new FactryHistorianSettings();
        return new FactryStorageEngine(FakeFactryServer.gatewayContext(), "Factry", settings, client, cache,
                new HistorianMetrics());
    }

    @Test
    void aChangedStyle_isStoredOnTheOwnMeasurement_keepingItsOtherSettings() throws Exception {
        try (FakeFactryServer server = new FakeFactryServer()) {
            server.collectorSettings = settings("siteName", "North");
            String own = server.addMeasurement("default/out", "number", "collector",
                    settings(MeasurementCache.IGNITION_DATA_TYPE_SETTING, "Float8",
                            MeasurementCache.DEADBAND_STYLE_SETTING, "Analog_Compressed"));
            String same = server.addMeasurement("default/same", "number", "collector",
                    settings(MeasurementCache.DEADBAND_STYLE_SETTING, "Discrete"));
            String other = server.addMeasurement("default/other", "number", "another-collector",
                    settings(MeasurementCache.IGNITION_DATA_TYPE_SETTING, "Float8"));
            FactryGrpcClient client = server.client();
            try {
                MeasurementCache cache = new MeasurementCache();
                cache.refresh(client);
                storage(client, cache).doStoreMetadata(List.of(
                        metadata("out", InterpolationMode.Discrete),
                        metadata("same", InterpolationMode.Discrete),
                        metadata("other", InterpolationMode.Discrete)));

                // One call, only for the own measurement whose style changed.
                assertEquals(1, server.migrations.size());
                var changes = server.migrations.get(0).getMeasurementSettingsList();
                assertEquals(1, changes.size());
                assertEquals(own, changes.get(0).getMeasurementUUID());
                assertEquals(settings(MeasurementCache.IGNITION_DATA_TYPE_SETTING, "Float8",
                        MeasurementCache.DEADBAND_STYLE_SETTING, "Discrete"), server.measurement(own).getSettings());
                assertEquals(settings(MeasurementCache.IGNITION_DATA_TYPE_SETTING, "Float8"),
                        server.measurement(other).getSettings());
                // MigrateSettings also replaces the collector's own settings: they are sent along.
                assertEquals(settings("siteName", "North"), server.migrations.get(0).getSettings());
                assertEquals(settings("siteName", "North"), server.collectorSettings);
                assertEquals("Discrete", cache.getMeasurementByUUID(own).getSettings()
                        .getFieldsOrThrow(MeasurementCache.DEADBAND_STYLE_SETTING).getStringValue());
                assertEquals(same, cache.getMeasurementByName("default/same").getUuid());

                // The cache has the new setting, so the same metadata again sends nothing.
                storage(client, cache).doStoreMetadata(List.of(metadata("out", InterpolationMode.Discrete)));
                assertEquals(1, server.migrations.size());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void aRefusedMigration_isLoggedOnce_andChangesNothingElse() throws Exception {
        try (FakeFactryServer server = new FakeFactryServer()) {
            server.migrateFailure = Status.PERMISSION_DENIED.withDescription("refused by the proxy");
            String own = server.addMeasurement("default/out", "number", "collector",
                    settings(MeasurementCache.IGNITION_DATA_TYPE_SETTING, "Float8"));
            FactryGrpcClient client = server.client();
            try (LogCapture log = new LogCapture(MeasurementCache.class.getName(), Level.INFO)) {
                MeasurementCache cache = new MeasurementCache();
                cache.refresh(client);
                FactryStorageEngine storage = storage(client, cache);
                var result = storage.doStoreMetadata(List.of(metadata("out", InterpolationMode.Discrete)));
                storage.doStoreMetadata(List.of(metadata("out", InterpolationMode.Analog_Compressed)));

                assertTrue(result.error().isEmpty(), result::toString);
                assertEquals(1, log.events().stream()
                        .filter(e -> e.getLevel() == Level.INFO && e.getFormattedMessage().contains("refused by the proxy"))
                        .count(), log::text);
                assertEquals(settings(MeasurementCache.IGNITION_DATA_TYPE_SETTING, "Float8"),
                        cache.getMeasurementByUUID(own).getSettings());
                assertEquals(settings(MeasurementCache.IGNITION_DATA_TYPE_SETTING, "Float8"),
                        server.measurement(own).getSettings());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void aMigration_storesTheConcreteStyle_andTheTypeWhenMissing() throws Exception {
        try (FakeFactryServer server = new FakeFactryServer()) {
            String own = server.addMeasurement("default/count", "number", "collector", settings("other", "x"));
            FactryGrpcClient client = server.client();
            try {
                MeasurementCache cache = new MeasurementCache();
                cache.refresh(client);
                storage(client, cache).doStoreMetadata(List.of(metadata("count", InterpolationMode.Auto, DataType.Int4)));
                assertEquals(settings("other", "x", MeasurementCache.DEADBAND_STYLE_SETTING, "Discrete",
                        MeasurementCache.IGNITION_DATA_TYPE_SETTING, "Int4"), server.measurement(own).getSettings());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void aTagRenamedInLetterCaseOnly_updatesItsMeasurement() throws Exception {
        try (FakeFactryServer server = new FakeFactryServer()) {
            String own = server.addMeasurement("default/Out", "number", "collector", Struct.getDefaultInstance());
            FactryGrpcClient client = server.client();
            try {
                MeasurementCache cache = new MeasurementCache();
                cache.refresh(client);
                storage(client, cache).doStoreMetadata(List.of(metadata("out", InterpolationMode.Discrete)));
                assertEquals(settings(MeasurementCache.DEADBAND_STYLE_SETTING, "Discrete"),
                        server.measurement(own).getSettings());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void manyMeasurements_goInBatches() throws Exception {
        try (FakeFactryServer server = new FakeFactryServer()) {
            List<MetadataPoint> points = new ArrayList<>();
            for (int i = 0; i < MeasurementCache.MIGRATE_BATCH + 1; i++) {
                server.addMeasurement("default/t" + i, "number", "collector", Struct.getDefaultInstance());
                points.add(metadata("t" + i, InterpolationMode.Discrete));
            }
            FactryGrpcClient client = server.client();
            try {
                MeasurementCache cache = new MeasurementCache();
                cache.refresh(client);
                storage(client, cache).doStoreMetadata(points);
                assertEquals(List.of(MeasurementCache.MIGRATE_BATCH, 1), server.migrations.stream()
                        .map(r -> r.getMeasurementSettingsCount()).toList());
            } finally {
                client.shutdown();
            }
        }
    }

    @Test
    void aFailure_isLoggedOncePerStatusCode() throws Exception {
        try (FakeFactryServer server = new FakeFactryServer()) {
            server.addMeasurement("default/out", "number", "collector", Struct.getDefaultInstance());
            FactryGrpcClient client = server.client();
            try (LogCapture log = new LogCapture(MeasurementCache.class.getName(), Level.INFO)) {
                MeasurementCache cache = new MeasurementCache();
                cache.refresh(client);
                FactryStorageEngine storage = storage(client, cache);
                server.migrateFailure = Status.PERMISSION_DENIED.withDescription("refused");
                storage.doStoreMetadata(List.of(metadata("out", InterpolationMode.Discrete)));
                storage.doStoreMetadata(List.of(metadata("out", InterpolationMode.Discrete)));
                server.migrateFailure = Status.UNIMPLEMENTED.withDescription("unknown method");
                storage.doStoreMetadata(List.of(metadata("out", InterpolationMode.Discrete)));
                assertEquals(2, log.events().stream()
                        .filter(e -> e.getFormattedMessage().contains("did not store the Deadband Style")).count(),
                        log::text);
                assertEquals(3, server.migrations.size());
            } finally {
                client.shutdown();
            }
        }
    }
}
