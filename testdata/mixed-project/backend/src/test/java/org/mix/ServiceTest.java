package org.mix;

import java.util.List;

class ServiceTest {
    void counts() {
        assert new Service(List.of("a", "ab")).count("a") == 2;
    }
}
