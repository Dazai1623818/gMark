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
import static dev.roanh.gmark.eval.TestGraph.D;
import static dev.roanh.gmark.eval.TestQuery.ID;
import static dev.roanh.gmark.eval.TestQuery.edge;
import static dev.roanh.gmark.eval.TestQuery.inter;
import static dev.roanh.gmark.eval.TestQuery.path;
import static dev.roanh.gmark.eval.TestQuery.plus;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dev.roanh.gmark.data.CardStat;
import dev.roanh.gmark.data.SourceTargetPair;
import dev.roanh.gmark.lang.ReachabilityQueryLanguageSyntax;
import dev.roanh.gmark.type.schema.Predicate;

/**
 * Edge case tests for the {@link ReachabilityQueryEvaluator} on small hand-built graphs from {@link TestGraphs}
 * with self loops, cycles, diamonds, parallel and duplicate edges, disconnected components and vertices
 * without edges. Every result (including the cardinality statistics) is compared with a naive brute force
 * reference evaluator ({@link TestQuery#evaluate(TestGraph)}) that shares no code with gMark. Besides
 * CPQs this also covers RPQs with concatenation, disjunction and transitive closure, which has to
 * terminate and give exact answers on cyclic graphs, with all combinations of a bound and free source
 * and target.
 * @see ReachabilityQueryEvaluator
 * @see DatabaseGraphEdgeCaseTest
 */
public class ReachabilityQueryEvaluatorEdgeCaseTest{
	/**
	 * The labels used for exhaustive enumeration (label a, label b and their inverses).
	 */
	private static final List<Predicate> ENUM_LABELS = TestQuery.withInverses(A, B);

	//--------------------------------------------------------------------------------------
	// Free source and target
	//--------------------------------------------------------------------------------------

	/**
	 * Every CPQ that can be built from {@code a}, {@code b} and their inverses with up to two
	 * binary operations (884 queries including all identity combinations).
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("allGraphs")
	public void exhaustiveSmallCPQs(TestGraph graph){
		checkUnbound(graph, TestQueries.enumerate(ENUM_LABELS, 2));
	}

	/**
	 * Hand picked tricky CPQs (loops, closed cycles, diamonds, repeated and identical operands,
	 * multiway intersections, nested identity filters and absent labels).
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("allGraphs")
	public void trickyCPQs(TestGraph graph){
		checkUnbound(graph, TestQueries.tricky());
	}

	/**
	 * CPQs that combine identity with itself, for example {@code (a ∘ (id ∩ id)) ∩ id}.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("allGraphs")
	public void identityWithIdentityCPQs(TestGraph graph){
		checkUnbound(graph, TestQueries.identityWithIdentity());
	}

	/**
	 * CPQs that consist of identity only, these select every vertex together with itself.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("allGraphs")
	public void identityOnlyCPQs(TestGraph graph){
		checkUnbound(graph, TestQueries.zeroDiameter());
	}

	/**
	 * Deterministic random CPQs of depth up to four over all labels and their inverses.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("allGraphs")
	public void randomDeepCPQs(TestGraph graph){
		Random random = new Random(0xFACADEL);
		List<Predicate> labels = TestQuery.withInverses(A, B, C, D);
		List<TestQuery> queries = new ArrayList<TestQuery>();
		for(int i = 0; i < 300; i++){
			queries.add(TestQueries.random(random, 4, labels));
		}
		checkUnbound(graph, queries);
	}

	/**
	 * Every instantiation (all assignments of {@code a}, {@code b} and {@code a-}) of the CPQ query
	 * templates that were used to evaluate CPQx in the original paper (chains, triangles, squares and
	 * stars, each also in the loop form that ends in identity), and random instantiations of the
	 * seven slot {@code ST} flower template.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("allGraphs")
	public void cpqxPaperTemplates(TestGraph graph){
		List<TestQuery> queries = new ArrayList<TestQuery>(TestQueries.cpqxExhaustiveQueries(List.of(A, B, A.getInverse())));
		queries.addAll(TestQueries.cpqxSampledQueries(new Random(0xF10EL), TestQuery.withInverses(A, B, C), 40));
		checkUnbound(graph, queries);
	}

	/**
	 * The robots graph (1484 vertices, 2960 edges, 4 labels), the smallest real dataset in the CPQx paper,
	 * queried with randomly labelled instances of the paper's twelve CPQ templates, with a free source and
	 * target and with a bound source and/or target that are taken from the actual answers.
	 */
	@Test
	public void robotsDataset() throws IOException{
		TestGraph graph = TestGraphs.readEdgeList("robots", "robots.edge");
		assertEquals(1484, graph.getVertexCount());
		ReachabilityQueryEvaluator evaluator = new ReachabilityQueryEvaluator(graph.toIntGraph());

		List<TestQuery> queries = TestQueries.cpqxSampledQueries(new Random(0xB0B0L), TestQuery.withInverses(A, B, C, D), 12);
		checkUnbound(graph, queries);
		TestQueries.checkAll(queries, query->{
			Set<SourceTargetPair> reference = query.evaluate(graph);
			ReachabilityQueryLanguageSyntax syntax = toSyntax(query);
			//bind the source and/or target to vertices of the first few answers (if any)
			for(SourceTargetPair answer : reference.stream().sorted().limit(3).toList()){
				for(int[] binding : new int[][]{{answer.source(), -1}, {-1, answer.target()}, {answer.source(), answer.target()}}){
					assertEquals(
						TestQuery.sorted(TestQuery.restrict(reference, binding[0], binding[1])),
						eval(evaluator, PathQuery.of(binding[0], syntax, binding[1])),
						"binding " + binding[0] + ", " + binding[1]
					);
				}
			}
		}, "robots ");
	}

	/**
	 * Regular path queries with transitive closure of labels, disjunctions and concatenations,
	 * evaluated on graphs that are full of cycles.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("allGraphs")
	public void regularPathQueries(TestGraph graph){
		checkUnbound(graph, TestQueries.rpqs());
	}

	/**
	 * Label sequences that are longer than any short path would be.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("allGraphs")
	public void longLabelSequences(TestGraph graph){
		checkUnbound(graph, TestQueries.labelSequences(ENUM_LABELS, 5));
	}

	//--------------------------------------------------------------------------------------
	// Bound source and/or target
	//--------------------------------------------------------------------------------------

	/**
	 * CPQs and RPQs with a bound source vertex and a free target, for every vertex of every graph.
	 */
	@Test
	public void boundSource(){
		checkBound(true, false);
	}

	/**
	 * CPQs and RPQs with a free source and a bound target vertex, for every vertex of every graph.
	 */
	@Test
	public void boundTarget(){
		checkBound(false, true);
	}

	/**
	 * CPQs and RPQs with both a bound source and a bound target vertex, for every pair of vertices of every graph.
	 */
	@Test
	public void boundSourceAndTarget(){
		checkBound(true, true);
	}

	//--------------------------------------------------------------------------------------
	// Pinned, hand verified transitive closure behaviour
	//--------------------------------------------------------------------------------------

	/**
	 * Transitive closure over a single self loop: vertex 0 reaches itself, an isolated vertex nothing.
	 */
	@Test
	public void closureOfSelfLoop(){
		TestGraph graph = TestGraphs.selfLoop();
		ReachabilityQueryEvaluator evaluator = new ReachabilityQueryEvaluator(graph.toIntGraph());

		assertEquals(pairs(0, 0), eval(evaluator, PathQuery.of(plus(edge(A)).toRPQ())));
		assertEquals(pairs(0, 0), eval(evaluator, PathQuery.of(0, plus(edge(A)).toRPQ())));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(1, plus(edge(A)).toRPQ())));
		assertEquals(pairs(0, 0), eval(evaluator, PathQuery.of(plus(edge(A)).toRPQ(), 0)));
		assertEquals(pairs(0, 0), eval(evaluator, PathQuery.of(0, plus(edge(A)).toRPQ(), 0)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(plus(edge(A)).toRPQ(), 1)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(1, plus(edge(A)).toRPQ(), 1)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(0, plus(edge(A)).toRPQ(), 1)));
	}

	/**
	 * On a cycle every vertex reaches every vertex including itself, on an acyclic path
	 * no vertex reaches itself. A bound source and target that are equal must only match on a cycle.
	 */
	@Test
	public void closureReachesSourceOnlyOnCycle(){
		//0 -> 1 -> 2 -> 0 (cycle) and 3 -> 0 (tail into the cycle) and 4 -> 5 (acyclic)
		TestGraph graph = new TestGraph("cycle-with-tail", 6).cycle(A, 0, 1, 2).edge(3, 0, A).edge(4, 5, A);
		ReachabilityQueryEvaluator evaluator = new ReachabilityQueryEvaluator(graph.toIntGraph());
		ReachabilityQueryLanguageSyntax closure = plus(edge(A)).toRPQ();

		assertEquals(pairs(0, 0, 0, 1, 0, 2, 1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2, 3, 0, 3, 1, 3, 2, 4, 5), eval(evaluator, PathQuery.of(closure)));
		assertEquals(pairs(0, 0, 0, 1, 0, 2), eval(evaluator, PathQuery.of(0, closure)));
		assertEquals(pairs(3, 0, 3, 1, 3, 2), eval(evaluator, PathQuery.of(3, closure)));
		assertEquals(pairs(4, 5), eval(evaluator, PathQuery.of(4, closure)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(5, closure)));
		assertEquals(pairs(0, 0, 1, 0, 2, 0, 3, 0), eval(evaluator, PathQuery.of(closure, 0)));
		assertEquals(pairs(4, 5), eval(evaluator, PathQuery.of(closure, 5)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(closure, 3)));
		assertEquals(pairs(0, 0), eval(evaluator, PathQuery.of(0, closure, 0)));
		assertEquals(pairs(1, 1), eval(evaluator, PathQuery.of(1, closure, 1)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(3, closure, 3)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(4, closure, 4)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(5, closure, 5)));
		assertEquals(pairs(3, 2), eval(evaluator, PathQuery.of(3, closure, 2)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(0, closure, 3)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(0, closure, 5)));
	}

	/**
	 * Closure of a closure is the closure, and a closure of an inverse label is the transposed closure.
	 */
	@Test
	public void closureIsIdempotentAndInvertible(){
		TestGraph graph = TestGraphs.figureEight();
		ReachabilityQueryEvaluator evaluator = new ReachabilityQueryEvaluator(graph.toIntGraph());

		List<SourceTargetPair> closure = eval(evaluator, PathQuery.of(plus(edge(A)).toRPQ()));
		assertEquals(pairs(0, 0, 0, 1, 0, 2, 1, 0, 1, 1, 1, 2, 2, 0, 2, 1, 2, 2), closure);
		assertEquals(closure, eval(evaluator, PathQuery.of(plus(plus(edge(A))).toRPQ())));
		assertEquals(closure, eval(evaluator, PathQuery.of(plus(plus(plus(edge(A)))).toRPQ())));
		assertEquals(
			TestQuery.sorted(TestQuery.transpose(Set.copyOf(closure))),
			eval(evaluator, PathQuery.of(plus(edge(A.getInverse())).toRPQ()))
		);
		//both cycles together: everything on a cycle reaches everything on a cycle through vertex 0
		assertEquals(
			pairs(
				0, 0, 0, 1, 0, 2, 0, 3, 0, 4,
				1, 0, 1, 1, 1, 2, 1, 3, 1, 4,
				2, 0, 2, 1, 2, 2, 2, 3, 2, 4,
				3, 0, 3, 1, 3, 2, 3, 3, 3, 4,
				4, 0, 4, 1, 4, 2, 4, 3, 4, 4
			),
			eval(evaluator, PathQuery.of(plus(TestQuery.union(edge(A), edge(B))).toRPQ()))
		);
	}

	/**
	 * Intersection with identity and bound vertices: a bound source and target that differ can never match a loop.
	 */
	@Test
	public void boundLoopsOnSelfLoopGraph(){
		TestGraph graph = TestGraphs.selfLoopsAndBridges();
		ReachabilityQueryEvaluator evaluator = new ReachabilityQueryEvaluator(graph.toIntGraph());
		ReachabilityQueryLanguageSyntax loop = inter(edge(A), ID).toCPQ();

		assertEquals(pairs(0, 0, 2, 2), eval(evaluator, PathQuery.of(loop)));
		assertEquals(pairs(0, 0), eval(evaluator, PathQuery.of(0, loop)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(1, loop)));
		assertEquals(pairs(2, 2), eval(evaluator, PathQuery.of(2, loop, 2)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(0, loop, 2)));
		assertEquals(pairs(), eval(evaluator, PathQuery.of(1, loop, 1)));
		assertEquals(pairs(0, 0), eval(evaluator, PathQuery.of(loop, 0)));
	}

	/**
	 * Duplicate and parallel edges must not lead to duplicate results or inflated statistics.
	 */
	@Test
	public void duplicateEdgesAreNotCountedTwice(){
		//0 -a,b,c-> 1 (a added twice), 1 -a-> 0, 1 -b-> 1
		TestGraph graph = TestGraphs.parallelEdges();
		ReachabilityQueryEvaluator evaluator = new ReachabilityQueryEvaluator(graph.toIntGraph());

		ResultGraph result = evaluator.evaluate(PathQuery.of(edge(A).toCPQ()));
		assertEquals(pairs(0, 1, 1, 0), result.getSourceTargetPairs().stream().sorted().toList());
		assertEquals(new CardStat(2, 2, 2), result.computeCardinality());
		assertEquals(new CardStat(1, 1, 1), evaluator.evaluate(PathQuery.of(inter(edge(A), edge(B), edge(C)).toCPQ())).computeCardinality());
		assertEquals(new CardStat(2, 2, 2), evaluator.evaluate(PathQuery.of(path(A, A).toCPQ())).computeCardinality());
	}

	/**
	 * Vertex identifiers above 16 bits in a graph with very many isolated vertices.
	 */
	@Test
	public void largeVertexIdentifiers(){
		int vertices = 70_000;
		TestGraph graph = new TestGraph("sparse-70000", vertices)
			.edge(vertices - 1, 0, A)
			.edge(0, 65_536, A)
			.edge(65_536, 65_536, B)
			.edge(65_536, vertices - 1, A)
			.edge(65_535, 65_536, C);
		ReachabilityQueryEvaluator evaluator = new ReachabilityQueryEvaluator(graph.toIntGraph());

		List<TestQuery> queries = new ArrayList<TestQuery>(List.of(
			path(A, A),
			path(A, A, A),
			path(C, B, A.getInverse()),
			inter(path(A, A, A), ID),
			inter(path(A, A), path(C, B, B.getInverse(), A.getInverse(), A, A)),
			plus(edge(A)),
			plus(TestQuery.union(edge(A), edge(B)))
		));
		TestQueries.checkAll(queries, query->{
			assertEquals(query.evaluateSorted(graph), eval(evaluator, PathQuery.of(toSyntax(query))), "free source and target");
			for(int bound : new int[]{0, 65_536, vertices - 1}){
				assertEquals(TestQuery.sorted(TestQuery.restrict(query.evaluate(graph), bound, -1)), eval(evaluator, PathQuery.of(bound, toSyntax(query))), "bound source " + bound);
				assertEquals(TestQuery.sorted(TestQuery.restrict(query.evaluate(graph), -1, bound)), eval(evaluator, PathQuery.of(toSyntax(query), bound)), "bound target " + bound);
			}
		}, graph + " ");
	}

	//--------------------------------------------------------------------------------------
	// Helpers
	//--------------------------------------------------------------------------------------

	/**
	 * Gets all catalog graphs including the degenerate graphs without edges.
	 * @return The graphs to test.
	 */
	private static Stream<TestGraph> allGraphs(){
		return Stream.concat(TestGraphs.all().stream(), TestGraphs.edgeless().stream());
	}

	/**
	 * Checks the free source and target result and the cardinality statistics of all queries on a graph.
	 * @param graph The graph to test on.
	 * @param queries The queries to check.
	 */
	private static void checkUnbound(TestGraph graph, List<TestQuery> queries){
		ReachabilityQueryEvaluator evaluator = new ReachabilityQueryEvaluator(graph.toIntGraph());
		TestQueries.checkAll(queries, query->{
			Set<SourceTargetPair> reference = query.evaluate(graph);
			List<SourceTargetPair> expected = TestQuery.sorted(reference);

			ResultGraph result = evaluator.evaluate(PathQuery.of(toSyntax(query)));
			assertEquals(expected, result.getSourceTargetPairs().stream().sorted().toList(), "result");
			assertEquals(
				new CardStat(
					(int)reference.stream().map(SourceTargetPair::source).distinct().count(),
					reference.size(),
					(int)reference.stream().map(SourceTargetPair::target).distinct().count()
				),
				result.computeCardinality(),
				"cardinality statistics"
			);

			//a query that is both a CPQ and an RPQ has to give the same result in both forms
			if(query.isCPQ() && query.isRPQ()){
				assertEquals(expected, eval(evaluator, PathQuery.of(query.toRPQ())), "result when expressed as an RPQ");
			}
		}, graph + " ");
	}

	/**
	 * Checks all queries with bound vertices on every catalog graph and aggregates all failures in one report.
	 * @param bindSource True to bind the source (otherwise it is free).
	 * @param bindTarget True to bind the target (otherwise it is free).
	 */
	private static void checkBound(boolean bindSource, boolean bindTarget){
		List<TestQuery> queries = new ArrayList<TestQuery>(TestQueries.tricky());
		queries.addAll(TestQueries.enumerate(ENUM_LABELS, 1));
		queries.addAll(TestQueries.identityWithIdentity());
		queries.addAll(TestQueries.rpqs());

		List<String> failures = new ArrayList<String>();
		int total = 0;
		for(TestGraph graph : (Iterable<TestGraph>)allGraphs()::iterator){
			ReachabilityQueryEvaluator evaluator = new ReachabilityQueryEvaluator(graph.toIntGraph());
			total += queries.size();
			TestQueries.collect(queries, query->{
				Set<SourceTargetPair> reference = query.evaluate(graph);
				ReachabilityQueryLanguageSyntax syntax = toSyntax(query);
				for(int source = bindSource ? 0 : -1; source < (bindSource ? graph.getVertexCount() : 0); source++){
					for(int target = bindTarget ? 0 : -1; target < (bindTarget ? graph.getVertexCount() : 0); target++){
						assertEquals(
							TestQuery.sorted(TestQuery.restrict(reference, source, target)),
							eval(evaluator, PathQuery.of(source, syntax, target)),
							"source=" + source + " target=" + target
						);
					}
				}
			}, graph + " " + (bindSource ? "bound source " : "") + (bindTarget ? "bound target " : ""), failures);
		}

		TestQueries.report(failures, total);
	}

	/**
	 * Converts a test query to the gMark query that is evaluated (CPQ if possible, RPQ otherwise).
	 * @param query The test query.
	 * @return The gMark query.
	 */
	private static ReachabilityQueryLanguageSyntax toSyntax(TestQuery query){
		return query.isCPQ() ? query.toCPQ() : query.toRPQ();
	}

	/**
	 * Evaluates a path query and returns the sorted result.
	 */
	private static List<SourceTargetPair> eval(ReachabilityQueryEvaluator evaluator, PathQuery query){
		return evaluator.evaluate(query).getSourceTargetPairs().stream().sorted().collect(Collectors.toList());
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
}
