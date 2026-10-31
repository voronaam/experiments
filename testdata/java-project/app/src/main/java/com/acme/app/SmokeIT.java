package com.acme.app;

/** Test code by its name (a Failsafe integration test), although it sits under src/main. */
class SmokeIT {
    void runs() {
        Main.main(new String[0]);
    }
}
