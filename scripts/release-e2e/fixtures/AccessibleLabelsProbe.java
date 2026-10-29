package releasee2e;

import java.util.List;
import java.util.Locale;
import javax.accessibility.*;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/** 验证可见诊断、循环与隐藏弹出层；不依赖桌面会话。 */
public final class AccessibleLabelsProbe {
    public static void main(String[] args) {
        Node root = new Node("root");
        Node message = new Node("visible failure");
        root.children = List.of(message, root, message);
        message.children = List.of(root);
        String text = ReleaseProbeAgent.accessibleLabels(root);
        if (!text.equals("root\nvisible failure\n") || root.reads != 1 || message.reads != 1) {
            throw new AssertionError("Visible diagnostic or cycle handling failed: " + text);
        }
        JPopupMenu hidden = new JPopupMenu() {
            @Override public AccessibleContext getAccessibleContext() {
                throw new AssertionError("Hidden component was traversed");
            }
        };
        if (!ReleaseProbeAgent.accessibleLabels(hidden).isEmpty()) {
            throw new AssertionError("Hidden content appeared in the desktop snapshot");
        }
        JPanel virtual = new JPanel() {
            @Override public AccessibleContext getAccessibleContext() { return new Node("virtual label"); }
        };
        if (!ReleaseProbeAgent.accessibleLabels(virtual).equals("virtual label\n")) {
            throw new AssertionError("Virtual accessibility component was excluded");
        }
        System.out.println("PASS: visible accessibility text, shared contexts, cycles and hidden components");
    }

    private static final class Node extends AccessibleContext implements Accessible {
        private final String name;
        private List<Accessible> children = List.of();
        private int reads;
        private Node(String name) { this.name = name; }
        @Override public AccessibleContext getAccessibleContext() { return this; }
        @Override public String getAccessibleName() { reads++; return name; }
        @Override public AccessibleRole getAccessibleRole() { return AccessibleRole.PANEL; }
        @Override public AccessibleStateSet getAccessibleStateSet() { return new AccessibleStateSet(); }
        @Override public int getAccessibleIndexInParent() { return 0; }
        @Override public int getAccessibleChildrenCount() { return children.size(); }
        @Override public Accessible getAccessibleChild(int index) { return children.get(index); }
        @Override public Locale getLocale() { return Locale.ROOT; }
    }
}
