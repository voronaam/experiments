package com.acme.app;

/** Deliberately broken: the parser must recover, keep both types and count the file as failed. */
public class Broken {
    void ok() {
        int x = 1;
    }

    void missingParen( {
        if (x > 1 {
            return;
        }
    }
}

class AfterBroken {
    int y;

    void fine() {
    }
}
