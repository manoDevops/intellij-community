import junit.framework.TestCase;

class Base extends TestCase {
    public void setUp() {
        super.setUp();
    }
}

public class T extends Base {
    private int i;

    public void test() {
    }

    public void setUp() throws Exception {
        super.setUp();
        i = 9;
    }
}
