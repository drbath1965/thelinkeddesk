package com.linkeddesk;

import java.util.ArrayList;
import java.util.List;

/** A tiny assertion helper and runner, so tests need nothing beyond the JDK. */
public final class Check {
    private int passed;
    private final List<String> failures = new ArrayList<>();
    private String suite = "";

    public void suite(String name) {
        suite = name;
        System.out.println("== " + name);
    }

    public void that(boolean ok, String what) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failures.add(suite + ": " + what);
            System.out.println("  FAIL " + what);
        }
    }

    public void eq(Object expected, Object actual, String what) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        that(ok, what + (ok ? "" : "  (expected <" + expected + "> but was <" + actual + ">)"));
    }

    public int passed() { return passed; }
    public List<String> failures() { return failures; }
}
