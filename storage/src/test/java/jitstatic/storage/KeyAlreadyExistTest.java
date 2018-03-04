package jitstatic.storage;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class KeyAlreadyExistTest {

    @Test
    public void test() {
        KeyAlreadyExist k = new KeyAlreadyExist("key", "branch");
        assertEquals("key already exist in branch branch", k.getMessage());
    }
}
