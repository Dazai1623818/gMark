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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import dev.roanh.gmark.data.SourceTargetPair;
import dev.roanh.gmark.lang.cpq.CPQ;
import dev.roanh.gmark.lang.rpq.RPQ;
import dev.roanh.gmark.type.schema.Predicate;

/**
 * A tiny query syntax tree that is independent of gMark's own CPQ and RPQ classes. Every
 * query can be converted to a gMark {@link CPQ} or {@link RPQ} (to be evaluated by the code
 * under test) and can also be evaluated directly on a {@link TestGraph} by a deliberately
 * naive set based reference evaluator. Since the reference never touches gMark's query
 * classes, it also cross-checks the gMark query builders and abstract syntax trees.
 * <p>
 * Semantics of the reference evaluator (the same as the gMark evaluators):
 * <ul>
 * <li>A query matches a <em>set</em> of source target pairs, walks are allowed to repeat vertices and edges.</li>
 * <li>{@link Edge} matches single edges, inverse labels traverse edges backwards.</li>
 * <li>{@link Id} matches {@code (v, v)} for every vertex {@code v} of the graph.</li>
 * <li>{@link Concat} is relational composition, {@link Inter} is set intersection
 *     and {@link Union} is set union.</li>
 * <li>{@link Plus} is the transitive closure (one or more repetitions, never zero), which is
 *     what gMark's {@code RPQ.kleene} computes.</li>
 * </ul>
 * @see TestGraph
 */
public sealed interface TestQuery permits TestQuery.Edge, TestQuery.Id, TestQuery.Concat, TestQuery.Inter, TestQuery.Union, TestQuery.Plus{
	/**
	 * The identity query.
	 */
	public static final TestQuery ID = new Id();

	/**
	 * Creates a single label traversal.
	 * @param label The label to traverse.
	 * @return The query.
	 */
	public static TestQuery edge(Predicate label){
		return new Edge(label);
	}

	/**
	 * Creates a query that traverses the given labels in order.
	 * @param labels The labels to traverse (at least one).
	 * @return The query, a single edge for one label and a (left deep) concatenation otherwise.
	 */
	public static TestQuery path(Predicate... labels){
		TestQuery query = edge(labels[0]);
		for(int i = 1; i < labels.length; i++){
			query = concat(query, edge(labels[i]));
		}
		return query;
	}

	/**
	 * Creates the concatenation of the given queries.
	 * @param parts The queries to concatenate (at least two).
	 * @return The query.
	 */
	public static TestQuery concat(TestQuery... parts){
		return new Concat(List.of(parts));
	}

	/**
	 * Creates the intersection of the given queries.
	 * @param parts The queries to intersect (at least two).
	 * @return The query.
	 */
	public static TestQuery inter(TestQuery... parts){
		return new Inter(List.of(parts));
	}

	/**
	 * Creates the union (disjunction) of the given queries. Not valid for CPQ.
	 * @param parts The queries to unite (at least two).
	 * @return The query.
	 */
	public static TestQuery union(TestQuery... parts){
		return new Union(List.of(parts));
	}

	/**
	 * Creates the transitive closure of the given query. Not valid for CPQ.
	 * @param query The query to repeat one or more times.
	 * @return The query.
	 */
	public static TestQuery plus(TestQuery query){
		return new Plus(query);
	}

	/**
	 * Tests if this query can be expressed as a CPQ.
	 * @return True if this query is a CPQ.
	 */
	public boolean isCPQ();

	/**
	 * Tests if this query can be expressed as an RPQ.
	 * @return True if this query is an RPQ.
	 */
	public boolean isRPQ();

	/**
	 * Converts this query to a gMark CPQ.
	 * @return The CPQ.
	 * @throws IllegalStateException When this query is not a CPQ.
	 */
	public CPQ toCPQ() throws IllegalStateException;

	/**
	 * Converts this query to a gMark RPQ.
	 * @return The RPQ.
	 * @throws IllegalStateException When this query is not an RPQ.
	 */
	public RPQ toRPQ() throws IllegalStateException;

	/**
	 * Computes the diameter (as defined for CPQ) of this query.
	 * @return The diameter.
	 * @throws IllegalStateException When this query is not a CPQ.
	 */
	public int diameter() throws IllegalStateException;

	/**
	 * Evaluates this query on the given graph using naive set operations.
	 * @param graph The graph to evaluate on.
	 * @return The matched source target pairs.
	 */
	public Set<SourceTargetPair> evaluate(TestGraph graph);

	/**
	 * Evaluates this query on the given graph and returns the result sorted.
	 * @param graph The graph to evaluate on.
	 * @return The matched source target pairs in natural order.
	 */
	public default List<SourceTargetPair> evaluateSorted(TestGraph graph){
		return sorted(evaluate(graph));
	}

	/**
	 * Sorts a set of pairs in natural order.
	 * @param pairs The pairs to sort.
	 * @return The sorted pairs.
	 */
	public static List<SourceTargetPair> sorted(Set<SourceTargetPair> pairs){
		return pairs.stream().sorted().toList();
	}

	/**
	 * Computes the transpose of a relation (swaps source and target for every pair).
	 * @param pairs The relation to transpose.
	 * @return The transposed relation.
	 */
	public static Set<SourceTargetPair> transpose(Set<SourceTargetPair> pairs){
		return pairs.stream().map(p->new SourceTargetPair(p.target(), p.source())).collect(Collectors.toSet());
	}

	/**
	 * Restricts a relation to the given bound source and/or target.
	 * @param pairs The relation to restrict.
	 * @param source The bound source or -1 if unbound.
	 * @param target The bound target or -1 if unbound.
	 * @return The restricted relation.
	 */
	public static Set<SourceTargetPair> restrict(Set<SourceTargetPair> pairs, int source, int target){
		return pairs.stream().filter(p->(source == -1 || p.source() == source) && (target == -1 || p.target() == target)).collect(Collectors.toSet());
	}

	/**
	 * Composes two relations ({@code first} followed by {@code second}).
	 * @param first The first relation.
	 * @param second The second relation.
	 * @return The composition.
	 */
	private static Set<SourceTargetPair> compose(Set<SourceTargetPair> first, Set<SourceTargetPair> second){
		Map<Integer, List<Integer>> bySource = new HashMap<Integer, List<Integer>>();
		for(SourceTargetPair pair : second){
			bySource.computeIfAbsent(pair.source(), k->new ArrayList<Integer>()).add(pair.target());
		}

		Set<SourceTargetPair> result = new HashSet<SourceTargetPair>();
		for(SourceTargetPair pair : first){
			for(int target : bySource.getOrDefault(pair.target(), List.of())){
				result.add(new SourceTargetPair(pair.source(), target));
			}
		}
		return result;
	}

	/**
	 * Computes the transitive closure (one or more steps) of a relation.
	 * @param relation The relation to close.
	 * @return The transitive closure.
	 */
	private static Set<SourceTargetPair> closure(Set<SourceTargetPair> relation){
		Map<Integer, List<Integer>> adjacency = new HashMap<Integer, List<Integer>>();
		for(SourceTargetPair pair : relation){
			adjacency.computeIfAbsent(pair.source(), k->new ArrayList<Integer>()).add(pair.target());
		}

		Set<SourceTargetPair> result = new HashSet<SourceTargetPair>();
		for(int source : adjacency.keySet()){
			Set<Integer> seen = new HashSet<Integer>();
			Deque<Integer> queue = new ArrayDeque<Integer>(adjacency.get(source));
			while(!queue.isEmpty()){
				int vertex = queue.poll();
				if(seen.add(vertex)){
					result.add(new SourceTargetPair(source, vertex));
					queue.addAll(adjacency.getOrDefault(vertex, List.of()));
				}
			}
		}
		return result;
	}

	/**
	 * Formats a list of queries as a string.
	 */
	private static String join(List<TestQuery> parts, String operator){
		return parts.stream().map(TestQuery::toString).collect(Collectors.joining(" " + operator + " ", "(", ")"));
	}

	/**
	 * A single (possibly inverse) label traversal.
	 * @param label The label to traverse.
	 */
	public static record Edge(Predicate label) implements TestQuery{
		@Override
		public boolean isCPQ(){
			return true;
		}

		@Override
		public boolean isRPQ(){
			return true;
		}

		@Override
		public CPQ toCPQ(){
			return CPQ.label(label);
		}

		@Override
		public RPQ toRPQ(){
			return RPQ.label(label);
		}

		@Override
		public int diameter(){
			return 1;
		}

		@Override
		public Set<SourceTargetPair> evaluate(TestGraph graph){
			return graph.step(label);
		}

		@Override
		public String toString(){
			return label.getAlias();
		}
	}

	/**
	 * The identity.
	 */
	public static record Id() implements TestQuery{
		@Override
		public boolean isCPQ(){
			return true;
		}

		@Override
		public boolean isRPQ(){
			return false;
		}

		@Override
		public CPQ toCPQ(){
			return CPQ.id();
		}

		@Override
		public RPQ toRPQ(){
			throw new IllegalStateException("Identity is not an RPQ.");
		}

		@Override
		public int diameter(){
			return 0;
		}

		@Override
		public Set<SourceTargetPair> evaluate(TestGraph graph){
			Set<SourceTargetPair> result = new HashSet<SourceTargetPair>();
			for(int v = 0; v < graph.getVertexCount(); v++){
				result.add(new SourceTargetPair(v, v));
			}
			return result;
		}

		@Override
		public String toString(){
			return "id";
		}
	}

	/**
	 * Concatenation of two or more queries.
	 * @param parts The concatenated queries.
	 */
	public static record Concat(List<TestQuery> parts) implements TestQuery{
		/**
		 * Constructs a new concatenation.
		 * @param parts The queries to concatenate (at least two).
		 */
		public Concat{
			if(parts.size() < 2){
				throw new IllegalArgumentException("A concatenation needs at least two operands.");
			}
		}

		@Override
		public boolean isCPQ(){
			return parts.stream().allMatch(TestQuery::isCPQ);
		}

		@Override
		public boolean isRPQ(){
			return parts.stream().allMatch(TestQuery::isRPQ);
		}

		@Override
		public CPQ toCPQ(){
			return CPQ.concat(parts.stream().map(TestQuery::toCPQ).toList());
		}

		@Override
		public RPQ toRPQ(){
			return RPQ.concat(parts.stream().map(TestQuery::toRPQ).toList());
		}

		@Override
		public int diameter(){
			return parts.stream().mapToInt(TestQuery::diameter).sum();
		}

		@Override
		public Set<SourceTargetPair> evaluate(TestGraph graph){
			Set<SourceTargetPair> result = parts.get(0).evaluate(graph);
			for(int i = 1; i < parts.size(); i++){
				result = compose(result, parts.get(i).evaluate(graph));
			}
			return result;
		}

		@Override
		public String toString(){
			return join(parts, "∘");
		}
	}

	/**
	 * Intersection of two or more queries.
	 * @param parts The intersected queries.
	 */
	public static record Inter(List<TestQuery> parts) implements TestQuery{
		/**
		 * Constructs a new intersection.
		 * @param parts The queries to intersect (at least two).
		 */
		public Inter{
			if(parts.size() < 2){
				throw new IllegalArgumentException("An intersection needs at least two operands.");
			}
		}

		@Override
		public boolean isCPQ(){
			return parts.stream().allMatch(TestQuery::isCPQ);
		}

		@Override
		public boolean isRPQ(){
			return false;
		}

		@Override
		public CPQ toCPQ(){
			return CPQ.intersect(parts.stream().map(TestQuery::toCPQ).toList());
		}

		@Override
		public RPQ toRPQ(){
			throw new IllegalStateException("Intersection is not an RPQ.");
		}

		@Override
		public int diameter(){
			return parts.stream().mapToInt(TestQuery::diameter).max().orElseThrow();
		}

		@Override
		public Set<SourceTargetPair> evaluate(TestGraph graph){
			Set<SourceTargetPair> result = new HashSet<SourceTargetPair>(parts.get(0).evaluate(graph));
			for(int i = 1; i < parts.size(); i++){
				result.retainAll(parts.get(i).evaluate(graph));
			}
			return result;
		}

		@Override
		public String toString(){
			return join(parts, "∩");
		}
	}

	/**
	 * Union (disjunction) of two or more queries.
	 * @param parts The united queries.
	 */
	public static record Union(List<TestQuery> parts) implements TestQuery{
		/**
		 * Constructs a new union.
		 * @param parts The queries to unite (at least two).
		 */
		public Union{
			if(parts.size() < 2){
				throw new IllegalArgumentException("A union needs at least two operands.");
			}
		}

		@Override
		public boolean isCPQ(){
			return false;
		}

		@Override
		public boolean isRPQ(){
			return parts.stream().allMatch(TestQuery::isRPQ);
		}

		@Override
		public CPQ toCPQ(){
			throw new IllegalStateException("Union is not a CPQ.");
		}

		@Override
		public RPQ toRPQ(){
			return RPQ.disjunct(parts.stream().map(TestQuery::toRPQ).toList());
		}

		@Override
		public int diameter(){
			throw new IllegalStateException("Union is not a CPQ.");
		}

		@Override
		public Set<SourceTargetPair> evaluate(TestGraph graph){
			Set<SourceTargetPair> result = new HashSet<SourceTargetPair>();
			for(TestQuery part : parts){
				result.addAll(part.evaluate(graph));
			}
			return result;
		}

		@Override
		public String toString(){
			return join(parts, "|");
		}
	}

	/**
	 * Transitive closure (one or more repetitions) of a query.
	 * @param operand The repeated query.
	 */
	public static record Plus(TestQuery operand) implements TestQuery{
		@Override
		public boolean isCPQ(){
			return false;
		}

		@Override
		public boolean isRPQ(){
			return operand.isRPQ();
		}

		@Override
		public CPQ toCPQ(){
			throw new IllegalStateException("Transitive closure is not a CPQ.");
		}

		@Override
		public RPQ toRPQ(){
			return RPQ.kleene(operand.toRPQ());
		}

		@Override
		public int diameter(){
			throw new IllegalStateException("Transitive closure is not a CPQ.");
		}

		@Override
		public Set<SourceTargetPair> evaluate(TestGraph graph){
			return closure(operand.evaluate(graph));
		}

		@Override
		public String toString(){
			return operand + "+";
		}
	}

	/**
	 * Convenience for building a list of labels and their inverses.
	 * @param labels The forward labels.
	 * @return The labels followed by the inverse of every label in an interleaved order.
	 */
	public static List<Predicate> withInverses(Predicate... labels){
		List<Predicate> result = new ArrayList<Predicate>(labels.length * 2);
		for(Predicate label : labels){
			result.add(label);
			result.add(label.getInverse());
		}
		return result;
	}
}
