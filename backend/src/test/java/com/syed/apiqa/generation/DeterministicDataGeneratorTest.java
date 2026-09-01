package com.syed.apiqa.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicDataGeneratorTest {

    private DeterministicDataGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new DeterministicDataGenerator(new ObjectMapper());
    }

    @Test
    void shouldGenerateIdenticalValuesForSameSeed() {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("id", new StringSchema().format("uuid"));
        schema.addProperty("email", new StringSchema().format("email"));
        schema.addProperty("createdAt", new DateTimeSchema());
        schema.addProperty("age", new IntegerSchema().minimum(BigDecimal.valueOf(18)).maximum(BigDecimal.valueOf(99)));

        long seed = 123456789L;

        Map<String, Object> run1 = generator.generateObject(schema, new Random(seed), "seed1");
        Map<String, Object> run2 = generator.generateObject(schema, new Random(seed), "seed1");

        assertEquals(run1.get("id"), run2.get("id"), "UUID must be strictly deterministic");
        assertEquals(run1.get("email"), run2.get("email"), "Email must be strictly deterministic");
        assertEquals(run1.get("createdAt"), run2.get("createdAt"), "DateTime must be strictly deterministic without system clock jitter");
        assertEquals(run1.get("age"), run2.get("age"), "Integer within boundaries must be strictly deterministic");
    }

    @Test
    void shouldRespectUniqueItemsInArrays() {
        ArraySchema arraySchema = new ArraySchema();
        arraySchema.setItems(new IntegerSchema().minimum(BigDecimal.ONE).maximum(BigDecimal.valueOf(20)));
        arraySchema.setMinItems(5);
        arraySchema.setMaxItems(5);
        arraySchema.setUniqueItems(true);

        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) generator.generateValueForSchema(arraySchema, "tags", new Random(42L), "run1");

        assertNotNull(list);
        assertEquals(5, list.size());
        // Verify all items are unique
        long uniqueCount = list.stream().distinct().count();
        assertEquals(list.size(), uniqueCount, "Array with uniqueItems=true must have all distinct elements");
    }

    @Test
    void shouldProduceDifferentResultsForDifferentSeeds() {
        StringSchema schema = new StringSchema();
        Object val1 = generator.generateValueForSchema(schema, "name", new Random(100L), "r1");
        Object val2 = generator.generateValueForSchema(schema, "name", new Random(200L), "r2");

        assertNotEquals(val1, val2, "Different seeds must produce different synthetic values");
    }
}
