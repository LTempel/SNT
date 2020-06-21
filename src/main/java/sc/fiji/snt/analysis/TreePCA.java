/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2019 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General private License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General private License for more details.
 *
 * You should have received a copy of the GNU General private
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt.analysis;

import org.jgrapht.Graphs;
import org.jgrapht.traverse.DepthFirstIterator;

import sc.fiji.snt.SNTService;
import sc.fiji.snt.Tree;
import sc.fiji.snt.analysis.graph.DirectedWeightedGraph;
import sc.fiji.snt.analysis.graph.SWCWeightedEdge;
import sc.fiji.snt.util.SWCPoint;

import java.math.BigInteger;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * Class for principal component analysis of {@link Tree}s
 * For an overview, see
 * Alfaro, C. et al. Dimension Reduction in Principal Component Analysis for Trees.
 * Computational Statistics & Data Analysis. 74. (2012).
 *
 * @author Cameron Arshadi
 */
public class TreePCA {

    private final List<Tree> inputTreeList;
    private int k;
    private List<KTree> kTreeList;
    private final Map<BigInteger, Node> allNodes = new HashMap<>();
    private KTree supportTree;
    private ArrayList<TreeLine> allTreeLines;
    private List<TreeLine> forwardPrincipalComponents;
    private List<TreeLine> backwardPrincipalComponents;

    public TreePCA(final Collection<Tree> trees) {
        this.inputTreeList = new ArrayList<>(trees);
        if (inputTreeList.isEmpty()) throw new NoSuchElementException("Empty Tree Collection given");
    }

    public TreePCA(final Collection<Tree> trees, final String... swcTypes) throws NoSuchElementException {
        this.inputTreeList = new ArrayList<>();
        trees.forEach(inputTree -> {
            final Tree filteredTree = inputTree.subTree(swcTypes);
            if (filteredTree != null && filteredTree.size() > 0) inputTreeList.add(filteredTree);
        });
        if (inputTreeList.isEmpty()) throw new NoSuchElementException("No match for the specified type(s) in group");
    }

    public List<Tree> getTrees() {
        return this.inputTreeList;
    }

    public int getTotalDimension() {
        if (allTreeLines == null || allTreeLines.isEmpty()) {
            buildTreeLines();
        }
        return allTreeLines.size();
    }

    public List<TreeLine> getFPCs() {
        if (allTreeLines == null || allTreeLines.isEmpty()) {
            buildTreeLines();
        }
        if (forwardPrincipalComponents == null
                || forwardPrincipalComponents.isEmpty()
                || forwardPrincipalComponents.size() < allTreeLines.size()) {
            forwardAlgorithm(allTreeLines.size());
        }
        return forwardPrincipalComponents;
    }

    public List<TreeLine> getFPCs(final int numComponents) {
        if (numComponents < 1) {
            throw new IllegalArgumentException("numComponents must be >= 1");
        }
        if (allTreeLines == null || allTreeLines.isEmpty()) {
            buildTreeLines();
        }
        if (numComponents > allTreeLines.size()) {
            throw new IllegalArgumentException(
                    "numComponents (" + numComponents + ") "
                            + "must be >= 1 and <= total num tree lines (" + allTreeLines.size() + ")"
            );
        }
        if (forwardPrincipalComponents == null
                || forwardPrincipalComponents.isEmpty()
                || forwardPrincipalComponents.size() < numComponents) {
            forwardAlgorithm(numComponents);
        }
        return forwardPrincipalComponents.subList(0, numComponents);
    }

    public List<TreeLine> getBPCs() {
        if (allTreeLines == null || allTreeLines.isEmpty()) {
            buildTreeLines();
        }
        if (backwardPrincipalComponents == null
                || backwardPrincipalComponents.isEmpty()
                || backwardPrincipalComponents.size() < allTreeLines.size()) {
            backwardAlgorithm(allTreeLines.size());
        }
        return backwardPrincipalComponents;
    }

    public List<TreeLine> getBPCs(final int numComponents) {
        if (numComponents < 1) {
            throw new IllegalArgumentException("numComponents must be >= 1");
        }
        if (allTreeLines == null || allTreeLines.isEmpty()) {
            buildTreeLines();
        }
        if (numComponents > allTreeLines.size()) {
            throw new IllegalArgumentException(
                    "numComponents (" + numComponents + ") "
                            + "must be >= 1 and <= total num tree lines (" + allTreeLines.size() + ")"
            );
        }
        if (backwardPrincipalComponents == null
                || backwardPrincipalComponents.isEmpty()
                || backwardPrincipalComponents.size() < numComponents) {
            backwardAlgorithm(numComponents);
        }
        return backwardPrincipalComponents.subList(0, numComponents);
    }

    public List<KTree> getKTrees() {
        if (kTreeList == null || kTreeList.isEmpty()) {
            buildKTrees();
        }
        return kTreeList;
    }

    public KTree getSupportKTree() {
        if (supportTree == null || supportTree.indexToNodeMap.isEmpty()) {
            buildSupportKTree();
        }
        return supportTree;
    }

    public KTree getIntersectionKTree() {
        if (kTreeList == null || kTreeList.isEmpty()) {
            buildKTrees();
        }
        Set<Node> inter = new HashSet<>(kTreeList.get(0).indexToNodeMap.values());
        for (int i = 1; i < kTreeList.size(); i++) {
            inter.retainAll(kTreeList.get(i).indexToNodeMap.values());
        }
        return new KTree(inter);
    }

    public Tree getMedianTree() {
        if (kTreeList == null || kTreeList.isEmpty()) {
            buildKTrees();
        }
        Map<KTree, Integer> distanceMap = new HashMap<>();
        for (KTree t1 : kTreeList) {
            int currentSum = 0;
            for (KTree t2 : kTreeList) {
                int dist = t1.distanceTo(t2);
                currentSum += dist;
            }
            distanceMap.put(t1, currentSum);
        }
        Entry<KTree, Integer> min = Collections.min(distanceMap.entrySet(), Entry.comparingByValue());
        int minDist = min.getValue();
        int minNumNodes = min.getKey().indexToNodeMap.size();
        KTree minMedianTree = min.getKey();
        for (KTree kTree : distanceMap.keySet()) {
            if (distanceMap.get(kTree) == minDist && kTree.indexToNodeMap.size() < minNumNodes) {
                minMedianTree = kTree;
                minNumNodes = kTree.indexToNodeMap.size();
            }
        }
        return minMedianTree.tree;
    }

    private int integerTreeMetric(KTree kt1, KTree kt2) {
        Set<BigInteger> indexSet1 = kt1.indexToNodeMap.keySet();
        Set<BigInteger> indexSet2 = kt2.indexToNodeMap.keySet();
        return symmetricDifference(indexSet1, indexSet2).size();
    }

    private void buildKTrees() {
        kTreeList = new ArrayList<>();
        k = 0;
        List<DirectedWeightedGraph> graphList = new ArrayList<>();
        for (Tree tree : inputTreeList) {
            // Use simple graph
            DirectedWeightedGraph graph = tree.getGraph(true);
            //graph.setLabel(tree.getLabel());
            graphList.add(graph);
            List<SWCPoint> bps = graph.getBPs();
            for (SWCPoint bp : bps) {
                int numChildren = graph.outDegreeOf(bp);
                if (numChildren > k) {
                    k = numChildren;
                }
            }
        }
        for (int i = 0; i < graphList.size(); i++) {
            KTree kTree = new KTree(inputTreeList.get(i), graphList.get(i));
            kTreeList.add(kTree);
        }
    }

    private void buildSupportKTree() {
        if (kTreeList == null || kTreeList.isEmpty()) {
            buildKTrees();
        }
        Map<BigInteger, Node> supportTreeIndexNodeMap = allNodes;

        Node root = null;
        for (Node node : supportTreeIndexNodeMap.values()) {
            if (node.parent != null) {
                node.parent.children.add(node);
            } else {
                root = node;
            }
        }
        supportTree = new KTree(supportTreeIndexNodeMap);
        supportTree.root = root;
    }

    private void buildTreeLines() {
        if (supportTree == null || supportTree.indexToNodeMap.isEmpty()) {
            buildSupportKTree();
        }
        List<Node> tips = new ArrayList<>();
        for (Node n : supportTree.indexToNodeMap.values()) {
            if (n.children.isEmpty()) {
                tips.add(n);
            }
        }
        allTreeLines = new ArrayList<>();
        int id = 0;
        for (Node t : tips) {
            ArrayList<Node> path = new ArrayList<>();
            Node current = t;
            while (current != null) {
                path.add(current);
                current = current.parent;
            }
            Collections.reverse(path);
            ArrayList<KTree> line = new ArrayList<>();
            for (int i = 0; i < path.size(); i++) {
                ArrayList<Node> subline = new ArrayList<>();
                for (int j = 0; j < i + 1; j++) {
                    subline.add(path.get(j));
                }
                line.add(new KTree(subline));
            }
            TreeLine tLine = new TreeLine(line, path);
            tLine.id = id;
            ++id;
            allTreeLines.add(tLine);
        }
    }

    private void resetWeights() {
        for (Node node : allNodes.values()) {
            node.weight = 0;
            node.cumulativeWeight = 0;
        }
    }

    private void assignForwardWeights() {
        for (Node node : supportTree.indexToNodeMap.values()) {
            node.weight = node.totalOccurrences;
        }
        // assign 0 weight to root node
        supportTree.root.weight = 0;
    }

    private void assignBackwardWeights(ArrayList<TreeLine> currentTreeLines) {
        Map<BigInteger, Node> supportB = new HashMap<>();
        for (Node n : supportTree.indexToNodeMap.values()) {
            n.currentLinesOccurences = 0;
        }
        for (TreeLine line : currentTreeLines) {
            for (Node n : line.path) {
                ++n.currentLinesOccurences;
                supportB.put(n.index, n);
            }
        }
        for (Node n : supportB.values()) {
            if (n.parent == null) {
                n.weight = 0;
                continue;
            }
            if (n.currentLinesOccurences >= 2) {
                n.weight = 0;
            } else {
                n.weight = n.totalOccurrences;
            }
        }
    }

    private TreeLine getLeftmostLine(List<TreeLine> lines) {
        List<List<Node>> pathList = new ArrayList<>();
        int[] pathLengths = new int[lines.size()];
        int longest = 0;
        for (int i = 0; i < lines.size(); i++) {
            List<Node> path = lines.get(i).path;
            pathList.add(path);
            int pathLength = path.size();
            pathLengths[i] = pathLength;
            if (pathLength > longest) {
                longest = pathLength;
            }
        }
        int numPaths = pathList.size();
        Set<Integer> losers = new HashSet<>();
        Node[] currentNodes = new Node[numPaths];
        Integer winner = null;
        for (int i = 0; i < longest; i++) {
            if (losers.size() == numPaths - 1) {
                break;
            }
            for (int j = 0; j < numPaths; j++) {
                if (i >= pathLengths[j]) {
                    currentNodes[j] = null;
                    losers.add(j);
                } else {
                    currentNodes[j] = pathList.get(j).get(i);
                }
            }
            int min = k;  // order is at most k-1
            for (int j = 0; j < currentNodes.length; j++) {
                if (currentNodes[j] != null && !losers.contains(j) && currentNodes[j].order < min) {
                    min = currentNodes[j].order;
                    winner = j;
                }
            }
            for (int j = 0; j < currentNodes.length; j++) {
                if (currentNodes[j] != null && !losers.contains(j) && currentNodes[j].order > min) {
                    losers.add(j);
                }
            }
        }
        return lines.get(winner);
    }

    private TreeLine getRightmostLine(List<TreeLine> lines) {
        List<List<Node>> pathList = new ArrayList<>();
        int[] pathLengths = new int[lines.size()];
        int longest = 0;
        for (int i = 0; i < lines.size(); i++) {
            List<Node> path = lines.get(i).path;
            pathList.add(path);
            int pathLength = path.size();
            pathLengths[i] = pathLength;
            if (pathLength > longest) {
                longest = pathLength;
            }
        }
        int numPaths = pathList.size();
        Set<Integer> losers = new HashSet<>();
        Node[] currentNodes = new Node[numPaths];
        Integer winner = null;
        for (int i = 0; i < longest; i++) {
            if (losers.size() == numPaths - 1) {
                break;
            }
            for (int j = 0; j < numPaths; j++) {
                if (i >= pathLengths[j]) {
                    currentNodes[j] = null;
                    losers.add(j);
                } else {
                    currentNodes[j] = pathList.get(j).get(i);
                }
            }
            int max = -1;  // order is at least 0
            for (int j = 0; j < currentNodes.length; j++) {
                if (currentNodes[j] != null && !losers.contains(j) && currentNodes[j].order > max) {
                    max = currentNodes[j].order;
                    winner = j;
                }
            }
            for (int j = 0; j < currentNodes.length; j++) {
                if (currentNodes[j] != null && !losers.contains(j) && currentNodes[j].order < max) {
                    losers.add(j);
                }
            }
        }
        return lines.get(winner);
    }

    private void forwardAlgorithm(final int numComponents) {
        resetWeights();
        assignForwardWeights();

        @SuppressWarnings("unchecked")
        ArrayList<TreeLine> allTreeLinesCopy = (ArrayList<TreeLine>) allTreeLines.clone();
        forwardPrincipalComponents = new ArrayList<>();
        int pc = 0;
        while (pc < numComponents) {
            //System.out.println(pc);
            int maxSumWeights = Integer.MIN_VALUE;
            Stack<Node> stack = new Stack<>();
            for (Node child : supportTree.root.children) {
                child.cumulativeWeight = child.weight + child.parent.cumulativeWeight;
                stack.push(child);
            }
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                n.cumulativeWeight = n.weight + n.parent.cumulativeWeight;
                if (n.children.size() == 0) {
                    if (n.cumulativeWeight > maxSumWeights) {
                        maxSumWeights = n.cumulativeWeight;
                    }
                } else {
                    for (Node child : n.children) {
                        stack.push(child);
                    }
                }
            }
            ArrayList<TreeLine> candidateLines = new ArrayList<>();
            for (TreeLine line : allTreeLinesCopy) {
                List<Node> path = line.path;
                if (path.get(path.size() - 1).cumulativeWeight == maxSumWeights) {
                    candidateLines.add(line);
                }
            }
            TreeLine bestLine;
            if (candidateLines.size() > 1) {
                bestLine = getLeftmostLine(candidateLines);
            } else if (candidateLines.size() == 1) {
                bestLine = candidateLines.get(0);
            } else {
                throw new IllegalStateException("Somehow there are no candidate lines");
            }
            //System.out.println("pc " + pc + " weight = " + maxSumWeights + ", line id = " + bestLine.id);
            forwardPrincipalComponents.add(bestLine);
            for (Node n : bestLine.path) {
                n.weight = 0;
            }
            ++pc;
            allTreeLinesCopy.remove(bestLine);
        }
    }

    private void backwardAlgorithm(final int numComponents) {
        resetWeights();

        @SuppressWarnings("unchecked")
        ArrayList<TreeLine> allTreeLinesCopy = (ArrayList<TreeLine>) allTreeLines.clone();
        backwardPrincipalComponents = new ArrayList<>();
        int pc = 0;
        while (pc < numComponents) {
            //System.out.println(pc);
            assignBackwardWeights(allTreeLinesCopy);
            int minSumWeights = Integer.MAX_VALUE;
            for (TreeLine treeLine : allTreeLinesCopy) {
                if (treeLine.getWeight() < minSumWeights) {
                    minSumWeights = treeLine.getWeight();
                }
            }
            ArrayList<TreeLine> candidateLines = new ArrayList<>();
            for (TreeLine line : allTreeLinesCopy) {
                if (line.getWeight() == minSumWeights) {
                    candidateLines.add(line);
                }
            }
            TreeLine bestLine;
            if (candidateLines.size() > 1) {
                bestLine = getRightmostLine(candidateLines);
            } else if (candidateLines.size() == 1) {
                bestLine = candidateLines.get(0);
            } else {
                throw new IllegalStateException("Somehow there are no candidate lines");
            }
            //System.out.println("pc " + pc + " weight = " + minSumWeights + ", line id = " + bestLine.id);
            backwardPrincipalComponents.add(bestLine);
            ++pc;
            allTreeLinesCopy.remove(bestLine);
        }
    }

    public int getTotalNumNodes() {
        if (kTreeList == null || kTreeList.isEmpty()) {
            buildKTrees();
        }
        int numNodes = 0;
        for (KTree t : kTreeList) {
            numNodes += t.indexToNodeMap.size();
        }
        return numNodes;
    }

    public int getNumExplainedNodes(TreeLine component) {
        if (!allTreeLines.contains(component)) {
            throw new IllegalArgumentException("Component not present in data");
        }
        if (kTreeList == null || kTreeList.isEmpty()) {
            buildKTrees();
        }
        List<KTree> line = component.getLine();
        Set<BigInteger> lastInLine = line.get(line.size() - 1).indexToNodeMap.keySet();
        int explainedNodes = 0;
        for (KTree t : kTreeList) {
            for (BigInteger index : lastInLine) {
                if (t.indexToNodeMap.containsKey(index)) {
                    ++explainedNodes;
                }
            }
        }
        return explainedNodes;
    }

    private Set<BigInteger> union(Set<BigInteger> s1, Set<BigInteger> s2) {
        Set<BigInteger> tmp = new HashSet<>(s1);
        tmp.addAll(s2);
        return tmp;
    }

    private Set<BigInteger> intersection(Set<BigInteger> s1, Set<BigInteger> s2) {
        Set<BigInteger> tmp = new HashSet<>(s1);
        tmp.retainAll(s2);
        return tmp;
    }

    private Set<BigInteger> symmetricDifference(Set<BigInteger> s1, Set<BigInteger> s2) {
        Set<BigInteger> symmetricDiff = new HashSet<>(s1);
        symmetricDiff.addAll(s2);
        Set<BigInteger> tmp = new HashSet<>(s1);
        tmp.retainAll(s2);
        symmetricDiff.removeAll(tmp);
        return symmetricDiff;
    }

    private Node getNode(BigInteger index) {
        Node node = allNodes.get(index);
        if (node == null) {
            node = new Node(index);
            allNodes.put(node.index, node);
        }
        return node;
    }

    public static class TreeLine {
        int id;
        private final List<KTree> line;
        private final List<Node> path;

        private TreeLine(ArrayList<KTree> line, ArrayList<Node> path) {
            this.line = line;
            this.path = path;
        }

        public List<KTree> getLine() {
            return this.line;
        }

        public List<Node> getPath() {
            return this.path;
        }

        private int getWeight() {
            int totalWeight = 0;
            for (Node n : path) {
                totalWeight += n.weight;
            }
            return totalWeight;
        }

//		@Override
//		public boolean equals(Object obj) {
//			if (this == obj) {
//				return true;
//			}
//			if (!(obj instanceof TreeLine)) {
//				return false;
//			}
//			TreeLine other = (TreeLine) obj;
//			if (this.path.size() != other.path.size()) {
//				return false;
//			}
//			for (int i = 0; i < this.path.size(); i++) {
//				if (!this.path.get(i).index.equals(other.path.get(i).index)) {
//					return false;
//				}
//			}
//			return true;
//		}

    }

    public static class Node implements Comparable<Node> {

        private final BigInteger index;

        private Node parent;
        // children are ordered by correspondence
        private final List<Node> children = new ArrayList<>();
        // temp value used in both forward and backward passes
        private int weight;
        // temp value used during forward algorithm
        private int cumulativeWeight;
        // value used to assign forward weight
        private int totalOccurrences;
        // value used in determination of backward weight
        private int currentLinesOccurences;
        // property used for tie-breaking during PCA
        // called "correspondence" in paper
        private Integer order = null;

        private Node(BigInteger index) {
            this.index = index;
        }

        public Node getParent() {
            return this.parent;
        }

        public List<Node> getChildren() {
            return this.children;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof Node)) {
                return false;
            }
            Node other = (Node) obj;
            return this.index.equals(other.index);
        }

        @Override
        public int hashCode() {
            return this.index.hashCode();
        }

        @Override
        public String toString() {
            return index.toString();
        }

        @Override
        public int compareTo(Node o) {
            return this.index.compareTo(o.index);
        }
    }

    enum Correspondence {
        NUM_DESCENDANTS, SUBTREE_LENGTH
    }

    public class KTree {
        private Tree tree;
        private final int k;
        private Node root;
        private Map<BigInteger, Node> indexToNodeMap;

        private final Correspondence correspondence = Correspondence.SUBTREE_LENGTH;

        private KTree(final Collection<Node> nodes) {
            this.k = TreePCA.this.k;
            indexToNodeMap = new HashMap<>();
            for (Node n : nodes) {
                indexToNodeMap.put(n.index, n);
            }
            this.root = indexToNodeMap.get(BigInteger.ZERO);
            if (root == null) {
                throw new NoSuchElementException("No root");
            }
        }

        private KTree(final Map<BigInteger, Node> indexNodeMap) {
            this.k = TreePCA.this.k;
            this.indexToNodeMap = indexNodeMap;
            this.root = indexNodeMap.get(BigInteger.ZERO);
            if (root == null) {
                throw new NoSuchElementException("No root");
            }
        }

        private KTree(final Tree tree, final DirectedWeightedGraph graph) {
            this.tree = tree;
            this.k = TreePCA.this.k;
            init(graph);
        }

        private void init(DirectedWeightedGraph graph) {
            Map<SWCPoint, Node> swcPointToNodeMap = new HashMap<>();
            indexToNodeMap = new HashMap<>();
            SWCPoint root = graph.getRoot();
            Node rootNode = getNode(BigInteger.ZERO);
            ++rootNode.totalOccurrences;
            rootNode.order = 0;
            swcPointToNodeMap.put(root, rootNode);
            indexToNodeMap.put(rootNode.index, rootNode);
            this.root = rootNode;
            DepthFirstIterator<SWCPoint, SWCWeightedEdge> dfi = graph.getDepthFirstIterator(root);
            while (dfi.hasNext()) {
                SWCPoint current = dfi.next();
                List<SWCPoint> children = Graphs.successorListOf(graph, current);
                if (!children.isEmpty()) {
                    Map<SWCPoint, Double> correspondenceMap = new HashMap<>();
                    if (correspondence == Correspondence.SUBTREE_LENGTH) {
                        for (SWCPoint child : children) {
                            correspondenceMap.put(child, getSubtreeLength(graph, child));
                        }
                    } else if (correspondence == Correspondence.NUM_DESCENDANTS) {
                        for (SWCPoint child : children) {
                            correspondenceMap.put(child, getNumDescendants(graph, child));
                        }
                    } else {
                        throw new IllegalStateException("Unrecognized Correspondence...");
                    }
                    List<SWCPoint> sortedChildren = correspondenceMap.entrySet().stream()
                            .sorted(Entry.comparingByValue(Comparator.reverseOrder())).map(Entry::getKey)
                            .collect(Collectors.toList());
                    int j = 1;
                    int order = 0;
                    for (SWCPoint childSwcPoint : sortedChildren) {
                        Node currentNode = swcPointToNodeMap.get(current);
                        BigInteger childIndex = currentNode.index
                                .multiply(BigInteger.valueOf(k))
                                .add(BigInteger.valueOf(j));
                        Node childNode = getNode(childIndex);
                        childNode.order = order;
                        childNode.parent = currentNode;
                        ++childNode.totalOccurrences;
                        swcPointToNodeMap.put(childSwcPoint, childNode);
                        indexToNodeMap.put(childNode.index, childNode);
                        ++j;
                        ++order;
                    }
                }
            }
        }

        public int getK() {
            return this.k;
        }

        public Tree getTree() {
            return this.tree;
        }

        public List<Node> getNodes() {
            return new ArrayList<>(this.indexToNodeMap.values());
        }

        public int distanceTo(KTree other) {
            return integerTreeMetric(this, other);
        }

        public KTree projectToTreeLine(TreeLine line) {
            KTree minTree = null;
            int minDist = Integer.MAX_VALUE;
            for (KTree t : line.getLine()) {
                int dist = this.distanceTo(t);
                if (dist < minDist) {
                    minTree = t;
                    minDist = dist;
                }
            }
            return minTree;
        }

        private double getNumDescendants(DirectedWeightedGraph graph, SWCPoint point) {
            DepthFirstIterator<SWCPoint, SWCWeightedEdge> dfi = graph.getDepthFirstIterator(point);
            int tipCount = 0;
            while (dfi.hasNext()) {
                dfi.next();
                ++tipCount;
            }
            return tipCount;
        }

        private double getSubtreeLength(DirectedWeightedGraph graph, SWCPoint point) {
            DepthFirstIterator<SWCPoint, SWCWeightedEdge> dfi = graph.getDepthFirstIterator(point);
            double totalWeight = 0;
            while (dfi.hasNext()) {
                SWCPoint current = dfi.next();
                SWCPoint parent = Graphs.predecessorListOf(graph, current).get(0);
                totalWeight += graph.getEdge(parent, current).getWeight();
            }
            return totalWeight;
        }

        public void draw2D() {
            DirectedWeightedGraph graph = new DirectedWeightedGraph();
            Map<Node, SWCPoint> pointMap = new HashMap<>();
            for (Node n : indexToNodeMap.values()) {
                SWCPoint p = new SWCPoint(0, 2, 0, 0, 0, 1.0, -1);
                graph.addVertex(p);
                pointMap.put(n, p);
            }
            for (Node n : pointMap.keySet()) {
                if (n.parent != null) {
                    Node np = n.parent;
                    SWCPoint point = pointMap.get(n);
                    SWCPoint pointParent = pointMap.get(np);
                    graph.addEdge(pointParent, point);
                }
            }
            graph.show();
        }
    }

    private void profile() {
        long startTime = System.currentTimeMillis();
        buildKTrees();
        long endTime = System.currentTimeMillis();
        System.out.println("build k-way trees = " + (endTime - startTime) + " milliseconds");

        startTime = System.currentTimeMillis();
        buildSupportKTree();
        endTime = System.currentTimeMillis();
        System.out.println("build support tree =  " + (endTime - startTime) + " milliseconds");

        startTime = System.currentTimeMillis();
        buildTreeLines();
        endTime = System.currentTimeMillis();
        System.out.println("build tree lines =  " + (endTime - startTime) + " milliseconds");

        startTime = System.currentTimeMillis();
        forwardAlgorithm(allTreeLines.size());
        endTime = System.currentTimeMillis();
        System.out.println("forward algorithm = " + (endTime - startTime) + " milliseconds");

        startTime = System.currentTimeMillis();
        backwardAlgorithm(allTreeLines.size());
        endTime = System.currentTimeMillis();
        System.out.println("backward algorithm = " + (endTime - startTime) + " milliseconds");

        startTime = System.currentTimeMillis();
        Tree med = getMedianTree();
        endTime = System.currentTimeMillis();
        System.out.println("median tree (" + med.getLabel() + ") " + " = " + (endTime - startTime) + " milliseconds");
    }

    public static void main(String[] args) {
        List<Tree> demoTrees = new SNTService().demoTrees();
        TreePCA tPCA = new TreePCA(demoTrees);
        tPCA.profile();
        int dimension = tPCA.getTotalDimension();
        System.out.println("total dimension = " + dimension);
        System.out.println("Total nodes: " + tPCA.getTotalNumNodes());
        System.out.println("Support tree size: " + tPCA.getSupportKTree().getNodes().size());
        List<TreeLine> fpcs = tPCA.forwardPrincipalComponents;
        List<TreeLine> bpcs = tPCA.backwardPrincipalComponents;
        boolean allTrue = true;
        for (int i = 0; i < fpcs.size(); i++) {
            if (fpcs.get(i) != bpcs.get(bpcs.size() - i - 1)) {
                allTrue = false;
                break;
            }
        }
        // If allTrue == true, the implementation is likely correct.
        System.out.println("k-th FPC == k-th BPC: " + allTrue);
        System.out.println("FPC1 explained nodes: " + tPCA.getNumExplainedNodes(fpcs.get(0)));
        System.out.println("BPC1 explained nodes: " + tPCA.getNumExplainedNodes(bpcs.get(0)));
        KTree inter = tPCA.getIntersectionKTree();
        KTree supp = tPCA.getSupportKTree();
        inter.draw2D();
        supp.draw2D();
    }

}
