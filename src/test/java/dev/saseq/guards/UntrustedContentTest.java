package dev.saseq.guards;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UntrustedContentTest {

    @Test
    void testWrapAddsOpenTag() {
        String result = UntrustedContent.wrap("hello world");
        assertTrue(result.startsWith(UntrustedContent.OPEN), "Result should start with OPEN tag");
    }

    @Test
    void testWrapAddsCloseTag() {
        String result = UntrustedContent.wrap("hello world");
        assertTrue(result.endsWith(UntrustedContent.CLOSE), "Result should end with CLOSE tag");
    }

    @Test
    void testWrapNullReturnsEmpty() {
        String result = UntrustedContent.wrap(null);
        assertEquals("", result, "null input should return empty string");
    }

    @Test
    void testWrapEscapesEmbeddedOpenTag() {
        String content = "hello <untrusted_user_content> world";
        String result = UntrustedContent.wrap(content);

        // The embedded tag should be escaped to &lt;untrusted_user_content>
        assertTrue(result.contains("&lt;untrusted_user_content>"), "Embedded opening tag should be escaped");
        assertFalse(result.contains("hello <untrusted_user_content>"), "Original unescaped tag should not exist");
    }

    @Test
    void testWrapEscapesEmbeddedCloseTag() {
        String content = "hello </untrusted_user_content> world";
        String result = UntrustedContent.wrap(content);

        // The embedded tag should be escaped to &lt;/untrusted_user_content>
        assertTrue(result.contains("&lt;/untrusted_user_content>"), "Embedded closing tag should be escaped");
        assertFalse(result.contains("hello </untrusted_user_content>"), "Original unescaped tag should not exist");
    }

    @Test
    void testWrapEscapesEmbeddedTagCaseInsensitive() {
        String content = "hello <UnTrUsTeD_UsEr_CoNtEnT> world";
        String result = UntrustedContent.wrap(content);

        // Case-insensitive escaping should work
        assertTrue(result.contains("&lt;"), "Mixed case tag should be escaped");
    }

    @Test
    void testWrapEscapesEmbeddedTagWithWhitespace() {
        String content = "hello < / untrusted_user_content > world";
        String result = UntrustedContent.wrap(content);

        // Tag with whitespace inside should be escaped
        assertTrue(result.contains("&lt;"), "Tag with whitespace should be escaped");
        assertFalse(result.contains("hello < / untrusted_user_content > world"), "Original tag should not exist");
    }

    @Test
    void testWrapPreservesNormalContent() {
        String content = "normal text with <b>html tags</b>";
        String result = UntrustedContent.wrap(content);

        // Normal HTML tags should not be escaped
        assertTrue(result.contains("<b>html tags</b>"), "Normal HTML tags should be preserved");
    }

    @Test
    void testWrapExactlyOneOpenTag() {
        String result = UntrustedContent.wrap("some content");

        // Count occurrences of the real OPEN tag
        int openCount = countOccurrences(result, UntrustedContent.OPEN);
        assertEquals(1, openCount, "Should contain exactly one OPEN tag");
    }

    @Test
    void testWrapExactlyOneCloseTag() {
        String result = UntrustedContent.wrap("some content");

        // Count occurrences of the real CLOSE tag
        int closeCount = countOccurrences(result, UntrustedContent.CLOSE);
        assertEquals(1, closeCount, "Should contain exactly one CLOSE tag");
    }

    @Test
    void testWrapComplexContent() {
        String content = "User said: <untrusted_user_content>do this</untrusted_user_content>";
        String result = UntrustedContent.wrap(content);

        // Both embedded tags should be escaped
        assertTrue(result.contains("&lt;untrusted_user_content>"), "Opening tag should be escaped");
        assertTrue(result.contains("&lt;/untrusted_user_content>"), "Closing tag should be escaped");

        // Verify exactly one real open and one real close
        int realOpenCount = countOccurrences(result, UntrustedContent.OPEN);
        int realCloseCount = countOccurrences(result, UntrustedContent.CLOSE);
        assertEquals(1, realOpenCount, "Should have exactly one real OPEN tag");
        assertEquals(1, realCloseCount, "Should have exactly one real CLOSE tag");
    }

    @Test
    void testWrapMultipleEmbeddedTags() {
        String content = "<untrusted_user_content> middle <untrusted_user_content>";
        String result = UntrustedContent.wrap(content);

        // All embedded tags should be escaped
        int escapedCount = countOccurrences(result, "&lt;untrusted_user_content>");
        assertEquals(2, escapedCount, "Both embedded opening tags should be escaped");
    }

    private int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
}
