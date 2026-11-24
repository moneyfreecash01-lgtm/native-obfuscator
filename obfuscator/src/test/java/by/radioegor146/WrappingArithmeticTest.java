package by.radioegor146;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the JVM arithmetic semantics of the emitted C++.
 *
 * The JVM defines int/long addition, subtraction, multiplication, negation and
 * shifting to wrap on overflow. Signed C++ overflow is undefined behaviour, so
 * clang -O2 may legally fold {@code x + Integer.MIN_VALUE} into {@code x} once
 * it can prove {@code x < 0} — which is exactly the shape of a Kotlin
 * coroutine resume path ({@code label += Integer.MIN_VALUE}).
 *
 * That is not hypothetical: GamesScreenKt.refreshGates died with
 * "call to 'resume' before 'invoke' with coroutine" on device because the
 * emitted {@code cstack1.i + cstack2.i} returned its left operand instead of
 * wrapping to 1. The snippets therefore cast through uint32_t/uint64_t, and
 * -fwrapv is set as a backstop. Both must stay.
 */
public class WrappingArithmeticTest {

    private static Properties snippets() throws Exception {
        try (InputStream in = WrappingArithmeticTest.class.getResourceAsStream("/sources/cppsnippets.properties")) {
            assertNotNull(in, "cppsnippets.properties is missing from the jar");
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        }
    }

    @Test
    public void arithmeticSnippetsUseDefinedWrapSemantics() throws Exception {
        Properties snippets = snippets();

        List<String> intOps = Arrays.asList("IADD", "ISUB", "IMUL", "INEG", "IINC");
        for (String op : intOps) {
            String snippet = snippets.getProperty(op);
            assertNotNull(snippet, op + " snippet is missing");
            assertTrue(snippet.contains("uint32_t"),
                    op + " must cast through uint32_t so overflow wraps like the JVM, was: " + snippet);
        }

        List<String> longOps = Arrays.asList("LADD", "LSUB", "LMUL", "LNEG");
        for (String op : longOps) {
            String snippet = snippets.getProperty(op);
            assertNotNull(snippet, op + " snippet is missing");
            assertTrue(snippet.contains("uint64_t"),
                    op + " must cast through uint64_t so overflow wraps like the JVM, was: " + snippet);
        }

        List<String> shiftOps = Arrays.asList("ISHL", "LSHL");
        for (String op : shiftOps) {
            String snippet = snippets.getProperty(op);
            assertNotNull(snippet, op + " snippet is missing");
            assertTrue(snippet.contains("uint32_t") || snippet.contains("uint64_t"),
                    op + " shifts a possibly negative value, which is UB on signed types, was: " + snippet);
        }

        // Integer.MIN_VALUE / -1 and Integer.MIN_VALUE % -1 both overflow the
        // C++ operator; the JVM defines them as MIN_VALUE and 0.
        assertTrue(snippets.getProperty("IREM").contains("2147483648U"),
                "IREM must special-case Integer.MIN_VALUE % -1");
        assertTrue(snippets.getProperty("LREM").contains("9223372036854775808ULL"),
                "LREM must special-case Long.MIN_VALUE % -1");
    }
}
