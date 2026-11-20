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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.roanh.gmark.data.SourceTargetPair;
import dev.roanh.gmark.type.schema.Predicate;
import dev.roanh.gmark.util.graph.generic.IntGraph;
import dev.roanh.gmark.util.graph.generic.UniqueGraph;

/**
 * Small hand-built edge labelled multigraph used by the edge case tests. A test graph
 * records its edges once and can then be turned into every graph representation that
 * gMark's evaluators and indexes need. Vertices are always the integers {@code 0 <= v < n}.
 * Parallel edges (same or different labels) and self loops are allowed.
 * @see TestQuery
 * @see TestGraphs
 */
public final class TestGraph{
	/**
	 * Label {@code a}.
	 */
	public static final Predicate A = new Predicate(0, "a");
	/**
	 * Label {@code b}.
	 */
	public static final Predicate B = new Predicate(1, "b");
	/**
	 * Label {@code c}.
	 */
	public static final Predicate C = new Predicate(2, "c");
	/**
	 * Label {@code d}, intentionally unused by most graphs.
	 */
	public static final Predicate D = new Predicate(3, "d");
	/**
	 * All labels that exist in the label space of every test graph.
	 */
	public static final List<Predicate> LABELS = List.of(A, B, C, D);

	/**
	 * Human readable name of this graph (shown in test names and failure messages).
	 */
	private final String name;
	/**
	 * The number of vertices in this graph.
	 */
	private final int vertexCount;
	/**
	 * All edges in insertion order, duplicates are intentionally kept.
	 */
	private final List<Edge> edges = new ArrayList<Edge>();

	/**
	 * Constructs a new test graph without any edges.
	 * @param name The name of the graph.
	 * @param vertexCount The number of vertices in the graph.
	 */
	public TestGraph(String name, int vertexCount){
		this.name = name;
		this.vertexCount = vertexCount;
	}

	/**
	 * Adds one edge for each of the given labels between the given vertices.
	 * @param source The source vertex.
	 * @param target The target vertex.
	 * @param labels The labels of the edges to add.
	 * @return This graph.
	 */
	public TestGraph edge(int source, int target, Predicate... labels){
		if(source < 0 || source >= vertexCount || target < 0 || target >= vertexCount){
			throw new IllegalArgumentException("Vertex out of range in test graph " + name);
		}

		for(Predicate label : labels){
			if(label.isInverse()){
				throw new IllegalArgumentException("Graph edges must use forward labels in test graph " + name);
			}
			edges.add(new Edge(source, target, label));
		}
		return this;
	}

	/**
	 * Adds a directed path using a single label.
	 * @param label The label for all edges.
	 * @param vertices The vertices on the path in order.
	 * @return This graph.
	 */
	public TestGraph path(Predicate label, int... vertices){
		for(int i = 0; i + 1 < vertices.length; i++){
			edge(vertices[i], vertices[i + 1], label);
		}
		return this;
	}

	/**
	 * Adds a directed cycle using a single label. The closing edge from the
	 * last vertex back to the first vertex is added automatically.
	 * @param label The label for all edges.
	 * @param vertices The vertices on the cycle in order.
	 * @return This graph.
	 */
	public TestGraph cycle(Predicate label, int... vertices){
		path(label, vertices);
		return edge(vertices[vertices.length - 1], vertices[0], label);
	}

	/**
	 * Gets the name of this graph.
	 * @return The name of this graph.
	 */
	public String getName(){
		return name;
	}

	/**
	 * Gets the number of vertices in this graph.
	 * @return The number of vertices.
	 */
	public int getVertexCount(){
		return vertexCount;
	}

	/**
	 * Gets all edges in this graph (including duplicates).
	 * @return An unmodifiable view of the edges.
	 */
	public List<Edge> getEdges(){
		return List.copyOf(edges);
	}

	/**
	 * Gets the set of source target pairs matched by traversing a single
	 * edge with the given label (inverse labels are traversed backwards).
	 * @param label The label to traverse.
	 * @return The matching source target pairs.
	 */
	public Set<SourceTargetPair> step(Predicate label){
		Set<SourceTargetPair> pairs = new HashSet<SourceTargetPair>();
		for(Edge edge : edges){
			if(edge.label().getID() == label.getID()){
				pairs.add(label.isInverse()
					? new SourceTargetPair(edge.target(), edge.source())
					: new SourceTargetPair(edge.source(), edge.target()));
			}
		}
		return pairs;
	}

	/**
	 * Constructs the representation of this graph used to build indexes.
	 * @return The unique graph for this test graph.
	 */
	public UniqueGraph<Integer, Predicate> toIndexGraph(){
		UniqueGraph<Integer, Predicate> graph = new UniqueGraph<Integer, Predicate>();
		for(int i = 0; i < vertexCount; i++){
			graph.addUniqueNode(i);
		}

		for(Edge edge : edges){
			graph.addUniqueEdge(edge.source(), edge.target(), edge.label());
		}
		return graph;
	}

	/**
	 * Constructs the representation of this graph used by the reachability evaluator.
	 * @return A fresh integer graph for this test graph.
	 */
	public IntGraph toIntGraph(){
		IntGraph graph = new IntGraph(vertexCount, LABELS.size());
		for(Edge edge : edges){
			graph.addEdge(edge.source(), edge.target(), edge.label().getID());
		}
		return graph;
	}

	/**
	 * Gets the set of labels that are actually used by edges in this graph.
	 * @return The used labels in label ID order.
	 */
	public List<Predicate> getUsedLabels(){
		return LABELS.stream().filter(label->edges.stream().anyMatch(e->e.label().getID() == label.getID())).toList();
	}

	@Override
	public String toString(){
		return name;
	}

	/**
	 * A single labelled edge of a test graph.
	 * @param source The source vertex.
	 * @param target The target vertex.
	 * @param label The (forward) edge label.
	 */
	public static record Edge(int source, int target, Predicate label){
	}
}
