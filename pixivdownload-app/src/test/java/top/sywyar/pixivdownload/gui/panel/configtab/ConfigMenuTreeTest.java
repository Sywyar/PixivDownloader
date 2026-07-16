package top.sywyar.pixivdownload.gui.panel.configtab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static top.sywyar.pixivdownload.gui.panel.configtab.ConfigMenuTree.branch;
import static top.sywyar.pixivdownload.gui.panel.configtab.ConfigMenuTree.leaf;

@DisplayName("配置菜单通用树")
class ConfigMenuTreeTest {

    @Test
    @DisplayName("未展开时保留原根节点视图")
    void disabledExpansionKeepsOriginalRoots() {
        ConfigMenuTree.Leaf<Object> leaf = leaf("root.leaf", "叶子", new Object());
        ConfigMenuTree.Branch<Object> branch = branch("root.branch", "分支", List.of(leaf));
        ConfigMenuTree<Object> tree = new ConfigMenuTree<>(List.of(branch));

        assertThat(tree.roots(false)).isSameAs(tree.roots());
        assertThat(tree.roots(false)).containsExactly(branch);
        assertThat(tree.roots(false).get(0)).isSameAs(branch);
    }

    @Test
    @DisplayName("四层不等深菜单按深度优先顺序收集全部叶子")
    void collectsUnevenFourLevelTreeDepthFirst() {
        ConfigMenuTree.Leaf<String> shallow = leaf("leaf.shallow", "浅层", "shallow");
        ConfigMenuTree.Leaf<String> middle = leaf("leaf.middle", "中层", "middle");
        ConfigMenuTree.Leaf<String> deep = leaf("leaf.deep", "深层", "deep");
        ConfigMenuTree.Leaf<String> afterBranch = leaf("leaf.after", "分支后", "after");
        ConfigMenuTree.Leaf<String> secondRoot = leaf("leaf.root", "第二根", "root");

        ConfigMenuTree.Branch<String> levelFour = branch(
                "branch.level-four", "第四层", List.of(deep));
        ConfigMenuTree.Branch<String> levelThree = branch(
                "branch.level-three", "第三层", List.of(levelFour));
        ConfigMenuTree.Branch<String> levelTwo = branch(
                "branch.level-two", "第二层", List.of(middle, levelThree));
        ConfigMenuTree.Branch<String> levelOne = branch(
                "branch.level-one", "第一层", List.of(shallow, levelTwo, afterBranch));
        ConfigMenuTree<String> tree = new ConfigMenuTree<>(List.of(levelOne, secondRoot));

        assertThat(tree.leavesDepthFirst())
                .containsExactly(shallow, middle, deep, afterBranch, secondRoot);
        assertThat(tree.roots(true))
                .containsExactly(shallow, middle, deep, afterBranch, secondRoot);
    }

    @Test
    @DisplayName("同名叶子依靠独立稳定标识全部保留")
    void preservesLeavesWithSameLabel() {
        ConfigMenuTree.Leaf<String> first = leaf("leaf.first", "同名页", "first");
        ConfigMenuTree.Leaf<String> second = leaf("leaf.second", "同名页", "second");
        ConfigMenuTree<String> tree = new ConfigMenuTree<>(List.of(
                branch("branch.same-label", "容器", List.of(first, second))));

        assertThat(tree.leavesDepthFirst()).containsExactly(first, second);
        assertThat(tree.leavesDepthFirst())
                .extracting(ConfigMenuTree.Leaf::id)
                .containsExactly("leaf.first", "leaf.second");
        assertThat(tree.leavesDepthFirst())
                .extracting(ConfigMenuTree.Leaf::label)
                .containsExactly("同名页", "同名页");
    }

    @Test
    @DisplayName("展开视图复用原叶节点和载荷实例")
    void expandedViewReusesLeafAndPayloadIdentity() {
        Object payload = new Object();
        ConfigMenuTree.Leaf<Object> leaf = leaf("leaf.identity", "身份", payload);
        ConfigMenuTree<Object> tree = new ConfigMenuTree<>(List.of(
                branch("branch.identity", "容器", List.of(leaf))));

        ConfigMenuTree.Node<Object> expanded = tree.roots(true).get(0);

        assertThat(expanded).isSameAs(leaf);
        assertThat(((ConfigMenuTree.Leaf<Object>) expanded).payload()).isSameAs(payload);
    }
}
