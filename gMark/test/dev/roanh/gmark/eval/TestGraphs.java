/*
 * gMark: A domain- and query language-independent query workload generator and query language utility library.
 * Copyright (C) 2021  Roan Hofland (roan@roanh.dev).  All rights reserved.
 *
 * gMark is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * gMark is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with gMark.  If not, see <http://www.gnu.org/licenses/>.
 */
package dev.roanh.gmark.eval;

import static dev.roanh.gmark.eval.TestGraph.A;
import static dev.roanh.gmark.eval.TestGraph.B;
import static dev.roanh.gmark.eval.TestGraph.C;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

/**
 * Catalog of small hand-built graphs that exercise loops, cycles and other
 * structures that are tricky for CPQ and RPQ evaluation. All graphs have at
 * most eight vertices so that a naive reference evaluator is instantaneous.
 * @see TestGraph
 * @see TestQuery
 */
public final class TestGraphs{

	/**
	 * Prevent instantiation.
	 */
	private TestGraphs(){
	}

	/**
	 * Gets the degenerate graphs without any edges.
	 * @return The graphs without edges.
	 */
	public static List<TestGraph> edgeless(){
		return List.of(emptyGraph(), singleVertex());
	}

	/**
	 * Gets every graph in the catalog that has at least one edge.
	 * @return All catalog graphs with edges.
	 */
	public static List<TestGraph> all(){
		return List.of(
			selfLoop(),
			selfLoopsAndBridges(),
			twoCycleSameLabel(),
			twoCycleMixedLabels(),
			triangle(),
			longCycle(),
			longCycleWithChord(),
			figureEight(),
			diamondSymmetric(),
			diamondAsymmetric(),
			parallelEdges(),
			disconnectedComponents(),
			completeWithLoops(),
			hub(),
			ladder(),
			bidirectionalChain(),
			randomDense()
		);
	}

	/**
	 * Three vertices and no edges at all.
	 * @return The graph.
	 */
	public static TestGraph emptyGraph(){
		return new TestGraph("empty", 3);
	}

	/**
	 * A single isolated vertex.
	 * @return The graph.
	 */
	public static TestGraph singleVertex(){
		return new TestGraph("single-vertex", 1);
	}

	/**
	 * A single {@code a} self loop on vertex 0 and an isolated vertex 1.
	 * @return The graph.
	 */
	public static TestGraph selfLoop(){
		return new TestGraph("self-loop", 2).edge(0, 0, A);
	}

	/**
	 * Self loops with different labels (including a vertex with two self loops)
	 * that are connected by a chain of ordinary edges that closes into a cycle.
	 * <pre>
	 * 0 -a-> 0, 1 -b-> 1, 2 -a,b-> 2
	 * 0 -a-> 1 -b-> 2 -c-> 0
	 * </pre>
	 * @return The graph.
	 */
	public static TestGraph selfLoopsAndBridges(){
		return new TestGraph("self-loops-and-bridges", 3)
			.edge(0, 0, A)
			.edge(1, 1, B)
			.edge(2, 2, A, B)
			.edge(0, 1, A)
			.edge(1, 2, B)
			.edge(2, 0, C);
	}

	/**
	 * Two vertices with {@code a} edges in both directions and an isolated third vertex.
	 * @return The graph.
	 */
	public static TestGraph twoCycleSameLabel(){
		return new TestGraph("two-cycle-same-label", 3).edge(0, 1, A).edge(1, 0, A);
	}

	/**
	 * Two vertices with an {@code a} edge one way and a {@code b} edge back.
	 * @return The graph.
	 */
	public static TestGraph twoCycleMixedLabels(){
		return new TestGraph("two-cycle-mixed-labels", 2).edge(0, 1, A).edge(1, 0, B);
	}

	/**
	 * A directed {@code a} triangle {@code 0 -> 1 -> 2 -> 0} with a {@code b}
	 * chord {@code 0 -> 2} that runs against the direction of the cycle.
	 * @return The graph.
	 */
	public static TestGraph triangle(){
		return new TestGraph("triangle", 3).cycle(A, 0, 1, 2).edge(0, 2, B);
	}

	/**
	 * A directed {@code a} cycle of length seven, longer than any indexed path length used in tests.
	 * @return The graph.
	 */
	public static TestGraph longCycle(){
		return new TestGraph("long-cycle-7", 7).cycle(A, 0, 1, 2, 3, 4, 5, 6);
	}

	/**
	 * A directed {@code a} cycle of length seven with a {@code b} chord that
	 * creates an additional cycle of length four.
	 * @return The graph.
	 */
	public static TestGraph longCycleWithChord(){
		return new TestGraph("long-cycle-7-chord", 7).cycle(A, 0, 1, 2, 3, 4, 5, 6).edge(3, 0, B);
	}

	/**
	 * Two cycles of length three that share vertex 0 ({@code a} cycle and {@code b} cycle).
	 * @return The graph.
	 */
	public static TestGraph figureEight(){
		return new TestGraph("figure-eight", 5).cycle(A, 0, 1, 2).cycle(B, 0, 3, 4);
	}

	/**
	 * A diamond where both branches carry the same labels.
	 * <pre>
	 * 0 -a-> 1 -b-> 3
	 * 0 -a-> 2 -b-> 3
	 * </pre>
	 * @return The graph.
	 */
	public static TestGraph diamondSymmetric(){
		return new TestGraph("diamond-symmetric", 4).edge(0, 1, A).edge(0, 2, A).edge(1, 3, B).edge(2, 3, B);
	}

	/**
	 * A diamond where the branches carry different labels and a back edge
	 * closes it into a cycle.
	 * <pre>
	 * 0 -a-> 1 -c-> 3
	 * 0 -b-> 2 -a-> 3
	 * 3 -a-> 0
	 * </pre>
	 * @return The graph.
	 */
	public static TestGraph diamondAsymmetric(){
		return new TestGraph("diamond-asymmetric", 4)
			.edge(0, 1, A)
			.edge(1, 3, C)
			.edge(0, 2, B)
			.edge(2, 3, A)
			.edge(3, 0, A);
	}

	/**
	 * Parallel edges with different labels (and an exact duplicate), a self
	 * loop in the middle of them and an isolated vertex.
	 * <pre>
	 * 0 -a,b,c-> 1 (a twice)
	 * 1 -a-> 0
	 * 1 -b-> 1
	 * </pre>
	 * @return The graph.
	 */
	public static TestGraph parallelEdges(){
		return new TestGraph("parallel-edges", 3)
			.edge(0, 1, A, B, C)
			.edge(0, 1, A)
			.edge(1, 0, A)
			.edge(1, 1, B);
	}

	/**
	 * Several components: a triangle, a two cycle with another label, a
	 * path and an isolated vertex.
	 * @return The graph.
	 */
	public static TestGraph disconnectedComponents(){
		return new TestGraph("disconnected-components", 8)
			.cycle(A, 0, 1, 2)
			.cycle(B, 3, 4)
			.edge(5, 6, C);
	}

	/**
	 * Three vertices with an {@code a} edge for every ordered pair, including all self loops.
	 * @return The graph.
	 */
	public static TestGraph completeWithLoops(){
		TestGraph graph = new TestGraph("complete-with-loops", 3);
		for(int s = 0; s < 3; s++){
			for(int t = 0; t < 3; t++){
				graph.edge(s, t, A);
			}
		}
		return graph;
	}

	/**
	 * A hub with five {@code a} leaves and one {@code b} edge back to the hub.
	 * @return The graph.
	 */
	public static TestGraph hub(){
		TestGraph graph = new TestGraph("hub", 6);
		for(int leaf = 1; leaf < 6; leaf++){
			graph.edge(0, leaf, A);
		}
		return graph.edge(3, 0, B);
	}

	/**
	 * A ladder with two {@code a} rails and {@code b} rungs, which is full of 4-cycles when
	 * traversing edges in both directions.
	 * <pre>
	 * 0 -a-> 1 -a-> 2
	 * |b     |b     |b
	 * 3 -a-> 4 -a-> 5
	 * </pre>
	 * @return The graph.
	 */
	public static TestGraph ladder(){
		return new TestGraph("ladder", 6)
			.path(A, 0, 1, 2)
			.path(A, 3, 4, 5)
			.edge(0, 3, B)
			.edge(1, 4, B)
			.edge(2, 5, B);
	}

	/**
	 * A chain of {@code a} edges that exists in both directions.
	 * @return The graph.
	 */
	public static TestGraph bidirectionalChain(){
		return new TestGraph("bidirectional-chain", 4).path(A, 0, 1, 2, 3).path(A, 3, 2, 1, 0);
	}

	/**
	 * A dense deterministic pseudo random graph with three labels, self loops and parallel edges.
	 * @return The graph.
	 */
	public static TestGraph randomDense(){
		Random random = new Random(0x5EEDL);
		TestGraph graph = new TestGraph("random-dense", 6);
		for(int i = 0; i < 24; i++){
			graph.edge(random.nextInt(6), random.nextInt(6), List.of(A, B, C).get(random.nextInt(3)));
		}
		return graph;
	}

	/**
	 * Reads a graph in the {@code .edge} text format used for gMark workloads and the CPQx
	 * paper datasets: a header line with the vertex, edge and label count followed by one
	 * {@code source target label} line per edge. At most four labels are supported.
	 * @param name The name for the graph.
	 * @param resource The name of the class path resource to read.
	 * @return The graph.
	 * @throws IOException When the resource cannot be read.
	 */
	public static TestGraph readEdgeList(String name, String resource) throws IOException{
		try(InputStream in = ClassLoader.getSystemResourceAsStream(resource)){
			if(in == null){
				throw new IOException("Resource not found: " + resource);
			}

			BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String[] header = reader.readLine().trim().split("\\s+");
			if(Integer.parseInt(header[2]) > TestGraph.LABELS.size()){
				throw new IOException("Too many labels in " + resource);
			}

			TestGraph graph = new TestGraph(name, Integer.parseInt(header[0]));
			String line;
			while((line = reader.readLine()) != null){
				if(!line.isBlank()){
					String[] parts = line.trim().split("\\s+");
					graph.edge(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), TestGraph.LABELS.get(Integer.parseInt(parts[2])));
				}
			}
			return graph;
		}
	}
}
