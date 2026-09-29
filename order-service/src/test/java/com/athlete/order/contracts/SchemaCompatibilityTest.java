package com.athlete.order.contracts;

import com.athlete.order.contracts.avro.OrderIntegrationEvent;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Encodes the BACKWARD-compatibility rule (ADR 0004) as a fail-fast test: a new reader schema must
 * be able to read data written with the previous schema. This catches breaking integration-event
 * changes in CI, before they ever reach a running registry. The generated contract schema is also
 * checked for self-compatibility so a malformed evolution fails the build.
 */
class SchemaCompatibilityTest {

    /** A committed copy of the published contract; the version consumers are assumed to hold. */
    private static final String BASELINE_RESOURCE = "/contracts/order-integration-event.v1.avsc";

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

    /**
     * The real gate: the contract as it stands must still be able to read data written with the
     * last frozen version of itself.
     *
     * <p>The baseline is a committed copy of the published schema
     * ({@code src/test/resources/contracts/order-integration-event.v1.avsc}). Comparing the current
     * schema against itself — as this test previously did — is unconditionally compatible and
     * cannot fail, so it gated nothing. Editing the {@code .avsc} in a breaking way (adding a
     * required field without a default, renaming a field, changing a type) now fails the build.
     *
     * <p>When a compatible evolution is deliberately published, update the baseline in the same
     * commit so the next change is measured against what consumers actually received.
     */
    @Test
    void the_current_contract_can_read_data_written_with_the_frozen_baseline() {
        Schema current = OrderIntegrationEvent.getClassSchema();
        Schema baseline = loadBaseline();

        SchemaCompatibility.SchemaPairCompatibility result =
                SchemaCompatibility.checkReaderWriterCompatibility(current, baseline);

        assertThat(result.getType())
                .as("current contract must remain BACKWARD compatible with %s — %s",
                        BASELINE_RESOURCE, result.getDescription())
                .isEqualTo(SchemaCompatibility.SchemaCompatibilityType.COMPATIBLE);
    }

    @Test
    void the_frozen_baseline_is_a_parsable_schema() {
        // Guards the guard: a corrupt baseline would make the gate above pass vacuously.
        assertThat(loadBaseline().getFullName())
                .isEqualTo(OrderIntegrationEvent.getClassSchema().getFullName());
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

    private static Schema loadBaseline() {
        try (InputStream in = SchemaCompatibilityTest.class.getResourceAsStream(BASELINE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing frozen contract baseline: " + BASELINE_RESOURCE);
            }
            return new Schema.Parser().parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + BASELINE_RESOURCE, e);
        }
    }

    private static Schema parse(String json) {
        return new Schema.Parser().parse(json);
    }

    private static SchemaCompatibility.SchemaCompatibilityType compatibility(Schema reader, Schema writer) {
        return SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).getType();
    }
}
