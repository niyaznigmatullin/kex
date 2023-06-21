package org.vorpal.research.kex.test.symgraph;

public class StaticDefaultInstance {
    public static StaticDefaultInstance INSTANCE = new StaticDefaultInstance();

    private StaticDefaultInstance() {
    }

    public static StaticDefaultInstance getDefault() {
        return INSTANCE;
    }
}
