package xyz.tcheeric.cashu.ledger.trace.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;import net.jqwik.api.constraints.UniqueElements;

/**
 * Property-based tests asserting that {@link CanonicalJson#write} is invariant to
 * object key insertion order — the foundation of deterministic event ids.
 */
class CanonicalJsonPropertyTest {

    /**
     * For any set of distinct keys with string values, serialising the object in
     * one insertion order and in the reverse order yields identical canonical bytes.
     */
    @Property(tries = 200)
    void canonicalWriteIsOrderInvariant(
            @ForAll @Size(min = 1, max = 12) @UniqueElements List<@net.jqwik.api.constraints.AlphaChars @net.jqwik.api.constraints.StringLength(min = 1, max = 8) String> keys) {
        // Arrange: build the same logical object in two opposite insertion orders
        Map<String, Object> forward = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            forward.put(keys.get(i), "v" + i);
        }
        List<String> reversedKeys = new ArrayList<>(keys);
        Collections.reverse(reversedKeys);
        Map<String, Object> backward = new LinkedHashMap<>();
        for (String key : reversedKeys) {
            backward.put(key, "v" + keys.indexOf(key));
        }

        // Act
        String a = CanonicalJson.write(forward);
        String b = CanonicalJson.write(backward);

        // Then
        assertThat(a).isEqualTo(b);
    }
}
