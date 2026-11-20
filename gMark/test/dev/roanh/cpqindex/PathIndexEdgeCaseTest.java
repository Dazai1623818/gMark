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
package dev.roanh.cpqindex;

import static dev.roanh.gmark.eval.TestGraph.A;
import static dev.roanh.gmark.eval.TestGraph.B;
import static dev.roanh.gmark.eval.TestGraph.C;
import static dev.roanh.gmark.eval.TestGraph.D;
import static dev.roanh.gmark.eval.TestQuery.ID;
import static dev.roanh.gmark.eval.TestQuery.concat;
import static dev.roanh.gmark.eval.TestQuery.edge;
import static dev.roanh.gmark.eval.TestQuery.inter;
import static dev.roanh.gmark.eval.TestQuery.path;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import dev.roanh.gmark.data.SourceTargetPair;
import dev.roanh.gmark.eval.PathQuery;
import dev.roanh.gmark.eval.ReachabilityQueryEvaluator;
import dev.roanh.gmark.eval.TestGraph;
import dev.roanh.gmark.eval.TestGraphs;
import dev.roanh.gmark.eval.TestQueries;
import dev.roanh.gmark.eval.TestQuery;
import dev.roanh.gmark.lang.cpq.CPQ;
import dev.roanh.gmark.type.schema.Predicate;

/**
 * Edge case tests for CPQ evaluation using a coreless {@link PathIndex}. All tests use small
 * hand-built graphs from {@link TestGraphs} (self loops, cycles, diamonds, parallel edges,
 * disconnected components, ...) and compare the path index against two independent oracles:
 * a naive brute-force reference evaluator ({@link TestQuery#evaluate(TestGraph)}) that does not
 * share any code with gMark, and gMark's plain {@link ReachabilityQueryEvaluator}. Every graph
 * is tested with several values of {@code k}, including values both much smaller and larger than
 * the diameter of the queries and the graph, because queries with a diameter above {@code k} take
 * a different (join based) evaluation path than queries that are answered from a single block.
 * @see PathIndexTest
 * @see PathIndex
 */
public class PathIndexEdgeCaseTest{
	/**
	 * The values of k that are tested.
	 */
	private static final int[] KS = {1, 2, 3, 4, 6};
	/**
	 * The labels used for exhaustive enumeration (label a, label b and their inverses).
	 */
	private static final List<Predicate> ENUM_LABELS = TestQuery.withInverses(A, B);

	//--------------------------------------------------------------------------------------
	// Differential tests against the brute force reference and the reachability evaluator
	//--------------------------------------------------------------------------------------

	/**
	 * Checks every CPQ that can be built from {@code a}, {@code b} and their inverses with
	 * up to two binary operations (884 queries, including all identity combinations and
	 * every nesting direction of concatenation and intersection) on every catalog graph.
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void exhaustiveSmallQueries(TestGraph graph, int k) throws InterruptedException{
		checkAgainstOracles(graph, k, TestQueries.enumerate(ENUM_LABELS, 2));
	}

	/**
	 * Checks hand picked tricky queries (loops, closed cycles, diamonds, repeated operands,
	 * multiway intersections, nested identity filters, absent labels) on every catalog graph.
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void trickyQueries(TestGraph graph, int k) throws InterruptedException{
		checkAgainstOracles(graph, k, TestQueries.tricky());
	}

	/**
	 * Checks deterministic random CPQs of depth up to four over all labels (and inverses)
	 * on every catalog graph, this reaches query shapes that the enumeration cannot.
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void randomDeepQueries(TestGraph graph, int k) throws InterruptedException{
		Random random = new Random(0xBADC0DEL + k);
		List<Predicate> labels = TestQuery.withInverses(A, B, C, D);
		List<TestQuery> queries = new ArrayList<TestQuery>();
		for(int i = 0; i < 200; i++){
			queries.add(TestQueries.random(random, 4, labels));
		}
		checkAgainstOracles(graph, k, queries);
	}

	/**
	 * Checks every label sequence over {@code a}, {@code b} and their inverses up to
	 * two labels beyond the indexed path length (capped at length five) so that queries that
	 * fit in a single block and queries that need several joined blocks are both covered.
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void labelSequencesBeyondK(TestGraph graph, int k) throws InterruptedException{
		checkAgainstOracles(graph, k, TestQueries.labelSequences(ENUM_LABELS, Math.min(k + 2, 5)));
	}

	/**
	 * Checks CPQs that are evaluated by composing a query with the inverse of CPQ operations:
	 * the inverse of a query has to match exactly the transposed relation of the query.
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void inverseQueries(TestGraph graph, int k) throws InterruptedException{
		PathIndex index = new PathIndex(graph.toIndexGraph(), k);
		ReachabilityQueryEvaluator direct = new ReachabilityQueryEvaluator(graph.toIntGraph());

		List<TestQuery> queries = new ArrayList<TestQuery>(TestQueries.tricky());
		queries.addAll(TestQueries.enumerate(ENUM_LABELS, 1));
		TestQueries.checkAll(queries, query->{
			List<SourceTargetPair> expected = TestQuery.sorted(TestQuery.transpose(query.evaluate(graph)));
			CPQ inverse = query.toCPQ().inverse();
			assertEquals(expected, toPairs(index.query(inverse)), "path index");
			assertEquals(expected, direct.evaluate(PathQuery.of(inverse)).getSourceTargetPairs().stream().sorted().toList(), "reachability evaluator");
		}, graph + " k=" + k + " inverse of ");
	}

	/**
	 * Checks that restricting the result of the path index to a bound source and/or target
	 * gives the same result as evaluating the query with that source and/or target bound on
	 * the brute force reference, for every combination of bound and unbound vertices. The
	 * path index API has no notion of bound vertices, so this is how the path index results
	 * are consumed when it is used to answer bound queries. Bound evaluation by the
	 * reachability evaluator itself is covered by {@code ReachabilityQueryEvaluatorEdgeCaseTest}.
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void boundSourceAndTarget(TestGraph graph, int k) throws InterruptedException{
		PathIndex index = new PathIndex(graph.toIndexGraph(), k);

		List<TestQuery> queries = new ArrayList<TestQuery>(TestQueries.tricky());
		queries.addAll(TestQueries.enumerate(ENUM_LABELS, 1));
		TestQueries.checkAll(queries, query->{
			var all = query.evaluate(graph);
			List<SourceTargetPair> indexed = toPairs(index.query(query.toCPQ()));
			for(int source = -1; source < graph.getVertexCount(); source++){
				for(int target = -1; target < graph.getVertexCount(); target++){
					final int s = source;
					final int t = target;
					assertEquals(
						TestQuery.sorted(TestQuery.restrict(all, s, t)),
						indexed.stream().filter(p->(s == -1 || p.source() == s) && (t == -1 || p.target() == t)).toList(),
						"restricted path index (" + s + ", " + t + ")"
					);
				}
			}
		}, graph + " k=" + k + " bound ");
	}

	/**
	 * Checks every instantiation (all assignments of {@code a}, {@code b} and {@code a-}) of the CPQ
	 * query templates that were used to evaluate CPQx in the original paper (chains, triangles, squares,
	 * stars and flowers, each also in the loop form that ends in identity) on every catalog graph.
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void cpqxPaperTemplates(TestGraph graph, int k) throws InterruptedException{
		checkAgainstOracles(graph, k, TestQueries.cpqxExhaustiveQueries(List.of(A, B, A.getInverse())));
	}

	/**
	 * Checks randomly labelled instances of all CPQ templates from the CPQx paper on every catalog graph,
	 * including the {@code ST} (flower) template that has seven label slots and is therefore not
	 * part of the exhaustive instantiation.
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void cpqxPaperFlowerTemplate(TestGraph graph, int k) throws InterruptedException{
		Random random = new Random(0xF10EL + k);
		List<TestQuery> queries = TestQueries.cpqxSampledQueries(random, TestQuery.withInverses(A, B, C), 40);
		checkAgainstOracles(graph, k, queries);
	}

	/**
	 * The robots graph (1484 vertices, 2960 edges, 4 labels) that is the smallest real dataset in the CPQx
	 * paper, queried with randomly labelled instances of the paper's twelve CPQ templates. It is a
	 * sparse graph full of small cycles and its path index is already used by the other index tests.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("robotsKs")
	public void robotsDataset(int k) throws InterruptedException, IOException{
		TestGraph graph = TestGraphs.readEdgeList("robots", "robots.edge");
		assertEquals(1484, graph.getVertexCount());
		assertEquals(2960, graph.getEdges().size());

		PathIndex index = new PathIndex(graph.toIndexGraph(), k);
		ReachabilityQueryEvaluator direct = new ReachabilityQueryEvaluator(graph.toIntGraph());
		Random random = new Random(0xB0B0L + k);
		List<TestQuery> queries = TestQueries.cpqxSampledQueries(random, TestQuery.withInverses(A, B, C, D), 12);
		TestQueries.checkAll(queries, query->checkQuery(index, direct, graph, k, query), "robots k=" + k + " ");
	}

	/**
	 * The Advogato graph (5,417 vertices, 51,327 edges, 4 labels; bundled as the workload graph
	 * {@code real/2}) with {@code k = 1}, so that every query that is not a single edge has to be answered
	 * by joining relations. Randomly labelled instances of the CPQx paper templates (up to diameter three,
	 * to keep intermediate results small) are compared with the reachability evaluator. The reference
	 * evaluator is intentionally not used here as it is far too slow at this size. Larger values of k
	 * are not tested as an index for k = 2 needs several gigabytes of memory.
	 */
	@Test
	public void advogatoDataset() throws InterruptedException, IOException{
		TestGraph graph = TestGraphs.readEdgeList("advogato", "workload/real/2/graph.edge");
		assertEquals(5417, graph.getVertexCount());
		assertEquals(51327, graph.getEdges().size());

		PathIndex index = new PathIndex(graph.toIndexGraph(), 1);
		ReachabilityQueryEvaluator direct = new ReachabilityQueryEvaluator(graph.toIntGraph());
		Random random = new Random(0xAD06A70L);
		List<TestQuery> queries = TestQueries.cpqxSampledQueries(random, TestQuery.withInverses(A, B, C, D), 8).stream().filter(query->query.diameter() <= 3).toList();
		assertTrue(queries.size() > 40, "expected a reasonable number of queries, got " + queries.size());

		TestQueries.checkAll(queries, query->{
			CPQ cpq = query.toCPQ();
			List<SourceTargetPair> expected = direct.evaluate(PathQuery.of(cpq)).getSourceTargetPairs().stream().sorted().toList();
			assertEquals(expected, toPairs(index.query(cpq)), "path index result");
			assertEquals(expected.size(), index.computeResultCardinality(cpq), "path index cardinality");
		}, "advogato k=1 ");
	}

	/**
	 * Checks that a fully saved and reloaded path index answers queries identically to the
	 * original. Label sequences with inverse labels, self loops and absent labels all have
	 * to survive serialisation.
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void writeReadRoundTrip(TestGraph graph, int k) throws InterruptedException, IOException{
		PathIndex original = new PathIndex(graph.toIndexGraph(), k);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		original.write(out);
		PathIndex read = new PathIndex(new ByteArrayInputStream(out.toByteArray()));
		assertEquals(k, read.getK());

		List<TestQuery> queries = new ArrayList<TestQuery>(TestQueries.tricky());
		queries.addAll(TestQueries.enumerate(ENUM_LABELS, 1));
		queries.addAll(TestQueries.labelSequences(ENUM_LABELS, Math.min(k + 1, 4)));
		TestQueries.checkAll(queries, query->{
			CPQ cpq = query.toCPQ();
			List<SourceTargetPair> expected = query.evaluateSorted(graph);
			assertEquals(expected, toPairs(read.query(cpq)), "reloaded index");
			assertEquals(toPairs(original.query(cpq)), toPairs(read.query(cpq)), "reloaded index vs original index");
			assertEquals(expected.size(), read.computeResultCardinality(cpq), "reloaded index cardinality");
		}, graph + " k=" + k + " write/read ");
	}

	/**
	 * Valid CPQs that combine identity with itself inside a larger query (for example
	 * {@code (a ∘ (id ∩ id)) ∩ id}). The generator in gMark never produces these but they
	 * are valid CPQs with a diameter above zero and the result is well defined: {@code id ∩ id}
	 * is simply identity.
	 */
	@Test
	public void identityCombinedWithIdentity() throws InterruptedException{
		List<String> failures = new ArrayList<String>();
		for(TestGraph graph : List.of(TestGraphs.selfLoopsAndBridges(), TestGraphs.triangle(), TestGraphs.randomDense())){
			for(int k : new int[]{1, 2, 3}){
				PathIndex index = new PathIndex(graph.toIndexGraph(), k);
				ReachabilityQueryEvaluator direct = new ReachabilityQueryEvaluator(graph.toIntGraph());
				for(TestQuery query : TestQueries.identityWithIdentity()){
					try{
						checkQuery(index, direct, graph, k, query);
					}catch(AssertionError | RuntimeException e){
						failures.add(graph + " k=" + k + " " + query + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
					}
				}
			}
		}

		assertTrue(failures.isEmpty(), failures.size() + " failures, distinct queries:\n" + failures.stream().map(f->f.replaceFirst("^\\S+ k=\\d+ ", "")).distinct().limit(8).collect(Collectors.joining("\n")));
	}

	//--------------------------------------------------------------------------------------
	// Pinned, hand verified expectations
	//--------------------------------------------------------------------------------------

	/**
	 * Verifies the reference evaluator itself on a tiny graph with hand computed results, so
	 * that agreement with the reference means something.
	 */
	@Test
	public void referenceEvaluatorSelfCheck(){
		//0 -a-> 1 -a-> 2 -b-> 2, 2 -a-> 0
		TestGraph graph = new TestGraph("reference", 4).edge(0, 1, A).edge(1, 2, A).edge(2, 2, B).edge(2, 0, A);

		assertEquals(pairs(0, 1, 1, 2, 2, 0), edge(A).evaluateSorted(graph));
		assertEquals(pairs(1, 0, 2, 1, 0, 2), edge(A.getInverse()).evaluateSorted(graph));
		assertEquals(pairs(0, 2, 1, 0, 2, 1), path(A, A).evaluateSorted(graph));
		assertEquals(pairs(0, 0, 1, 1, 2, 2), inter(path(A, A, A), ID).evaluateSorted(graph));
		assertEquals(pairs(2, 2), inter(edge(B), ID).evaluateSorted(graph));
		assertEquals(pairs(0, 0, 1, 1, 2, 2, 3, 3), ID.evaluateSorted(graph));
		assertEquals(pairs(), inter(edge(A), edge(B)).evaluateSorted(graph));
		assertEquals(pairs(2, 0), concat(edge(B), edge(A)).evaluateSorted(graph));
		assertEquals(pairs(0, 0, 0, 1, 0, 2, 1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2), TestQuery.plus(edge(A)).evaluateSorted(graph));
		assertEquals(pairs(), edge(D).evaluateSorted(graph));
	}

	/**
	 * A graph consisting of a single self loop and an isolated vertex.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void selfLoop(int k) throws InterruptedException{
		PathIndex index = new PathIndex(TestGraphs.selfLoop().toIndexGraph(), k);

		assertPairs(index, CPQ.label(A.getInverse()), 0, 0);
		assertPairs(index, CPQ.labels(A, A), 0, 0);
		assertPairs(index, CPQ.labels(A, A, A, A, A), 0, 0);
		assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.id()), 0, 0);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A), CPQ.id()), 0, 0);
		assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.label(A.getInverse())), 0, 0);
		assertPairs(index, CPQ.concat(CPQ.id(), CPQ.label(A)), 0, 0);
		assertPairs(index, CPQ.label(B));
		assertPairs(index, CPQ.labels(A, B));
		assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.label(B)));
		assertPairs(index, CPQ.intersect(CPQ.labels(B, A), CPQ.id()));
	}

	/**
	 * Self loops with several labels on the same vertex combined with a cycle through all vertices.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void selfLoopsWithDifferentLabels(int k) throws InterruptedException{
		//0 -a-> 0, 1 -b-> 1, 2 -a,b-> 2, 0 -a-> 1 -b-> 2 -c-> 0
		PathIndex index = new PathIndex(TestGraphs.selfLoopsAndBridges().toIndexGraph(), k);

		assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.id()), 0, 0, 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.label(B), CPQ.id()), 1, 1, 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.id(), CPQ.label(A), CPQ.label(B)), 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.label(B)), 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, B), CPQ.label(A)), 0, 1, 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, B, C), CPQ.id()), 0, 0);
		assertPairs(index, CPQ.intersect(CPQ.labels(C, A, B), CPQ.id()), 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(B, C, A), CPQ.id()), 1, 1);
	}

	/**
	 * A triangle is a cycle of length three: it can be found as loop of length three or
	 * by closing a path of length two with an inverse edge.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void triangleCycles(int k) throws InterruptedException{
		//0 -a-> 1 -a-> 2 -a-> 0 and 0 -b-> 2
		PathIndex index = new PathIndex(TestGraphs.triangle().toIndexGraph(), k);

		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A), CPQ.id()), 0, 0, 1, 1, 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A), CPQ.id()));
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A), CPQ.label(A.getInverse())), 0, 2, 1, 0, 2, 1);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A), CPQ.label(B)), 0, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A), CPQ.label(B), CPQ.label(A.getInverse())), 0, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, B.getInverse(), A.getInverse()), CPQ.id()));
		assertPairs(index, CPQ.intersect(CPQ.labels(B, A), CPQ.id()), 0, 0);
		assertPairs(index, CPQ.intersect(CPQ.labels(B, A, A), CPQ.id()));
	}

	/**
	 * Loops that are longer than the indexed path length have to be assembled by joining blocks.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void longCycleBeyondK(int k) throws InterruptedException{
		PathIndex index = new PathIndex(TestGraphs.longCycle().toIndexGraph(), k);

		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A, A), CPQ.id()), 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A, A, A, A, A, A, A, A, A), CPQ.id()), 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A), CPQ.id()));
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A, A, A), CPQ.id()));
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A), CPQ.id()));
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A, A), CPQ.label(A)));
		//walking six steps forward is the same as one step backwards on a cycle of length seven
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A), CPQ.label(A.getInverse())), 0, 6, 1, 0, 2, 1, 3, 2, 4, 3, 5, 4, 6, 5);
	}

	/**
	 * A 7-cycle with a chord: besides the long cycle a short cycle of length four appears.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void cycleWithChord(int k) throws InterruptedException{
		//a cycle 0 -> 1 -> 2 -> 3 -> 4 -> 5 -> 6 -> 0 plus 3 -b-> 0
		PathIndex index = new PathIndex(TestGraphs.longCycleWithChord().toIndexGraph(), k);

		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, B), CPQ.id()), 0, 0);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A), CPQ.label(B.getInverse())), 0, 3);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A, A), CPQ.id()), 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, B, A, A, A, B), CPQ.id()), 0, 0);
	}

	/**
	 * Two cycles sharing a vertex: only the shared vertex lies on loops with mixed labels.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void figureEightCycles(int k) throws InterruptedException{
		//0 -a-> 1 -a-> 2 -a-> 0 and 0 -b-> 3 -b-> 4 -b-> 0
		PathIndex index = new PathIndex(TestGraphs.figureEight().toIndexGraph(), k);

		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A), CPQ.id()), 0, 0, 1, 1, 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(B, B, B), CPQ.id()), 0, 0, 3, 3, 4, 4);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, B, B, B), CPQ.id()), 0, 0);
		assertPairs(index, CPQ.intersect(CPQ.labels(B, B, B, A, A, A), CPQ.id()), 0, 0);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A), CPQ.id()), 0, 0, 1, 1, 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, B.getInverse()), CPQ.id()));
		assertPairs(index, CPQ.intersect(CPQ.labels(A.getInverse(), B), CPQ.id()));
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A.getInverse()), CPQ.id()), 0, 0, 1, 1, 2, 2);
	}

	/**
	 * Two different paths between the same vertices (the classical CPQ diamond).
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void diamonds(int k) throws InterruptedException{
		PathIndex symmetric = new PathIndex(TestGraphs.diamondSymmetric().toIndexGraph(), k);
		//0 -a-> 1 -b-> 3 and 0 -a-> 2 -b-> 3
		assertPairs(symmetric, CPQ.labels(A, B), 0, 3);
		assertPairs(symmetric, CPQ.intersect(CPQ.labels(A, B), CPQ.labels(A, B)), 0, 3);
		assertPairs(symmetric, CPQ.intersect(CPQ.labels(A, B), CPQ.labels(A, B, B.getInverse(), B)), 0, 3);
		assertPairs(symmetric, CPQ.intersect(CPQ.labels(A, B, B.getInverse(), A.getInverse()), CPQ.id()), 0, 0);
		assertPairs(symmetric, CPQ.intersect(CPQ.labels(A.getInverse(), A), CPQ.id()), 1, 1, 2, 2);
		assertPairs(symmetric, CPQ.labels(A.getInverse(), A), 1, 1, 1, 2, 2, 1, 2, 2);
		assertPairs(symmetric, CPQ.labels(B.getInverse(), B), 3, 3);
		assertPairs(symmetric, CPQ.labels(B, B.getInverse()), 1, 1, 1, 2, 2, 1, 2, 2);

		//0 -a-> 1 -c-> 3, 0 -b-> 2 -a-> 3 and 3 -a-> 0
		PathIndex asymmetric = new PathIndex(TestGraphs.diamondAsymmetric().toIndexGraph(), k);
		assertPairs(asymmetric, CPQ.intersect(CPQ.labels(A, C), CPQ.labels(B, A)), 0, 3);
		assertPairs(asymmetric, CPQ.intersect(CPQ.labels(A, C), CPQ.labels(B, B)));
		assertPairs(asymmetric, CPQ.intersect(CPQ.labels(A, C, A), CPQ.id()), 0, 0);
		assertPairs(asymmetric, CPQ.intersect(CPQ.labels(B, A, A), CPQ.id()), 0, 0);
		assertPairs(asymmetric, CPQ.intersect(CPQ.labels(A, C, A), CPQ.labels(B, A, A)), 0, 0);
		assertPairs(asymmetric, CPQ.intersect(CPQ.labels(A, C), CPQ.labels(B, A), CPQ.label(A.getInverse())), 0, 3);
	}

	/**
	 * Parallel edges with different labels, and an exact duplicate edge that must
	 * not produce duplicate results.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void parallelEdgesWithDifferentLabels(int k) throws InterruptedException{
		//0 -a,b,c-> 1 (a added twice), 1 -a-> 0, 1 -b-> 1, vertex 2 isolated
		PathIndex index = new PathIndex(TestGraphs.parallelEdges().toIndexGraph(), k);

		assertPairs(index, CPQ.label(A), 0, 1, 1, 0);
		assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.label(B)), 0, 1);
		assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.label(B), CPQ.label(C)), 0, 1);
		assertPairs(index, CPQ.intersect(CPQ.label(B), CPQ.label(C)), 0, 1);
		assertPairs(index, CPQ.intersect(CPQ.label(B), CPQ.id()), 1, 1);
		assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.id()));
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A), CPQ.id()), 0, 0, 1, 1);
		assertPairs(index, CPQ.intersect(CPQ.label(B), CPQ.label(A.getInverse())), 0, 1);
		assertPairs(index, CPQ.intersect(CPQ.label(C.getInverse()), CPQ.label(A.getInverse()), CPQ.label(B.getInverse())), 1, 0);
		assertEquals(2, index.computeResultCardinality(CPQ.label(A)));
		assertEquals(1, index.computeResultCardinality(CPQ.intersect(CPQ.label(A), CPQ.label(B), CPQ.label(C))));
	}

	/**
	 * Components never leak into each other and loops are found in the right component only.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void disconnectedComponents(int k) throws InterruptedException{
		//a triangle 0 1 2, a b two cycle 3 4, a c edge 5 -> 6 and isolated vertex 7
		PathIndex index = new PathIndex(TestGraphs.disconnectedComponents().toIndexGraph(), k);

		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A), CPQ.id()), 0, 0, 1, 1, 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(B, B), CPQ.id()), 3, 3, 4, 4);
		assertPairs(index, CPQ.labels(B, A));
		assertPairs(index, CPQ.labels(C, B));
		assertPairs(index, CPQ.labels(C.getInverse(), C), 6, 6);
		assertPairs(index, CPQ.labels(C, C.getInverse()), 5, 5);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A), CPQ.id()), 0, 0, 1, 1, 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(B, B, B), CPQ.id()));
		assertPairs(index, CPQ.concat(CPQ.label(C), CPQ.label(A)));
	}

	/**
	 * A complete graph with all self loops where every pair of vertices has exactly the same
	 * label sequences, so the whole graph collapses into very few blocks.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void completeGraphWithLoops(int k) throws InterruptedException{
		PathIndex index = new PathIndex(TestGraphs.completeWithLoops().toIndexGraph(), k);

		assertEquals(9, index.query(CPQ.label(A)).size());
		assertEquals(9, index.query(CPQ.labels(A, A, A, A, A)).size());
		assertEquals(9, index.query(CPQ.labels(A.getInverse(), A)).size());
		assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.id()), 0, 0, 1, 1, 2, 2);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A, A, A, A), CPQ.id()), 0, 0, 1, 1, 2, 2);
		assertEquals(9, index.query(CPQ.intersect(CPQ.labels(A, A, A), CPQ.label(A))).size());
		assertEquals(0, index.query(CPQ.label(B)).size());
	}

	/**
	 * A hub with many leaves: the composition of two edges produces a result that is
	 * quadratic in the number of leaves with all the pairs in one block.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("smallKs")
	public void hubWithManyLeaves(int k) throws InterruptedException{
		int leaves = 100;
		TestGraph graph = new TestGraph("hub-100", leaves + 1);
		for(int leaf = 1; leaf <= leaves; leaf++){
			graph.edge(0, leaf, A);
		}
		graph.edge(7, 0, B);

		PathIndex index = new PathIndex(graph.toIndexGraph(), k);
		ReachabilityQueryEvaluator direct = new ReachabilityQueryEvaluator(graph.toIntGraph());
		List<TestQuery> queries = List.of(
			path(A.getInverse(), A),
			path(A.getInverse(), A, A.getInverse(), A),
			inter(path(A.getInverse(), A), ID),
			inter(path(A.getInverse(), A), path(A.getInverse(), A)),
			inter(path(A.getInverse(), A), path(B, A)),
			path(B, A, A.getInverse()),
			inter(path(B, A, A.getInverse(), B.getInverse()), ID)
		);

		TestQueries.checkAll(queries, query->checkQuery(index, direct, graph, k, query), graph + " k=" + k + " ");
		assertEquals((long)leaves * leaves, index.computeResultCardinality(CPQ.labels(A.getInverse(), A)));
		assertEquals(leaves, index.computeResultCardinality(CPQ.intersect(CPQ.labels(A.getInverse(), A), CPQ.id())));
	}

	/**
	 * Vertex identifiers above 16 bits and a sparse graph with many isolated vertices.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void largeVertexIdentifiers(int k) throws InterruptedException{
		int vertices = 70_000;
		TestGraph graph = new TestGraph("sparse-70000", vertices)
			.edge(vertices - 1, 0, A)
			.edge(0, 65_536, A)
			.edge(65_536, 65_536, B)
			.edge(65_536, vertices - 1, A)
			.edge(65_535, 65_536, C);

		PathIndex index = new PathIndex(graph.toIndexGraph(), k);
		ReachabilityQueryEvaluator direct = new ReachabilityQueryEvaluator(graph.toIntGraph());
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A), CPQ.id()), 0, 0, 65_536, 65_536, vertices - 1, vertices - 1);
		assertPairs(index, CPQ.intersect(CPQ.labels(A, A, A, A), CPQ.id()));
		assertPairs(index, CPQ.intersect(CPQ.label(B), CPQ.id()), 65_536, 65_536);
		assertPairs(index, CPQ.labels(A, A), 0, vertices - 1, 65_536, 0, vertices - 1, 65_536);
		assertPairs(index, CPQ.intersect(CPQ.label(C), CPQ.label(B.getInverse())));
		assertPairs(index, CPQ.concat(CPQ.label(C), CPQ.label(B)), 65_535, 65_536);

		List<TestQuery> queries = List.of(
			path(A, A),
			path(A, A, A),
			path(C, B, A.getInverse()),
			inter(path(A, A, A, A), ID),
			inter(path(A, A), path(C, B, B.getInverse(), A.getInverse(), A, A))
		);
		TestQueries.checkAll(queries, query->checkQuery(index, direct, graph, k, query), graph + " k=" + k + " ");
	}

	/**
	 * Queries whose result is empty in every possible way must return an empty list and a zero
	 * cardinality, and must not throw.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void emptyResults(int k) throws InterruptedException{
		//0 -a-> 1 -b-> 2 (acyclic chain, label c and d unused)
		TestGraph graph = new TestGraph("chain", 3).edge(0, 1, A).edge(1, 2, B);
		PathIndex index = new PathIndex(graph.toIndexGraph(), k);

		List<CPQ> empty = List.of(
			CPQ.label(C),
			CPQ.label(D),
			CPQ.label(C.getInverse()),
			CPQ.labels(B, A),
			CPQ.labels(A, A),
			CPQ.labels(A, B, A),
			CPQ.labels(A, B, A, B, A, B, A),
			CPQ.intersect(CPQ.label(A), CPQ.label(B)),
			CPQ.intersect(CPQ.label(A), CPQ.id()),
			CPQ.intersect(CPQ.labels(A, B), CPQ.id()),
			CPQ.intersect(CPQ.labels(A, B), CPQ.label(A)),
			CPQ.intersect(CPQ.labels(A, B, B.getInverse(), A.getInverse(), A, B), CPQ.id()),
			CPQ.concat(CPQ.label(C), CPQ.label(A)),
			CPQ.concat(CPQ.label(A), CPQ.label(C)),
			CPQ.concat(CPQ.intersect(CPQ.label(A), CPQ.label(B)), CPQ.label(B)),
			CPQ.intersect(CPQ.label(D), CPQ.id())
		);

		for(CPQ cpq : empty){
			assertEquals(List.of(), index.query(cpq), cpq.toString());
			assertEquals(0, index.computeResultCardinality(cpq), cpq.toString());
		}
	}

	/**
	 * Graphs without any edges at all (and with a single vertex) must be indexable and
	 * answer every query with the empty set.
	 */
	@Test
	public void graphWithoutEdges(){
		List<String> failures = new ArrayList<String>();
		for(TestGraph graph : TestGraphs.edgeless()){
			for(int k : KS){
				try{
					PathIndex index = new PathIndex(graph.toIndexGraph(), k);
					assertEquals(k, index.getK());
					assertPairs(index, CPQ.label(A));
					assertPairs(index, CPQ.labels(A, B, C));
					assertPairs(index, CPQ.intersect(CPQ.label(A), CPQ.id()));
					assertPairs(index, CPQ.intersect(CPQ.labels(A, A.getInverse()), CPQ.id()));
					assertEquals(0, index.computeResultCardinality(CPQ.labels(A, A, A, A, A, A, A)));
				}catch(AssertionError | RuntimeException | InterruptedException e){
					failures.add(graph + " k=" + k + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
				}
			}
		}

		assertTrue(failures.isEmpty(), failures.size() + " of " + (TestGraphs.edgeless().size() * KS.length) + " (graph, k) combinations failed:\n" + String.join("\n", failures));
	}

	//--------------------------------------------------------------------------------------
	// Identity and validation
	//--------------------------------------------------------------------------------------

	/**
	 * Every way of combining identity with plain edges (the position of identity in
	 * concatenations and intersections and several identities in a row).
	 */
	@ParameterizedTest(name = "{0} k={1}")
	@MethodSource("graphsAndK")
	public void identityCombinations(TestGraph graph, int k) throws InterruptedException{
		TestQuery a = edge(A);
		TestQuery b = edge(B);
		checkAgainstOracles(graph, k, List.of(
			concat(ID, a),
			concat(a, ID),
			concat(ID, a, ID),
			concat(ID, ID, a),
			concat(a, ID, ID),
			concat(ID, concat(ID, concat(ID, a))),
			inter(a, ID),
			inter(ID, a),
			inter(ID, a, ID),
			inter(inter(ID, a), ID),
			inter(ID, inter(ID, inter(ID, a))),
			concat(inter(a, ID), inter(b, ID)),
			concat(inter(a, ID), ID, inter(b, ID)),
			inter(concat(ID, a), concat(a, ID)),
			inter(concat(a, ID), ID),
			inter(concat(a, ID, b), ID),
			inter(concat(ID, path(A, B)), ID),
			concat(inter(path(A, B), ID), a),
			concat(a, inter(path(A, B), ID))
		));
	}

	/**
	 * CPQs with a diameter of zero cannot be answered by a path index and have to be
	 * rejected up front with an {@link IllegalArgumentException} instead of failing later.
	 */
	@ParameterizedTest(name = "k={0}")
	@MethodSource("ks")
	public void zeroDiameterIsRejected(int k) throws InterruptedException{
		PathIndex index = new PathIndex(TestGraphs.triangle().toIndexGraph(), k);

		for(TestQuery query : TestQueries.zeroDiameter()){
			CPQ cpq = query.toCPQ();
			assertEquals(0, cpq.getDiameter(), query.toString());
			assertThrows(IllegalArgumentException.class, ()->index.query(cpq), query.toString());
			assertThrows(IllegalArgumentException.class, ()->index.computeResultCardinality(cpq), query.toString());
		}
	}

	/**
	 * The diameter that gMark computes for a CPQ must be consistent with the
	 * diameter computed from the independent syntax tree used by these tests.
	 */
	@Test
	public void diameterConsistency(){
		List<TestQuery> queries = new ArrayList<TestQuery>(TestQueries.enumerate(ENUM_LABELS, 2));
		queries.addAll(TestQueries.tricky());
		queries.addAll(TestQueries.identityWithIdentity());
		queries.addAll(TestQueries.zeroDiameter());
		TestQueries.checkAll(queries, query->assertEquals(query.diameter(), query.toCPQ().getDiameter()), "diameter of ");
	}

	/**
	 * The index has to reject invalid indexed path lengths.
	 */
	@Test
	public void invalidK(){
		assertThrows(IllegalArgumentException.class, ()->new PathIndex(TestGraphs.triangle().toIndexGraph(), 0));
		assertThrows(IllegalArgumentException.class, ()->new PathIndex(TestGraphs.triangle().toIndexGraph(), -1));
	}

	//--------------------------------------------------------------------------------------
	// Plan cache and concurrency
	//--------------------------------------------------------------------------------------

	/**
	 * Evaluating many different queries (and the same query many times, both as the same
	 * object and as distinct but structurally equal objects) in an interleaved order on one
	 * index must give the same answers as evaluating each in isolation: compiled plans are
	 * cached per query and must never be confused with each other.
	 */
	@Test
	public void repeatedAndInterleavedEvaluation() throws InterruptedException{
		TestGraph graph = TestGraphs.randomDense();
		PathIndex index = new PathIndex(graph.toIndexGraph(), 2);
		List<TestQuery> queries = TestQueries.enumerate(ENUM_LABELS, 2);

		Random random = new Random(42);
		for(int i = 0; i < 4000; i++){
			TestQuery query = queries.get(random.nextInt(queries.size()));
			CPQ cpq = query.toCPQ();
			assertEquals(query.evaluateSorted(graph), toPairs(index.query(cpq)), query.toString());
			//the same object a second time (plan cache hit)
			assertEquals(query.evaluateSorted(graph), toPairs(index.query(cpq)), query.toString());
			//a fresh but structurally identical object
			assertEquals(query.evaluateSorted(graph), toPairs(index.query(query.toCPQ())), query.toString());
		}
	}

	/**
	 * Querying one index from many threads at once has to give the same answers as
	 * querying it from a single thread.
	 */
	@Test
	public void concurrentQueries() throws InterruptedException, ExecutionException{
		TestGraph graph = TestGraphs.randomDense();
		PathIndex index = new PathIndex(graph.toIndexGraph(), 2);
		List<TestQuery> queries = new ArrayList<TestQuery>(TestQueries.enumerate(ENUM_LABELS, 2));
		queries.addAll(TestQueries.tricky());

		ExecutorService executor = Executors.newFixedThreadPool(8);
		try{
			List<Future<String>> futures = new ArrayList<Future<String>>();
			for(int worker = 0; worker < 8; worker++){
				final int offset = worker;
				futures.add(executor.submit(()->{
					for(int i = 0; i < queries.size(); i++){
						TestQuery query = queries.get((i * 7 + offset * 131) % queries.size());
						List<SourceTargetPair> actual = toPairs(index.query(query.toCPQ()));
						if(!actual.equals(query.evaluateSorted(graph))){
							return "worker " + offset + " got a wrong answer for " + query;
						}
					}
					return null;
				}));
			}

			for(Future<String> future : futures){
				assertEquals(null, future.get());
			}
		}finally{
			executor.shutdownNow();
		}
	}

	//--------------------------------------------------------------------------------------
	// Helpers
	//--------------------------------------------------------------------------------------

	/**
	 * Gets all combinations of catalog graphs and tested values of k.
	 * @return The arguments for parameterised tests.
	 */
	private static Stream<Arguments> graphsAndK(){
		return TestGraphs.all().stream().flatMap(graph->IntStream.of(KS).mapToObj(k->Arguments.of(graph, k)));
	}

	/**
	 * Gets all tested values of k.
	 * @return The arguments for parameterised tests.
	 */
	private static IntStream ks(){
		return IntStream.of(KS);
	}

	/**
	 * Gets the values of k that are tested on the robots dataset.
	 * @return The arguments for parameterised tests.
	 */
	private static IntStream robotsKs(){
		return IntStream.of(1, 2);
	}

	/**
	 * Gets the smaller tested values of k for tests on graphs where large k is too expensive.
	 * @return The arguments for parameterised tests.
	 */
	private static IntStream smallKs(){
		return IntStream.of(1, 2, 3);
	}

	/**
	 * Builds an index for the given graph and checks all given queries against the
	 * brute force reference and the reachability evaluator.
	 * @param graph The graph to test on.
	 * @param k The indexed path length.
	 * @param queries The queries to check.
	 */
	private static void checkAgainstOracles(TestGraph graph, int k, List<TestQuery> queries) throws InterruptedException{
		PathIndex index = new PathIndex(graph.toIndexGraph(), k);
		ReachabilityQueryEvaluator direct = new ReachabilityQueryEvaluator(graph.toIntGraph());
		TestQueries.checkAll(queries, query->checkQuery(index, direct, graph, k, query), graph + " k=" + k + " ");
	}

	/**
	 * Checks a single query on an index against the brute force reference and the reachability evaluator.
	 * @param index The index to test.
	 * @param direct The reachability evaluator on the same graph.
	 * @param graph The graph the index was built for.
	 * @param k The indexed path length.
	 * @param query The query to check.
	 */
	private static void checkQuery(PathIndex index, ReachabilityQueryEvaluator direct, TestGraph graph, int k, TestQuery query){
		CPQ cpq = query.toCPQ();
		List<SourceTargetPair> expected = query.evaluateSorted(graph);

		assertTrue(cpq.getDiameter() > 0, "tests only use queries with a diameter above zero here");
		assertEquals(expected, toPairs(index.query(cpq)), "path index result");
		assertEquals(expected.size(), index.computeResultCardinality(cpq), "path index cardinality");
		assertEquals(expected, direct.evaluate(PathQuery.of(cpq)).getSourceTargetPairs().stream().sorted().toList(), "reachability evaluator result");
	}

	/**
	 * Asserts that the result of a query is exactly the given set of pairs.
	 * @param index The index to query.
	 * @param cpq The query to evaluate.
	 * @param flatPairs The expected pairs as alternating source and target.
	 */
	private static void assertPairs(PathIndex index, CPQ cpq, int... flatPairs){
		List<SourceTargetPair> expected = pairs(flatPairs);
		assertEquals(expected, toPairs(index.query(cpq)), cpq.toString());
		assertEquals(expected.size(), index.computeResultCardinality(cpq), cpq.toString());
	}

	/**
	 * Builds a sorted list of pairs.
	 * @param flatPairs The pairs as alternating source and target.
	 * @return The sorted pairs.
	 */
	private static List<SourceTargetPair> pairs(int... flatPairs){
		List<SourceTargetPair> pairs = new ArrayList<SourceTargetPair>();
		for(int i = 0; i < flatPairs.length; i += 2){
			pairs.add(new SourceTargetPair(flatPairs[i], flatPairs[i + 1]));
		}
		return pairs.stream().sorted().toList();
	}

	/**
	 * Converts index pairs to the representation used by the reference.
	 * @param result The index result.
	 * @return The result as source target pairs, in the same order.
	 */
	private static List<SourceTargetPair> toPairs(List<Pair> result){
		return result.stream().map(pair->new SourceTargetPair(pair.getSource(), pair.getTarget())).toList();
	}
}
