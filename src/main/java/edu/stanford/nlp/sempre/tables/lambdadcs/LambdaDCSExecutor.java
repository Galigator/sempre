package edu.stanford.nlp.sempre.tables.lambdadcs;

import edu.stanford.nlp.sempre.AggregateFormula;
import edu.stanford.nlp.sempre.ArithmeticFormula;
import edu.stanford.nlp.sempre.BooleanValue;
import edu.stanford.nlp.sempre.CanonicalNames;
import edu.stanford.nlp.sempre.ContextValue;
import edu.stanford.nlp.sempre.DateValue;
import edu.stanford.nlp.sempre.ErrorValue;
import edu.stanford.nlp.sempre.Executor;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.KnowledgeGraph;
import edu.stanford.nlp.sempre.LambdaFormula;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.MarkFormula;
import edu.stanford.nlp.sempre.MergeFormula;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.PairListValue;
import edu.stanford.nlp.sempre.ReverseFormula;
import edu.stanford.nlp.sempre.StringValue;
import edu.stanford.nlp.sempre.SuperlativeFormula;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueFormula;
import edu.stanford.nlp.sempre.VariableFormula;
import edu.stanford.nlp.sempre.tables.ScopedFormula;
import edu.stanford.nlp.sempre.tables.ScopedValue;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import edu.stanford.nlp.sempre.tables.lambdadcs.LambdaDCSException.Type;
import fig.basic.Evaluation;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import fig.basic.StopWatch;
import java.util.ArrayList;
import java.util.List;

/**
 * Execute a Formula on the given KnowledgeGraph instance.
 *
 * @author ppasupat
 */
public class LambdaDCSExecutor extends Executor
{
	public static class Options
	{
		@Option(gloss = "Verbosity")
		public int verbose = 0;
		@Option(gloss = "Use caching")
		public boolean useCache = true;
		@Option(gloss = "Sort the resulting values (may slow down execution)")
		public boolean sortResults = true;
		@Option(gloss = "Allow the return value to be an implicit value")
		public boolean allowImplicitValues = true;
		@Option(gloss = "Allow the root formula to be a binary")
		public boolean executeBinary = false;
		@Option(gloss = "Generic DateValue: (date -1 5 -1) in formula also matches (date -1 5 12)")
		public boolean genericDateValue = false;
		@Option(gloss = "If the result is empty, return an ErrorValue instead of an empty ListValue")
		public boolean failOnEmptyLists = false;
		@Option(gloss = "Return all ties on (argmax 1 1 ...) and (argmin 1 1 ...)")
		public boolean superlativesReturnAllTopTies = true;
		@Option(gloss = "Aggregates (sum, avg, max, min) throw an error on empty lists")
		public boolean aggregatesFailOnEmptyLists = false;
		@Option(gloss = "Superlatives (argmax, argmin) throw an error on empty lists")
		public boolean superlativesFailOnEmptyLists = false;
		@Option(gloss = "Arithmetics (+, -, *, /) throw an error on empty lists")
		public boolean arithmeticsFailOnEmptyLists = false;
		@Option(gloss = "Arithmetics (+, -, *, /) throw an error when both operants have > 1 values")
		public boolean arithmeticsFailOnMultipleElements = true;
	}

	public static Options opts = new Options();

	public final Evaluation stats = new Evaluation();

	@Override
	public Response execute(Formula formula, final ContextValue context)
	{
		LambdaDCSCoreLogic logic;
		if (opts.verbose < 3)
			logic = new LambdaDCSCoreLogic(context, stats);
		else
			logic = new LambdaDCSCoreLogicWithVerbosity(context, stats);
		final StopWatch stopWatch = new StopWatch();
		stopWatch.start();
		formula = Formulas.betaReduction(formula);
		final Value answer = logic.execute(formula);
		stopWatch.stop();
		stats.addCumulative("execTime", stopWatch.ms);
		if (stopWatch.ms >= 10 && opts.verbose >= 1)
			LogInfo.logs("long time (%d ms): %s => %s", stopWatch.ms, formula, answer);
		return new Response(answer);
	}

	public void summarize()
	{
		LogInfo.begin_track("LambdaDCSExecutor: summarize");
		stats.logStats("LambdaDCSExecutor");
		LogInfo.end_track();
	}
}

// ============================================================
// Execution
// ============================================================

/**
 * Main logic of Lambda DCS Executor. Find the denotation of a formula (logical form) with respect to the given knowledge graph. Assume that the denotation is
 * either a unary or a binary, and the final denotation is a unary. Both unaries and binaries are lists (not sets). However, the following formula types will
 * treat them as sets: - and, or - count (= count the number of distinct values) Note that (and (!weight (@type @row)) (@p.num (> (number 90)))) may give a
 * wrong answer, but it can be rewritten as (!weight (and (@type @row) (weight (@p.num (> (number 90))))))
 *
 * @author ppasupat
 */
class LambdaDCSCoreLogic
{

	// Note: STAR does not work well with type checking
	static final NameValue STAR = new NameValue("*");

	final KnowledgeGraph graph;
	final Evaluation stats;
	ExecutorCache cache;

	public LambdaDCSCoreLogic(final ContextValue context, final Evaluation stats)
	{
		graph = context.graph;
		this.stats = stats;
		if (graph == null)
			throw new RuntimeException("Cannot call LambdaDCSExecutor when context graph is null");
		if (graph instanceof TableKnowledgeGraph)
			cache = ((TableKnowledgeGraph) graph).executorCache;
		if (cache == null)
			cache = ExecutorCache.singleton;
	}

	public Value execute(final Formula formula)
	{
		if (LambdaDCSExecutor.opts.verbose >= 2)
			LogInfo.logs("%s", formula);
		Value answer;
		// Special case: ScopedFormula
		if (formula instanceof ScopedFormula)
		{
			final ScopedFormula scoped = (ScopedFormula) formula;
			try
			{
				// Head
				final UnaryDenotation head = (UnaryDenotation) computeUnary(scoped.head, TypeHint.UNRESTRICTED_UNARY);
				if (head.size() == Integer.MAX_VALUE)
					throw new LambdaDCSException(Type.infiniteList, "Cannot have an infinite head: ", head);
				final ListValue headValue = ((ListValue) head.toValue()).getUnique();
				// Relation
				final LambdaFormula lambdaRelation = (LambdaFormula) scoped.relation;
				final List<Pair<Value, Value>> collapsedPairs = new ArrayList<>();
				for (final Value varValue : headValue.values)
				{
					final UnaryDenotation results = (UnaryDenotation) computeUnary(lambdaRelation.body, TypeHint.UNRESTRICTED_UNARY.withVar(lambdaRelation.var, varValue));
					if (LambdaDCSExecutor.opts.useCache)
						cache.put(graph, new Pair<>(lambdaRelation.body, new Pair<>(lambdaRelation.var, varValue)), results);
					if (!results.isEmpty())
						collapsedPairs.add(new Pair<>(varValue, results.toValue()));
				}
				final Value relationValue = new PairListValue(collapsedPairs);
				answer = new ScopedValue(headValue, relationValue);
			}
			catch (final LambdaDCSException e)
			{
				answer = new ErrorValue(e.toString());
			}
		}
		else
			// Unaries and Binaries
			try
			{
				final Unarylike denotation = computeUnary(formula, TypeHint.UNRESTRICTED_UNARY);
				if (LambdaDCSExecutor.opts.useCache)
					cache.put(graph, formula, denotation);
				answer = denotation.toValue();
				if (answer instanceof ListValue)
				{
					answer = ((ListValue) answer).getUnique();
					if (LambdaDCSExecutor.opts.failOnEmptyLists && ((ListValue) answer).values.isEmpty())
						answer = ErrorValue.empty;
				}
			}
			catch (final LambdaDCSException e)
			{
				if (LambdaDCSExecutor.opts.executeBinary && e.type == Type.notUnary)
					try
					{
						final Binarylike denotation = computeBinary(formula, TypeHint.UNRESTRICTED_BINARY);
						answer = denotation.toValue();
					}
					catch (final LambdaDCSException e2)
					{
						answer = new ErrorValue(e2.toString());
					}
				else
					answer = new ErrorValue(e.toString());
			}
		if (LambdaDCSExecutor.opts.verbose >= 2)
			LogInfo.logs("=> %s", answer);
		return answer;
	}

	public Unarylike computeUnary(final Formula formula, final UnarylikeTypeHint typeHint)
	{
		assert typeHint != null;
		if (formula instanceof LambdaFormula)
			throw new LambdaDCSException(Type.notUnary, "[Unary] Not a unary %s", formula);

		if (LambdaDCSExecutor.opts.useCache)
		{
			Object object = cache.get(graph, formula);
			if (object != null && object instanceof Unarylike)
			{
				stats.addCumulative("normalCacheHit", true);
				stats.addCumulative("scopedCacheHit", false);
				return (Unarylike) object;
			}
			else
				if (typeHint.getIfSingleVar() != null)
				{
					object = cache.get(graph, new Pair<>(formula, typeHint.getIfSingleVar()));
					if (object != null && object instanceof Unarylike)
					{
						stats.addCumulative("normalCacheHit", false);
						stats.addCumulative("scopedCacheHit", true);
						return (Unarylike) object;
					}
				}
			stats.addCumulative("normalCacheHit", false);
			stats.addCumulative("scopedCacheHit", false);
		}

		if (formula instanceof ValueFormula)
		{
			// ============================================================
			// ValueFormula
			// ============================================================
			Value value = ((ValueFormula<?>) formula).value;
			if (value instanceof BooleanValue || value instanceof NumberValue || value instanceof StringValue || value instanceof DateValue || value instanceof NameValue)
			{
				// Special case: *
				if (STAR.equals(value))
					return InfiniteUnaryDenotation.STAR_UNARY;
				// Special case: generic date
				if (LambdaDCSExecutor.opts.genericDateValue && value instanceof DateValue)
					return typeHint.applyBound(InfiniteUnaryDenotation.GenericDateUnaryDenotation.get((DateValue) value));
				// Rule out binaries
				if (CanonicalNames.isBinary(value) && LambdaDCSExecutor.opts.executeBinary)
					throw new LambdaDCSException(Type.notUnary, "[Unary] Binary value %s", formula);
				if (value instanceof NameValue && graph instanceof TableKnowledgeGraph)
					value = ((TableKnowledgeGraph) graph).getNameValueWithOriginalString((NameValue) value);
				// Other cases
				return typeHint.applyBound(new ExplicitUnaryDenotation(value));
			}

		}
		else
			if (formula instanceof VariableFormula)
			{
				// ============================================================
				// Variable
				// ============================================================
				final String name = ((VariableFormula) formula).name;
				final Value value = typeHint.get(name);
				if (value != null)
					return typeHint.applyBound(new ExplicitUnaryDenotation(value));
				// Could be a mapping
				if (name.equals(typeHint.getFreeVar()))
					return typeHint.applyBound(new MappingDenotation<>(name, PredicatePairList.IDENTITY));

			}
			else
				if (formula instanceof JoinFormula)
				{
					// ============================================================
					// JoinFormula
					// ============================================================
					final JoinFormula join = (JoinFormula) formula;
					try
					{
						// Compute unary, then join binary
						final Unarylike childD = computeUnary(join.child, typeHint.unrestrictedUnary());
						final Binarylike relationD = computeBinary(join.relation, typeHint.asFirstOfBinaryWithSecond(childD.range()));
						return typeHint.applyBound(DenotationUtils.genericJoin(relationD, childD));
					}
					catch (final LambdaDCSException e1)
					{
						try
						{
							// Compute binary, then join unary
							final Binarylike relationD = computeBinary(join.relation, typeHint.asFirstOfBinary());
							final Unarylike childUpperBound = relationD.joinOnValue(typeHint.upperBound);
							final Unarylike childD = computeUnary(join.child, typeHint.restrictedUnary(childUpperBound.range()));
							return typeHint.applyBound(DenotationUtils.genericJoin(relationD, childD));
						}
						catch (final LambdaDCSException e2)
						{
							final Type errorType = (e1.type == e2.type) ? e1.type : Type.unknown;
							throw new LambdaDCSException(errorType, "Cannot join | %s | %s", e1, e2);
						}
					}

				}
				else
					if (formula instanceof MergeFormula)
					{
						// ============================================================
						// Merge
						// ============================================================
						final MergeFormula merge = (MergeFormula) formula;
						try
						{
							final Unarylike child1D = computeUnary(merge.child1, typeHint);
							final Unarylike child2D = computeUnary(merge.child2, merge.mode == MergeFormula.Mode.and ? typeHint.restrict(child1D) : typeHint);
							return typeHint.applyBound(DenotationUtils.merge(child1D, child2D, merge.mode));
						}
						catch (final LambdaDCSException e1)
						{
							try
							{
								final Unarylike child2D = computeUnary(merge.child2, typeHint);
								final Unarylike child1D = computeUnary(merge.child1, merge.mode == MergeFormula.Mode.and ? typeHint.restrict(child2D) : typeHint);
								return typeHint.applyBound(DenotationUtils.merge(child2D, child1D, merge.mode));
							}
							catch (final LambdaDCSException e2)
							{
								final Type errorType = (e1.type == e2.type) ? e1.type : Type.unknown;
								throw new LambdaDCSException(errorType, "Cannot merge | %s | %s", e1, e2);
							}
						}

					}
					else
						if (formula instanceof AggregateFormula)
						{
							// ============================================================
							// Aggregate
							// ============================================================
							final AggregateFormula aggregate = (AggregateFormula) formula;
							final Unarylike childD = computeUnary(aggregate.child, typeHint.unrestrictedUnary());
							return typeHint.applyBound(childD.aggregate(aggregate.mode));

						}
						else
							if (formula instanceof SuperlativeFormula)
							{
								// ============================================================
								// Superlative
								// ============================================================
								final SuperlativeFormula superlative = (SuperlativeFormula) formula;
								final int rank = DenotationUtils.getSinglePositiveInteger(computeUnary(superlative.rank, typeHint.unrestrictedUnary()).range());
								final int count = DenotationUtils.getSinglePositiveInteger(computeUnary(superlative.count, typeHint.unrestrictedUnary()).range());
								if (rank != 1 || count != 1)
									LogInfo.logs("Superlative WTF: %s | rank %d | count %d", formula, rank, count);
								final Unarylike headD = computeUnary(superlative.head, typeHint);
								Binarylike relationD;
								if (superlative.relation instanceof ReverseFormula)
									relationD = computeBinary(((ReverseFormula) superlative.relation).child, typeHint.restrictedBinary(null, headD.range()));
								else
									relationD = computeBinary(superlative.relation, typeHint.restrictedBinary(headD.range(), null)).reverse();
								return typeHint.applyBound(DenotationUtils.superlative(rank, count, headD, relationD, superlative.mode));

							}
							else
								if (formula instanceof ArithmeticFormula)
								{
									// ============================================================
									// Arithmetic
									// ============================================================
									final ArithmeticFormula arithmetic = (ArithmeticFormula) formula;
									final Unarylike child1D = computeUnary(arithmetic.child1, typeHint.unrestrictedUnary());
									final Unarylike child2D = computeUnary(arithmetic.child2, typeHint.unrestrictedUnary());
									return typeHint.applyBound(DenotationUtils.arithmetic(child1D, child2D, arithmetic.mode));

								}
								else
									if (formula instanceof MarkFormula)
									{
										// ============================================================
										// Mark
										// ============================================================
										final MarkFormula mark = (MarkFormula) formula;
										final LambdaFormula lambda = new LambdaFormula(mark.var, new MergeFormula(MergeFormula.Mode.and, new VariableFormula(mark.var), mark.body));
										final Binarylike lambdaD = computeBinary(lambda, typeHint.asFirstAndSecondOfBinary());
										return lambdaD.joinOnValue(InfiniteUnaryDenotation.STAR_UNARY);

									}
									else
										throw new LambdaDCSException(Type.notUnary, "[Unary] Not a valid unary %s", formula);

		// Catch-all error
		throw new LambdaDCSException(Type.unknown, "[Unary] Cannot handle formula %s", formula);
	}

	public Binarylike computeBinary(final Formula formula, final BinaryTypeHint typeHint)
	{
		assert typeHint != null;
		if (formula instanceof ValueFormula)
		{
			// ============================================================
			// ValueFormula
			// ============================================================
			final Value value = ((ValueFormula<?>) formula).value;
			// Must be a binary
			if (CanonicalNames.isBinary(value))
				return new BinaryDenotation<>(new PredicatePairList(value, graph));
			else
				throw new LambdaDCSException(Type.notBinary, "[Binary] Unary value %s", formula);

		}
		else
			if (formula instanceof ReverseFormula)
			{
				// ============================================================
				// Reverse
				// ============================================================
				final ReverseFormula reverse = (ReverseFormula) formula;
				final Binarylike childD = computeBinary(reverse.child, typeHint.reverse());
				return childD.reverse();

			}
			else
				if (formula instanceof LambdaFormula)
				{
					// ============================================================
					// Lambda
					// ============================================================
					// Note: The variable's values become the SECOND argument of the binary pairs
					final LambdaFormula lambda = (LambdaFormula) formula;
					final String var = lambda.var;
					// Assuming that the type hint has enough information ...
					try
					{
						final List<Pair<Value, Value>> pairs = new ArrayList<>();
						for (final Value varValue : typeHint.secondUpperBound)
						{
							final Unarylike results = computeUnary(lambda.body, typeHint.first().withVar(var, varValue));
							if (!(results instanceof UnaryDenotation))
								throw new LambdaDCSException(Type.notUnary, "Not a unary denotation: %s", results);
							for (final Value result : (UnaryDenotation) results)
								pairs.add(new Pair<>(result, varValue));
						}
						return new BinaryDenotation<>(new ExplicitPairList(pairs));
					}
					catch (final LambdaDCSException e)
					{
					}
					// Try the reverse
					try
					{
						final Formula reversed = Formulas.reverseFormula(lambda);
						if (reversed instanceof LambdaFormula && !reversed.equals(lambda))
						{
							final List<Pair<Value, Value>> pairs = new ArrayList<>();
							for (final Value varValue : typeHint.firstUpperBound)
							{
								final Unarylike results = computeUnary(((LambdaFormula) reversed).body, typeHint.second().withVar(var, varValue));
								if (!(results instanceof UnaryDenotation))
									throw new LambdaDCSException(Type.notUnary, "Not a unary denotation: %s", results);
								for (final Value result : (UnaryDenotation) results)
									pairs.add(new Pair<>(varValue, result));
							}
							return new BinaryDenotation<>(new ExplicitPairList(pairs));
						}
						else
							throw new LambdaDCSException(Type.unknown, "Cannot compute reverse of %s", lambda);
					}
					catch (final LambdaDCSException e)
					{
					}
					// Try to execute using a mapping.
					if (LambdaDCSExecutor.opts.executeBinary)
						try
						{
							final Unarylike mapping = computeUnary(lambda.body, typeHint.asMapping(lambda.var));
							if (mapping instanceof MappingDenotation)
								return ((MappingDenotation<?>) mapping).asBinary();
						}
						catch (final LambdaDCSException e)
						{
						}

				}
				else
					throw new LambdaDCSException(Type.notBinary, "[Binary] Not a valid binary %s", formula);

		// Catch-all error
		throw new LambdaDCSException(Type.unknown, "[Binary] Cannot handle formula %s", formula);
	}

}

// ============================================================
// Debug Print
// ============================================================

class LambdaDCSCoreLogicWithVerbosity extends LambdaDCSCoreLogic
{

	public LambdaDCSCoreLogicWithVerbosity(final ContextValue context, final Evaluation stats)
	{
		super(context, stats);
	}

	@Override
	public Unarylike computeUnary(final Formula formula, final UnarylikeTypeHint typeHint)
	{
		LogInfo.begin_track("UNARY %s [%s]", formula, typeHint);
		try
		{
			final Unarylike denotation = super.computeUnary(formula, typeHint);
			LogInfo.logs("%s", denotation);
			LogInfo.end_track();
			return denotation;
		}
		catch (final Exception e)
		{
			LogInfo.end_track();
			throw e;
		}
	}

	@Override
	public Binarylike computeBinary(final Formula formula, final BinaryTypeHint typeHint)
	{
		LogInfo.begin_track("BINARY %s [%s]", formula, typeHint);
		try
		{
			final Binarylike denotation = super.computeBinary(formula, typeHint);
			LogInfo.logs("%s", denotation);
			LogInfo.end_track();
			return denotation;
		}
		catch (final Exception e)
		{
			LogInfo.end_track();
			throw e;
		}
	}

}
