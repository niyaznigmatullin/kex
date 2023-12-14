package org.vorpal.research.kex.test.symgraph;

/**
 * The PersistentList class represents an immutable linked list data structure.
 * Each list node contains an integer element and a reference to the next node in the list.
 */
public class PersistentLinkedList {

    public static final PersistentLinkedList EMPTY = new PersistentLinkedList();

    private final int element;
    private final PersistentLinkedList next;

    private PersistentLinkedList() {
        this.element = 0;
        this.next = null;
    }

    private PersistentLinkedList(int element, PersistentLinkedList next) {
        this.element = element;
        this.next = next;
    }

    public PersistentLinkedList add(int element) {
        return new PersistentLinkedList(element, this);
    }

    public static PersistentLinkedList empty() {
        return EMPTY;
    }

    @Override
    public String toString() {
        if (this == EMPTY) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(this.element);

        PersistentLinkedList node = this.next;
        while (node != EMPTY) {
            sb.append(", ").append(node.element);
            node = node.next;
        }
        return sb.append("]").toString();
    }
}
