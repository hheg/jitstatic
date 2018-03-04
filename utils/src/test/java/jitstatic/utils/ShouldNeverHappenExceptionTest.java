package jitstatic.utils;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ShouldNeverHappenExceptionTest {

    @Test
    public void test() {
        ShouldNeverHappenException s = new ShouldNeverHappenException("", new Exception());
        assertTrue(s.getMessage().isEmpty());
    }
}
