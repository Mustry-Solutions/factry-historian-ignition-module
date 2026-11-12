package io.factry.historian.gateway;

import com.google.protobuf.ListValue;
import com.google.protobuf.Value;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import io.factry.historian.proto.Assets;
import io.factry.historian.proto.Collectors;
import io.factry.historian.proto.Measurement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reading array tags back. Ignition historizes an array tag as one path per element
 * ({@code Tag[0]}, {@code Tag[1]}, ...) and the storage engine stores them together as one
 * list-valued measurement {@code Tag}. A query for {@code Tag[2]} must read element 2 of it.
 */
class ArrayElementReadTest {

    private MeasurementCache cache;

    @BeforeEach
    void setUp() {
        MeasurementCacheTest.FakeFactry factry = new MeasurementCacheTest.FakeFactry() {
            @Override
            public Collectors getCollectors() {
                return Collectors.getDefaultInstance();
            }

            @Override
            public Assets getAssets() {
                return Assets.getDefaultInstance();
            }
        };
        factry.measurements.add(measurement("default/Temps", "u-temps", "[]number"));
        factry.measurements.add(measurement("default/Names", "u-names", "[]string"));
        factry.measurements.add(measurement("default/Speed", "u-speed", "number"));
        cache = new MeasurementCache();
        cache.refresh(factry);
    }

    private static Measurement measurement(String name, String uuid, String datatype) {
        return Measurement.newBuilder().setName(name).setUuid(uuid).setDatatype(datatype).setStatus("Active").build();
    }

    private static Value list(double... values) {
        ListValue.Builder b = ListValue.newBuilder();
        for (double v : values) {
            b.addValues(Value.newBuilder().setNumberValue(v));
        }
        return Value.newBuilder().setListValue(b).build();
    }

    @Test
    void elementPathReadsElementOfTheArrayMeasurement() {
        FactryRecord node = FactryQueryEngine.measurementNode(cache, "default/Temps[2]", null).orElseThrow();

        assertEquals("u-temps", node.measurementUUID());
        assertEquals(2, node.arrayIndex());
        assertEquals(Optional.of(DataType.Float8), node.dataType());
        assertEquals(100.0, FactryQueryEngine.pointValue(list(1, 10, 100), node.arrayIndex()));
    }

    @Test
    void elementOfAStringArrayIsAString() {
        FactryRecord node = FactryQueryEngine.measurementNode(cache, "default/Names[0]", null).orElseThrow();

        assertEquals(Optional.of(DataType.String), node.dataType());
    }

    @Test
    void elementsOfOneArrayAreDistinctNodes() {
        FactryRecord first = FactryQueryEngine.measurementNode(cache, "default/Temps[0]", null).orElseThrow();
        FactryRecord second = FactryQueryEngine.measurementNode(cache, "default/Temps[1]", null).orElseThrow();

        assertNotEquals(first.nodeId(), second.nodeId());
    }

    @Test
    void basePathOfAnArrayHasNoHistory() {
        // Ignition only historizes the elements, so the base path is not found, as with
        // Ignition's own historians.
        assertTrue(FactryQueryEngine.measurementNode(cache, "default/Temps", null).isEmpty());
    }

    @Test
    void plainMeasurementIsReadWhole() {
        FactryRecord node = FactryQueryEngine.measurementNode(cache, "default/Speed", null).orElseThrow();

        assertEquals("u-speed", node.measurementUUID());
        assertEquals(-1, node.arrayIndex());
        assertEquals(42.0, FactryQueryEngine.pointValue(Value.newBuilder().setNumberValue(42).build(), -1));
    }

    @Test
    void indexOnAPlainMeasurementIsNotFound() {
        assertTrue(FactryQueryEngine.measurementNode(cache, "default/Speed[1]", null).isEmpty());
    }

    @Test
    void elementPastTheEndOfAShorterArrayIsNull() {
        assertNull(FactryQueryEngine.pointValue(list(1, 10), 2));
    }

    @Test
    void countOverAnArrayIsReturnedAsIs() {
        // Factry answers count on an array measurement with a plain number.
        assertEquals(3.0, FactryQueryEngine.pointValue(Value.newBuilder().setNumberValue(3).build(), 1));
    }

    @Test
    void onlyCountAndLastValueAggregateArrays() {
        // Factry rejects mean, min, max and the rest for "[]number".
        assertTrue(FactryQueryEngine.isArrayAggregate("LastValue"));
        assertTrue(FactryQueryEngine.isArrayAggregate("Count"));
        assertFalse(FactryQueryEngine.isArrayAggregate("Average"));
        assertFalse(FactryQueryEngine.isArrayAggregate("MinMax"));
    }
}
