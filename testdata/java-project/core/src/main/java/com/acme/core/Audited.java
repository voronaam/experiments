package com.acme.core;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** An annotation type: its elements are methods. */
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    String owner();

    int level() default 1;
}
