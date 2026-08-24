package top.sywyar.pixivdownload.gui.panel.configtab;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Framework-independent configuration menu tree.
 *
 * <p>Node identity is represented by an explicit stable id and never derived from the display label.
 * Traversal keeps the original node and payload instances, preserves depth-first order, and does not
 * collapse leaves that happen to share the same label.</p>
 */
public final class ConfigMenuTree<T> {

    private final List<Node<T>> roots;

    public ConfigMenuTree(List<? extends Node<T>> roots) {
        this.roots = copyNodes(roots, "roots");
    }

    /** Returns the original immutable root-node view. */
    public List<Node<T>> roots() {
        return roots;
    }

    /**
     * Returns either the original roots or a root-level view containing every leaf in depth-first order.
     */
    public List<Node<T>> roots(boolean expandAllBranches) {
        if (!expandAllBranches) {
            return roots;
        }
        List<Node<T>> expanded = new ArrayList<>();
        expanded.addAll(leavesDepthFirst());
        return List.copyOf(expanded);
    }

    /** Collects every leaf below every root in depth-first order. */
    public List<Leaf<T>> leavesDepthFirst() {
        return leavesDepthFirst(roots);
    }

    /** Collects every leaf below the supplied nodes in depth-first order. */
    public static <T> List<Leaf<T>> leavesDepthFirst(List<? extends Node<T>> nodes) {
        Objects.requireNonNull(nodes, "nodes");
        List<Leaf<T>> leaves = new ArrayList<>();
        for (Node<T> node : nodes) {
            leaves.addAll(Objects.requireNonNull(node, "node").leavesDepthFirst());
        }
        return List.copyOf(leaves);
    }

    public static <T> Branch<T> branch(String id, String label, List<? extends Node<T>> children) {
        return new Branch<>(id, label, copyNodes(children, "children"));
    }

    public static <T> Leaf<T> leaf(String id, String label, T payload) {
        return new Leaf<>(id, label, payload);
    }

    public sealed interface Node<T> permits Branch, Leaf {

        String id();

        String label();

        List<Leaf<T>> leavesDepthFirst();
    }

    public record Branch<T>(String id, String label, List<Node<T>> children) implements Node<T> {

        public Branch {
            id = requireText(id, "id");
            label = requireText(label, "label");
            children = copyNodes(children, "children");
        }

        @Override
        public List<Leaf<T>> leavesDepthFirst() {
            return ConfigMenuTree.leavesDepthFirst(children);
        }
    }

    public record Leaf<T>(String id, String label, T payload) implements Node<T> {

        public Leaf {
            id = requireText(id, "id");
            label = requireText(label, "label");
            payload = Objects.requireNonNull(payload, "payload");
        }

        @Override
        public List<Leaf<T>> leavesDepthFirst() {
            return List.of(this);
        }
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static <T> List<Node<T>> copyNodes(List<? extends Node<T>> nodes, String name) {
        Objects.requireNonNull(nodes, name);
        return List.copyOf(nodes);
    }
}
