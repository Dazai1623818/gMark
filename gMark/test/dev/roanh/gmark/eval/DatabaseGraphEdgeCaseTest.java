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
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.roanh.gmark.data.SourceTargetPair;

/**
 * Edge case tests for the label selection operations of {@link DatabaseGraph} that bind a
 * source and/or target vertex, in particular for vertices that have no edges at all in the
 * direction that is being looked up (no outgoing edges for a bound source, no incoming edges
 * for a bound target). For such vertices the graph stores no per-label index so the lookup
 * has to return an empty result rather than reading the data of an unrelated vertex.
 * <p>
 * The test graph is a diamond with an extra isolated vertex:
 * <pre>
 * 0 -a-> 1 -b-> 3
 * 0 -a-> 2 -b-> 3
 * 4 (isolated)
 * </pre>
 * Vertex 0 has no incoming edges, vertices 3 and 4 have no outgoing edges and vertex 4 has no
 * incoming edges either.
 * @see DatabaseGraph
 * @see ReachabilityQueryEvaluatorEdgeCaseTest
 */
public class DatabaseGraphEdgeCaseTest{

	/**
	 * Builds the test graph.
	 * @return The database graph.
	 */
	private static DatabaseGraph graph(){
		return new DatabaseGraph(
			new TestGraph("diamond-and-isolated", 5).edge(0, 1, A).edge(0, 2, A).edge(1, 3, B).edge(2, 3, B).toIntGraph()
		);
	}

	/**
	 * Converts a result into a sorted list of pairs.
	 */
	private static List<SourceTargetPair> pairs(ResultGraph result){
		return result.getSourceTargetPairs().stream().sorted().toList();
	}

	/**
	 * Control: bound target lookups for vertices that do have incoming edges work.
	 */
	@Test
	public void boundTargetWithIncomingEdges(){
		DatabaseGraph graph = graph();
		assertEquals(List.of(new SourceTargetPair(0, 1)), pairs(graph.selectLabel(A, 1)));
		assertEquals(List.of(new SourceTargetPair(1, 3), new SourceTargetPair(2, 3)), pairs(graph.selectLabel(B, 3)));
		assertEquals(List.of(new SourceTargetPair(1, 0), new SourceTargetPair(2, 0)), pairs(graph.selectLabel(A.getInverse(), 0)));
		assertEquals(List.of(), pairs(graph.selectLabel(B, 1)));
	}

	/**
	 * Control: bound source lookups for vertices that do have outgoing edges work.
	 */
	@Test
	public void boundSourceWithOutgoingEdges(){
		DatabaseGraph graph = graph();
		assertEquals(List.of(new SourceTargetPair(0, 1), new SourceTargetPair(0, 2)), pairs(graph.selectLabel(0, A)));
		assertEquals(List.of(new SourceTargetPair(3, 1), new SourceTargetPair(3, 2)), pairs(graph.selectLabel(3, B.getInverse())));
		assertEquals(List.of(), pairs(graph.selectLabel(1, A)));
		assertEquals(List.of(new SourceTargetPair(1, 3)), pairs(graph.selectLabel(1, B)));
	}

	/**
	 * A bound target without any incoming edges (vertex 0 and the isolated vertex 4) can never
	 * be the target of a label edge, so the result has to be empty.
	 */
	@Test
	public void boundTargetWithoutIncomingEdges(){
		DatabaseGraph graph = graph();
		assertEquals(List.of(), pairs(graph.selectLabel(A, 0)), "a edges into vertex 0");
		assertEquals(List.of(), pairs(graph.selectLabel(B, 0)), "b edges into vertex 0");
		assertEquals(List.of(), pairs(graph.selectLabel(A, 4)), "a edges into isolated vertex 4");
		assertEquals(List.of(), pairs(graph.selectLabel(B, 4)), "b edges into isolated vertex 4");
	}

	/**
	 * The same as {@link #boundTargetWithoutIncomingEdges()} for inverse labels, which are looked up in the
	 * forward direction of the graph: vertices 3 and 4 have no outgoing edges.
	 */
	@Test
	public void boundTargetWithoutOutgoingEdgesInverseLabel(){
		DatabaseGraph graph = graph();
		assertEquals(List.of(), pairs(graph.selectLabel(A.getInverse(), 3)), "a- edges into vertex 3");
		assertEquals(List.of(), pairs(graph.selectLabel(B.getInverse(), 4)), "b- edges into isolated vertex 4");
	}

	/**
	 * A bound source without any outgoing edges (the sink 3 and the isolated vertex 4)
	 * can never be the source of a label edge, so the result has to be empty.
	 */
	@Test
	public void boundSourceWithoutOutgoingEdges(){
		DatabaseGraph graph = graph();
		assertEquals(List.of(), pairs(graph.selectLabel(3, A)), "a edges out of sink 3");
		assertEquals(List.of(), pairs(graph.selectLabel(3, B)), "b edges out of sink 3");
		assertEquals(List.of(), pairs(graph.selectLabel(4, A)), "a edges out of isolated vertex 4");
		assertEquals(List.of(), pairs(graph.selectLabel(4, B)), "b edges out of isolated vertex 4");
	}

	/**
	 * The same as {@link #boundSourceWithoutOutgoingEdges()} for inverse labels, which are looked up in the
	 * reverse direction of the graph: vertices 0 and 4 have no incoming edges.
	 */
	@Test
	public void boundSourceWithoutIncomingEdgesInverseLabel(){
		DatabaseGraph graph = graph();
		assertEquals(List.of(), pairs(graph.selectLabel(0, A.getInverse())), "a- edges out of vertex 0");
		assertEquals(List.of(), pairs(graph.selectLabel(4, B.getInverse())), "b- edges out of isolated vertex 4");
	}

	/**
	 * A bound source and target where the source has no outgoing edges.
	 */
	@Test
	public void boundSourceAndTargetWithoutEdges(){
		DatabaseGraph graph = graph();
		assertEquals(List.of(), pairs(graph.selectLabel(3, A, 0)), "a edge from sink 3 to 0");
		assertEquals(List.of(), pairs(graph.selectLabel(4, B, 3)), "b edge from isolated vertex 4 to 3");
		assertEquals(List.of(), pairs(graph.selectLabel(0, A.getInverse(), 1)), "a- edge from vertex 0 (no incoming edges) to 1");
		assertEquals(List.of(), pairs(graph.selectLabel(4, A.getInverse(), 4)), "a- edge from isolated vertex 4 to itself");
		//controls
		assertEquals(List.of(new SourceTargetPair(0, 1)), pairs(graph.selectLabel(0, A, 1)));
		assertEquals(List.of(new SourceTargetPair(3, 1)), pairs(graph.selectLabel(3, B.getInverse(), 1)));
	}

	/**
	 * A bound vertex in a graph where only the very last vertex has no edges, so that
	 * the per-vertex data that would be (mis)read lies at the end of the data array.
	 */
	@Test
	public void boundVertexAtEndOfData(){
		DatabaseGraph graph = new DatabaseGraph(new TestGraph("trailing-isolated", 3).edge(0, 1, A).edge(1, 0, A).toIntGraph());
		assertEquals(List.of(), pairs(graph.selectLabel(A, 2)), "a edges into trailing isolated vertex 2");
		assertEquals(List.of(), pairs(graph.selectLabel(2, A)), "a edges out of trailing isolated vertex 2");
		assertEquals(List.of(), pairs(graph.selectLabel(2, A, 0)), "a edge from trailing isolated vertex 2 to 0");
		assertEquals(List.of(), pairs(graph.selectLabel(A.getInverse(), 2)), "a- edges into trailing isolated vertex 2");
		assertEquals(List.of(), pairs(graph.selectLabel(2, A.getInverse())), "a- edges out of trailing isolated vertex 2");
	}
}
