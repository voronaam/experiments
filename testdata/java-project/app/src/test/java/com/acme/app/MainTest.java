package com.acme.app;

import com.acme.core.Registry;
import org.junit.jupiter.api.Test;

/** Test code by its directory (src/test). */
class MainTest {
    @Test
    void runs() {
        Main.main(new String[]{"fast"});
        Registry.Mode mode = Registry.Mode.AUTO;
    }
}
