package org.vorpal.research.kex.test.symgraph;

public class Node {
    private Node next;
    private int value;

    private Node(Node n, int v) {
        this.next = n;
        this.value = v;
    }


    private static Node construct(Node v, int value) {
        return new Node(v, value);
    }

    public static Node create(int v, boolean b) {
        // if (b == true) {
        //      return new Node(null, v * 2 + 1);
        // } else {
        //      return new Node(null, v * 2);
        // }
        int value = v * 2;
        if (b == true) {
            value++;
        }
        // return new Node(null, value);
        Node toReturn = construct(null, value);
        return toReturn;
    }

    public Node getNext() {
        return this.next;
    }

    public int getValue() {
        return this.value;
    }

    public void addAfter(int v) {
        this.next = construct(this.next, v);
    }

    public Node addBefore(int v) {
        return new Node(this, v);
    }
}
