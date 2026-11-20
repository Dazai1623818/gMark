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
import static dev.roanh.gmark.eval.TestQuery.ID;
import static dev.roanh.gmark.eval.TestQuery.concat;
import static dev.roanh.gmark.eval.TestQuery.edge;
import static dev.roanh.gmark.eval.TestQuery.inter;
import static dev.roanh.gmark.eval.TestQuery.path;
import static dev.roanh.gmark.eval.TestQuery.plus;
import static dev.roanh.gmark.eval.TestQuery.union;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.roanh.gmark.type.schema.Predicate;

/**
 * Generators and catalogs of CPQs used by the edge case tests.
 * @see TestQuery
 * @see TestGraphs
 */
public final class TestQueries{

	/**
	 * Prevent instantiation.
	 */
	private TestQueries(){
	}

	/**
	 * Exhaustively enumerates all CPQs that can be built from the given labels using
	 * at most the given number of binary operations (concatenation and intersection),
	 * including all ways to combine a query with identity (by concatenation on either
	 * side or intersection). Queries are structurally unique, so for example both
	 * left and right nested concatenations appear. The result is deterministic.
	 * <p>
	 * For four labels (two labels and their inverses) the number of queries is
	 * 4 for zero operations, 48 for one and 884 for two.
	 * @param alphabet The (possibly inverse) labels the queries are built from.
	 * @param maxOperations The maximum number of binary operations.
	 * @return All queries with at least one and at most {@code maxOperations} operations,
	 *         preceded by the plain labels (zero operations).
	 */
	public static List<TestQuery> enumerate(List<Predicate> alphabet, int maxOperations){
		List<List<TestQuery>> levels = new ArrayList<List<TestQuery>>();
		levels.add(alphabet.stream().<TestQuery>map(TestQuery::edge).toList());

		for(int operations = 1; operations <= maxOperations; operations++){
			Set<TestQuery> level = new LinkedHashSet<TestQuery>();
			for(int left = 0; left < operations; left++){
				for(TestQuery first : levels.get(left)){
					for(TestQuery second : levels.get(operations - 1 - left)){
						level.add(concat(first, second));
						level.add(inter(first, second));
					}
				}
			}

			for(TestQuery inner : levels.get(operations - 1)){
				level.add(inter(inner, ID));
				level.add(concat(inner, ID));
				level.add(concat(ID, inner));
			}

			levels.add(List.copyOf(level));
		}

		return levels.stream().flatMap(List::stream).toList();
	}

	/**
	 * Enumerates all label sequences of every length up to the given length.
	 * @param alphabet The (possibly inverse) labels to build sequences from.
	 * @param maxLength The maximum sequence length.
	 * @return All label sequences as plain concatenation queries.
	 */
	public static List<TestQuery> labelSequences(List<Predicate> alphabet, int maxLength){
		List<TestQuery> result = new ArrayList<TestQuery>();
		List<List<Predicate>> current = List.of(List.of());
		for(int length = 1; length <= maxLength; length++){
			List<List<Predicate>> next = new ArrayList<List<Predicate>>();
			for(List<Predicate> prefix : current){
				for(Predicate label : alphabet){
					List<Predicate> sequence = new ArrayList<Predicate>(prefix);
					sequence.add(label);
					next.add(sequence);
					result.add(path(sequence.toArray(Predicate[]::new)));
				}
			}
			current = next;
		}
		return result;
	}

	/**
	 * Generates a random CPQ with a deterministic random source. Unlike the
	 * enumeration this reaches larger query trees and mixes in identity.
	 * @param random The random source.
	 * @param depth The maximum remaining depth of the query tree.
	 * @param alphabet The (possibly inverse) labels to use.
	 * @return The generated query.
	 */
	public static TestQuery random(Random random, int depth, List<Predicate> alphabet){
		if(depth == 0 || random.nextInt(5) == 0){
			return edge(alphabet.get(random.nextInt(alphabet.size())));
		}

		return switch(random.nextInt(6)){
		case 0, 1 -> concat(random(random, depth - 1, alphabet), random(random, depth - 1, alphabet));
		case 2, 3 -> inter(random(random, depth - 1, alphabet), random(random, depth - 1, alphabet));
		case 4 -> inter(random(random, depth - 1, alphabet), ID);
		default -> concat(random(random, depth - 1, alphabet), inter(random(random, depth - 1, alphabet), ID));
		};
	}

	/**
	 * Hand picked queries over the labels {@code a}, {@code b} and {@code c} (and inverses)
	 * that are designed to be awkward: loops of different lengths, closed cycles, diamonds,
	 * repeated and identical operands, multiway intersections and nested identity filters.
	 * Every query has a diameter greater than zero.
	 * @return The queries.
	 */
	public static List<TestQuery> tricky(){
		TestQuery a = edge(A);
		TestQuery b = edge(B);
		TestQuery c = edge(C);
		TestQuery ai = edge(A.getInverse());
		TestQuery bi = edge(B.getInverse());
		TestQuery ci = edge(C.getInverse());

		return List.of(
			//self loops
			inter(a, ID),
			inter(ID, a),
			inter(ID, inter(ID, b)),
			inter(a, ID, ID),
			inter(ID, a, ID),
			inter(inter(a, ID), ID),
			//cycles of increasing length
			inter(path(A, A), ID),
			inter(path(A, A, A), ID),
			inter(path(A, A, A, A), ID),
			inter(path(A, A, A, A, A, A, A), ID),
			inter(path(A, B, C), ID),
			inter(path(A, B, A, B, A, B), ID),
			//closing a cycle with an inverse edge (triangle detection)
			inter(path(A, A), ai),
			inter(path(A, A, A), ai),
			inter(path(A, B), ci),
			inter(path(A, B, C), ID, ID),
			//going back and forth over the same edge
			inter(path(A, A.getInverse()), ID),
			inter(path(A.getInverse(), A), ID),
			path(A, A.getInverse(), A, A.getInverse(), A),
			inter(path(A, A.getInverse(), A), a),
			inter(path(A.getInverse(), B, B.getInverse(), A), ID),
			//diamonds and squares
			inter(path(A, B), path(B, A)),
			inter(path(A, B), path(A, B)),
			inter(path(A, C), path(B, A)),
			inter(concat(a, b), concat(b, a), concat(a, b)),
			inter(path(A, B, B.getInverse(), A.getInverse()), ID),
			inter(path(A, B), path(B, A), path(A, B, B.getInverse(), B)),
			//parallel edges with different labels
			inter(a, b),
			inter(a, b, c),
			inter(a, a),
			inter(a, ai),
			inter(a, b, ID),
			inter(inter(a, b), inter(c, a)),
			//identical or nested operands
			inter(path(A, B), path(A, B), path(A, B)),
			concat(inter(a, b), inter(b, c)),
			concat(inter(a, ID), inter(b, ID)),
			concat(inter(a, ID), inter(a, ID)),
			concat(inter(a, ID), b),
			concat(b, inter(a, ID)),
			inter(concat(inter(a, ID), b), concat(b, inter(a, ID))),
			concat(concat(a, b), concat(c, ai)),
			concat(a, concat(b, concat(c, concat(ai, concat(bi, ci))))),
			concat(concat(concat(concat(concat(a, b), c), ai), bi), ci),
			//identity composition
			concat(ID, a),
			concat(a, ID),
			concat(ID, a, ID),
			concat(ID, concat(ID, a)),
			concat(concat(a, ID), concat(ID, b)),
			inter(concat(ID, a), ID),
			inter(concat(a, ID), concat(ID, a)),
			inter(concat(a, ID, b), path(A, B)),
			inter(concat(ID, path(A, A)), ID),
			concat(inter(path(A, A), ID), inter(path(A, A), ID)),
			//queries over a label that is typically absent from the graph
			edge(TestGraph.D),
			inter(a, edge(TestGraph.D)),
			concat(a, edge(TestGraph.D)),
			inter(path(A, A), edge(TestGraph.D.getInverse()))
		);
	}

	/**
	 * Queries that combine identity with itself (for example {@code id ∩ id}) as a
	 * sub expression of a larger query. These are valid CPQs with a diameter greater
	 * than zero that gMark's generator never produces. They are a special case for
	 * evaluators that simplify identity.
	 * @return The queries.
	 */
	public static List<TestQuery> identityWithIdentity(){
		TestQuery a = edge(A);
		TestQuery b = edge(B);
		TestQuery idid = inter(ID, ID);

		return List.of(
			inter(idid, a),
			inter(a, idid),
			concat(a, idid),
			concat(idid, a),
			concat(idid, a, idid),
			concat(a, b, idid),
			inter(concat(a, idid), b),
			inter(concat(idid, a), concat(a, idid)),
			inter(inter(idid, ID), a),
			inter(a, inter(ID, concat(ID, ID))),
			concat(a, inter(ID, concat(ID, ID))),
			concat(concat(ID, ID), a),
			inter(concat(a, idid), ID),
			inter(concat(idid, a), ID),
			inter(concat(concat(a, idid), b), ID),
			inter(concat(a, concat(ID, ID)), ID)
		);
	}

	/**
	 * Queries that have a diameter of zero, which path indexes cannot evaluate.
	 * @return The queries.
	 */
	public static List<TestQuery> zeroDiameter(){
		return List.of(
			ID,
			concat(ID, ID),
			inter(ID, ID),
			inter(ID, concat(ID, ID)),
			concat(inter(ID, ID), ID),
			inter(ID, ID, ID),
			concat(ID, ID, ID)
		);
	}

	/**
	 * Hand picked regular path queries (concatenation, disjunction and transitive closure,
	 * which has to terminate on cycles and self loops) over {@code a}, {@code b}, {@code c}
	 * and their inverses.
	 * @return The queries, all of them are valid RPQs.
	 */
	public static List<TestQuery> rpqs(){
		TestQuery a = edge(A);
		TestQuery b = edge(B);
		TestQuery c = edge(C);
		TestQuery ai = edge(A.getInverse());
		TestQuery bi = edge(B.getInverse());
		TestQuery ci = edge(C.getInverse());

		return List.of(
			//plain closure of every label in both directions
			plus(a), plus(b), plus(c), plus(ai), plus(bi), plus(ci),
			plus(edge(TestGraph.D)),
			//closure of larger expressions
			plus(union(a, b)),
			plus(union(a, bi)),
			plus(union(a, b, c)),
			plus(union(ai, bi, ci)),
			plus(path(A, B)),
			plus(path(A, B.getInverse())),
			plus(path(A, A)),
			plus(path(A, A, A)),
			plus(union(path(A, B), c)),
			plus(concat(a, plus(b))),
			//closure inside closure and idempotence
			plus(plus(a)),
			plus(plus(union(a, b))),
			concat(plus(a), plus(a)),
			//concatenation of closures
			concat(plus(a), plus(b)),
			concat(plus(a), b),
			concat(a, plus(b)),
			concat(plus(a), plus(ai)),
			concat(plus(ai), plus(a)),
			concat(a, plus(union(b, c)), a),
			concat(plus(union(a, b)), plus(union(a, b))),
			//plain RPQ concatenation and disjunction
			union(a, b),
			union(a, a),
			union(a, ai),
			union(path(A, B), path(B, A)),
			concat(union(a, b), union(b, c)),
			concat(union(a, b), union(a, b), union(a, b)),
			union(concat(a, b), plus(c)),
			union(plus(a), plus(b)),
			union(plus(a), plus(ai)),
			path(A, A, A, A, A, A, A, A),
			path(A, A.getInverse(), A, A.getInverse())
		);
	}

	/**
	 * A parameterised CPQ shape whose label positions (slots) are filled in to obtain queries.
	 * @param name The name of the template.
	 * @param slots The number of label slots in the template.
	 * @param builder Constructs the query from the labels assigned to the slots.
	 */
	public static record Template(String name, int slots, Function<List<Predicate>, TestQuery> builder){

		/**
		 * Instantiates this template with the given labels.
		 * @param labels The labels for the slots, exactly {@link #slots()} of them.
		 * @return The query.
		 */
		public TestQuery instantiate(List<Predicate> labels){
			return builder.apply(labels);
		}

		@Override
		public String toString(){
			return name;
		}
	}

	/**
	 * The twelve CPQ query templates that were used to evaluate CPQx in the paper
	 * "Language-aware Indexing for Conjunctive Path Queries" (Sasaki, Fletcher, Onizuka,
	 * Figure 5), together with a variant of the star template. With {@code C2 = l1 ∘ l2}
	 * the templates are {@code C4 = C2 ∘ C2}, {@code T = C2 ∩ l}, {@code S = C2 ∩ C2},
	 * {@code TT = T ∩ C2}, {@code TC = T ∘ l}, {@code SC = S ∘ l}, {@code ST = S ∘ T},
	 * {@code C2i = C2 ∩ id}, {@code Ti = (C2 ∘ l) ∩ id}, {@code Si = C4 ∩ id} and
	 * {@code St = (l1 ∘ l1⁻) ∩ (l2 ∘ l2⁻) ∩ (l3 ∩ l3⁻) ∩ id} (the last template is reproduced
	 * exactly as printed in the paper, {@code StC} is the same with {@code l3 ∘ l3⁻}).
	 * They cover chains, triangles, squares, stars, flowers and the loop (identity) forms
	 * of these.
	 * @return The templates.
	 */
	public static List<Template> cpqxTemplates(){
		return List.of(
			new Template("C2", 2, l->c2(l, 0)),
			new Template("C4", 4, l->concat(c2(l, 0), c2(l, 2))),
			new Template("T", 3, l->t(l, 0)),
			new Template("S", 4, l->s(l, 0)),
			new Template("TT", 5, l->inter(t(l, 0), c2(l, 3))),
			new Template("TC", 4, l->concat(t(l, 0), edge(l.get(3)))),
			new Template("SC", 5, l->concat(s(l, 0), edge(l.get(4)))),
			new Template("ST", 7, l->concat(s(l, 0), t(l, 4))),
			new Template("C2i", 2, l->inter(c2(l, 0), ID)),
			new Template("Ti", 3, l->inter(concat(c2(l, 0), edge(l.get(2))), ID)),
			new Template("Si", 4, l->inter(concat(c2(l, 0), c2(l, 2)), ID)),
			new Template("St", 3, l->inter(
				concat(edge(l.get(0)), edge(l.get(0).getInverse())),
				concat(edge(l.get(1)), edge(l.get(1).getInverse())),
				inter(edge(l.get(2)), edge(l.get(2).getInverse())),
				ID
			)),
			new Template("StC", 3, l->inter(
				concat(edge(l.get(0)), edge(l.get(0).getInverse())),
				concat(edge(l.get(1)), edge(l.get(1).getInverse())),
				concat(edge(l.get(2)), edge(l.get(2).getInverse())),
				ID
			))
		);
	}

	/**
	 * {@code C2 = l1 ∘ l2} using the labels at the given offset.
	 */
	private static TestQuery c2(List<Predicate> labels, int offset){
		return concat(edge(labels.get(offset)), edge(labels.get(offset + 1)));
	}

	/**
	 * {@code T = C2 ∩ l} using the labels at the given offset.
	 */
	private static TestQuery t(List<Predicate> labels, int offset){
		return inter(c2(labels, offset), edge(labels.get(offset + 2)));
	}

	/**
	 * {@code S = C2 ∩ C2} using the labels at the given offset.
	 */
	private static TestQuery s(List<Predicate> labels, int offset){
		return inter(c2(labels, offset), c2(labels, offset + 2));
	}

	/**
	 * Exhaustively instantiates every {@linkplain #cpqxTemplates() CPQx template} with at most
	 * five label slots with every assignment of the given labels (that is every template
	 * except {@code ST}, see {@link #cpqxSampledQueries(Random, List, int)} for that one).
	 * @param alphabet The (possibly inverse) labels to assign to slots.
	 * @return All instantiated queries.
	 */
	public static List<TestQuery> cpqxExhaustiveQueries(List<Predicate> alphabet){
		List<TestQuery> queries = new ArrayList<TestQuery>();
		for(Template template : cpqxTemplates()){
			if(template.slots() <= 5){
				assign(template, alphabet, new ArrayList<Predicate>(), queries);
			}
		}
		return queries;
	}

	/**
	 * Instantiates every {@linkplain #cpqxTemplates() CPQx template} with random labels
	 * (this mirrors how the queries were generated in the paper).
	 * @param random The random source.
	 * @param alphabet The (possibly inverse) labels to assign to slots.
	 * @param perTemplate The number of queries to generate for each template.
	 * @return The instantiated queries.
	 */
	public static List<TestQuery> cpqxSampledQueries(Random random, List<Predicate> alphabet, int perTemplate){
		List<TestQuery> queries = new ArrayList<TestQuery>();
		for(Template template : cpqxTemplates()){
			for(int i = 0; i < perTemplate; i++){
				List<Predicate> labels = new ArrayList<Predicate>();
				for(int slot = 0; slot < template.slots(); slot++){
					labels.add(alphabet.get(random.nextInt(alphabet.size())));
				}
				queries.add(template.instantiate(labels));
			}
		}
		return queries;
	}

	/**
	 * Recursively assigns labels to the slots of a template.
	 */
	private static void assign(Template template, List<Predicate> alphabet, List<Predicate> chosen, List<TestQuery> result){
		if(chosen.size() == template.slots()){
			result.add(template.instantiate(List.copyOf(chosen)));
			return;
		}

		for(Predicate label : alphabet){
			chosen.add(label);
			assign(template, alphabet, chosen, result);
			chosen.remove(chosen.size() - 1);
		}
	}

	/**
	 * Runs a check for every query and reports <em>all</em> failing queries (up to a limit)
	 * in a single assertion failure instead of stopping at the first one. This makes it
	 * far easier to see the common pattern when an evaluator is wrong for a family of queries.
	 * @param queries The queries to check.
	 * @param check The check to run for each query, failures are signalled by throwing.
	 * @param context A prefix describing the setup of the check, prepended to every failure.
	 */
	public static void checkAll(List<TestQuery> queries, Consumer<TestQuery> check, String context){
		List<String> failures = new ArrayList<String>();
		collect(queries, check, context, failures);
		report(failures, queries.size());
	}

	/**
	 * Runs a check for every query and records a description of every failure.
	 * @param queries The queries to check.
	 * @param check The check to run for each query, failures are signalled by throwing.
	 * @param context A prefix describing the setup of the check, prepended to every failure.
	 * @param failures The list to add failure descriptions to.
	 */
	public static void collect(List<TestQuery> queries, Consumer<TestQuery> check, String context, List<String> failures){
		for(TestQuery query : queries){
			try{
				check.accept(query);
			}catch(AssertionError | RuntimeException e){
				failures.add(context + query + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
			}
		}
	}

	/**
	 * Fails the current test if any failures were recorded.
	 * @param failures The recorded failure descriptions.
	 * @param total The total number of checks that were run.
	 */
	public static void report(List<String> failures, int total){
		final int maxReported = 8;
		if(!failures.isEmpty()){
			StringBuilder message = new StringBuilder();
			message.append(failures.size()).append(" of ").append(total).append(" checks failed");
			for(int i = 0; i < Math.min(maxReported, failures.size()); i++){
				message.append(System.lineSeparator()).append("  ").append(failures.get(i));
			}
			if(failures.size() > maxReported){
				message.append(System.lineSeparator()).append("  ... and ").append(failures.size() - maxReported).append(" more");
			}
			fail(message.toString());
		}
	}
}
