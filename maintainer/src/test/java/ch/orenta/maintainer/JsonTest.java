package ch.orenta.maintainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void parsesNestedValues() {
        Map<String, Object> m = Json.parseObject(
                "{\"a\":1,\"b\":[true,false,null],\"c\":{\"d\":\"x\\ny\\u00e4\"},\"e\":-1.5e2}");
        assertEquals(1L, m.get("a"));
        assertEquals(java.util.Arrays.asList(true, false, null), m.get("b"));
        assertEquals("x\nyä", ((Map<?, ?>) m.get("c")).get("d"));
        assertEquals(new BigDecimal("-1.5e2"), m.get("e"));
    }

    @Test
    void rejectsDuplicateKeys() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
    }

    @Test
    void rejectsBrokenInput() {
        for (String bad : List.of("", "{", "{\"a\"}", "[1,]", "{\"a\":01}", "\"x", "{} x", "tru", "\"\\q\"")) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
    }

    @Test
    void writesWhatItReads() {
        String text = "{\"a\":[1,\"x\\\"y\"],\"b\":{\"c\":null,\"d\":false}}";
        assertEquals(text, Json.write(Json.parse(text)));
    }
}
