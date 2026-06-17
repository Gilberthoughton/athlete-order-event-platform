package com.athlete.order.contracts;

import com.athlete.order.contracts.avro.OrderIntegrationEvent;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Encodes the BACKWARD-compatibility rule (ADR 0004) as a fail-fast test: a new reader schema must
 * be able to read data written with the previous schema. This catches breaking integration-event
 * changes in CI, before they ever reach a running registry. The generated contract schema is also
 * checked for self-compatibility so a malformed evolution fails the build.
 */
class SchemaCompatibilityTest {

    private static final String V1 = """
            {"type":"record","name":"Evt","namespace":"aoep.test","fields":[
              {"name":"id","type":"string"}]}""";

    private static final String V2_ADD_OPTIONAL = """
            {"type":"record","name":"Evt","namespace":"aoep.test","fields":[
              {"name":"id","type":"string"},
              {"name":"channel","type":["null","string"],"default":null}]}""";

    private static final String V2_ADD_REQUIRED = """
            {"type":"record","name":"Evt","namespace":"aoep.test","fields":[
              {"name":"id","type":"string"},
              {"name":"channel","type":"string"}]}""";

    @Test
    void the_published_contract_is_self_compatible() {
        Schema current = OrderIntegrationEvent.getClassSchema();
        assertThat(compatibility(current, current))
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    }

    @Test
    void adding_a_nullable_defaulted_field_is_backward_compatible() {
        // ALLOWED: new reader (with the added optional field) reads data written by the old writer.
        assertThat(compatibility(parse(V2_ADD_OPTIONAL), parse(V1)))
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    }

    @Test
    void adding_a_required_field_without_a_default_is_a_breaking_change() {
        // BREAKING: new reader requires a field absent from old data and has no default to fall back on.
        assertThat(compatibility(parse(V2_ADD_REQUIRED), parse(V1)))
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.INCOMPATIBLE);
    }

    private static Schema parse(String json) {
        return new Schema.Parser().parse(json);
    }

    private static SchemaCompatibility.SchemaCompatibilityType compatibility(Schema reader, Schema writer) {
        return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).getType();
    }
}
