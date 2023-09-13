package org.vorpal.research.kex.test.symgraph.strings;

public class StringContainer {
    StringContainer left;
    StringContainer right;
    String add;

    public StringContainer(String s) {
        left = right = null;
        add = s;
    }

    private StringContainer(StringContainer left, StringContainer right, String add) {
        this.left = left;
        this.right = right;
        this.add = add;
    }

    public StringContainer concat(StringContainer other) {
        return new StringContainer(this, other, "");
    }

    public boolean isEmpty() {
        return (left == null || left.isEmpty()) && (right == null || right.isEmpty()) && add.isEmpty();
    }

    public String collect() {
        return (left == null ? "" : left.collect()) + (right == null ? "" : right.collect()) + add;
    }

    public boolean firstAndLastEqual() {
        return StringUtils.firstAndLastEqual(collect());
    }
}
