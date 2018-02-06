package edu.stanford.nlp.sempre.tables.features;

import edu.stanford.nlp.sempre.AggregateFormula;
import edu.stanford.nlp.sempre.ArithmeticFormula;
import edu.stanford.nlp.sempre.CanonicalNames;
import edu.stanford.nlp.sempre.ContextValue;
import edu.stanford.nlp.sempre.DateValue;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.LambdaFormula;
import edu.stanford.nlp.sempre.LanguageAnalyzer;
import edu.stanford.nlp.sempre.LanguageInfo;
import edu.stanford.nlp.sempre.MarkFormula;
import edu.stanford.nlp.sempre.MergeFormula;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.NumberValue;
import edu.stanford.nlp.sempre.ReverseFormula;
import edu.stanford.nlp.sempre.StringValue;
import edu.stanford.nlp.sempre.SuperlativeFormula;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueFormula;
import edu.stanford.nlp.sempre.VariableFormula;
import edu.stanford.nlp.sempre.tables.ScopedFormula;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import fig.basic.Fmt;
import fig.basic.Option;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Represents a predicate in the formula. Also contains additional information such as type and original string.
 *
 * @author ppasupat
 */
public class PredicateInfo
{
	public static class Options
	{
		@Option(gloss = "Allow repreated predicates")
		public boolean allowRepeats = false;
		@Option(gloss = "Maximum length of predicate string")
		public int maxPredicateLength = 40;
	}

	public static Options opts = new Options();

	static enum PredicateType
	{
		KEYWORD, ENTITY, BINARY
	};

	public final String predicate;
	public final String originalString;
	public final PredicateType type;

	public PredicateInfo(final String predicate, final ContextValue context)
	{
		this.predicate = predicate;
		type = inferType(predicate);
		final String s = getOriginalString(predicate, context);
		originalString = s == null ? null : s.toLowerCase();
	}

	public static PredicateType inferType(String predicate)
	{
		if (predicate.charAt(0) == '!')
			predicate = predicate.substring(1);
		if (predicate.startsWith(CanonicalNames.PREFIX))
		{
			if (CanonicalNames.isUnary(predicate))
				return PredicateType.ENTITY;
			else
				if (CanonicalNames.isBinary(predicate))
					return PredicateType.BINARY;
				else
					throw new RuntimeException("Unrecognized predicate: " + predicate);
		}
		else
			return PredicateType.KEYWORD;
	}

	@Override
	public String toString()
	{
		return predicate + "(" + originalString + ")";
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || !(o instanceof PredicateInfo))
			return false;
		return predicate.equals(((PredicateInfo) o).predicate);
	}

	@Override
	public int hashCode()
	{
		return predicate.hashCode();
	}

	// ============================================================
	// Get original strings and lemmas
	// ============================================================

	// Lemma cache
	private static final Map<String, String> lemmaCache = new HashMap<>();

	// Helper function: get lemma form
	public static synchronized String getLemma(final String s)
	{
		if (s == null || s.trim().isEmpty())
			return null;
		String lemma = lemmaCache.get(s);
		if (lemma == null)
		{
			final LanguageInfo langInfo = LanguageAnalyzer.getSingleton().analyze(s);
			lemma = langInfo.numTokens() == 0 ? "" : langInfo.lemmaPhrase(0, langInfo.numTokens());
			lemmaCache.put(s, lemma);
		}
		return lemma;
	}

	// Helper function: get original string from the table
	public static String getOriginalString(final String predicate, final Example ex)
	{
		return getOriginalString(predicate, ex.context);
	}

	//Helper function: get original string from the table
	public static String getOriginalString(final String predicate, final ContextValue context)
	{
		if (context == null || context.graph == null || !(context.graph instanceof TableKnowledgeGraph))
			return null;
		return getOriginalString(predicate, (TableKnowledgeGraph) context.graph);
	}

	// Helper function: get original string from the table
	public static String getOriginalString(final String predicate, final TableKnowledgeGraph graph)
	{
		String s = graph.getOriginalString(predicate);
		s = getLemma(s);
		if (s != null && s.trim().isEmpty())
			s = null;
		return s;
	}

	// ============================================================
	// Get the list of all PredicateInfos
	// ============================================================

	public static List<PredicateInfo> getPredicateInfos(final Example ex, final Derivation deriv)
	{
		Collection<PredicateInfo> predicates;
		final Formula formula = deriv.formula;
		final FormulaTraverser traverser = new FormulaTraverser(ex);
		traverser.traverse(formula);
		predicates = traverser.predicates;
		final List<PredicateInfo> answer = new ArrayList<>();
		for (final PredicateInfo p : predicates)
			if (p.originalString == null || p.originalString.length() <= opts.maxPredicateLength)
				answer.add(p);
		return answer;
	}

	private static class FormulaTraverser
	{
		public final Collection<PredicateInfo> predicates;
		private final ContextValue context;

		public FormulaTraverser(final Example ex)
		{
			predicates = opts.allowRepeats ? new ArrayList<>() : new HashSet<>();
			context = ex.context;
		}

		public void traverse(final Formula formula)
		{
			if (formula instanceof ValueFormula)
			{
				final Value value = ((ValueFormula<?>) formula).value;
				if (value instanceof NumberValue)
				{
					final NumberValue number = (NumberValue) value;
					predicates.add(new PredicateInfo("number", context));
					predicates.add(new PredicateInfo(Fmt.D(number._value), context));

				}
				else
					if (value instanceof DateValue)
					{
						final DateValue date = (DateValue) value;
						predicates.add(new PredicateInfo("date", context));
						// Use prefixes to distinguish from numbers
						predicates.add(new PredicateInfo("y:" + Fmt.D(date.year), context));
						predicates.add(new PredicateInfo("m:" + Fmt.D(date.month), context));
						predicates.add(new PredicateInfo("d:" + Fmt.D(date.day), context));

					}
					else
						if (value instanceof StringValue)
						{
							final StringValue string = (StringValue) value;
							predicates.add(new PredicateInfo("string", context));
							predicates.add(new PredicateInfo(string.value, context));

						}
						else
							if (value instanceof NameValue)
							{
								final NameValue name = (NameValue) value;
								final String id = name._id;
								predicates.add(new PredicateInfo(id, context));
							}

			}
			else
				if (formula instanceof JoinFormula)
				{
					final JoinFormula join = (JoinFormula) formula;
					traverse(join.relation);
					traverse(join.child);

				}
				else
					if (formula instanceof ReverseFormula)
					{
						final ReverseFormula reverse = (ReverseFormula) formula;
						if (reverse.child instanceof ValueFormula<?> && ((ValueFormula<?>) reverse.child).value instanceof NameValue)
						{
							String id = ((NameValue) ((ValueFormula<?>) reverse.child).value)._id;
							id = id.startsWith("!") ? id.substring(1) : "!" + id;
							traverse(new ValueFormula<>(new NameValue(id)));
						}
						else
						{
							predicates.add(new PredicateInfo("reverse", context));
							traverse(reverse.child);
						}

					}
					else
						if (formula instanceof MergeFormula)
						{
							final MergeFormula merge = (MergeFormula) formula;
							predicates.add(new PredicateInfo(merge.mode.toString(), context));
							traverse(merge.child1);
							traverse(merge.child2);

						}
						else
							if (formula instanceof AggregateFormula)
							{
								final AggregateFormula aggregate = (AggregateFormula) formula;
								predicates.add(new PredicateInfo(aggregate.mode.toString(), context));
								traverse(aggregate.child);

							}
							else
								if (formula instanceof SuperlativeFormula)
								{
									final SuperlativeFormula superlative = (SuperlativeFormula) formula;
									predicates.add(new PredicateInfo(superlative.mode.toString(), context));
									// Skip the "(number 1) (number 1)" part
									traverse(superlative.head);
									traverse(superlative.relation);

								}
								else
									if (formula instanceof ArithmeticFormula)
									{
										final ArithmeticFormula arithmetic = (ArithmeticFormula) formula;
										predicates.add(new PredicateInfo(arithmetic.mode.toString(), context));
										traverse(arithmetic.child1);
										traverse(arithmetic.child2);

									}
									else
										if (formula instanceof VariableFormula)
										{
											// Skip variables

										}
										else
											if (formula instanceof MarkFormula)
											{
												final MarkFormula mark = (MarkFormula) formula;
												predicates.add(new PredicateInfo("mark", context));
												// Skip variable
												traverse(mark.body);

											}
											else
												if (formula instanceof LambdaFormula)
												{
													final LambdaFormula lambda = (LambdaFormula) formula;
													predicates.add(new PredicateInfo("lambda", context));
													// Skip variable
													traverse(lambda.body);

												}
												else
													if (formula instanceof ScopedFormula)
													{
														final ScopedFormula scoped = (ScopedFormula) formula;
														traverse(scoped.head);
														Formula relation = scoped.relation;
														if (relation instanceof LambdaFormula)
															relation = ((LambdaFormula) relation).body;
														traverse(relation);

													}
													else
														throw new RuntimeException("[PredicateInfo] Cannot handle formula " + formula);
		}
	}
}
