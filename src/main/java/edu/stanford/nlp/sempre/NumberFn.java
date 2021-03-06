package edu.stanford.nlp.sempre;

import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a string to a number (double).
 *
 * @author Percy Liang
 */
public class NumberFn extends SemanticFn
{
	public static class Options
	{
		@Option(gloss = "Omit units")
		public boolean unitless = false;
		@Option(gloss = "Also test numbers by try converting to float (instead of using NER tags)")
		public boolean alsoTestByConversion = false;
		@Option(gloss = "Also test numbers by applying NER on just the phrase")
		public boolean alsoTestByIsolatedNER = false;
		@Option(gloss = "range of allowed numbers. e.g. null: no limits, Lists.newArrayList(0,100): 0-100 inclusive")
		public List<Double> allowedRange = null;
	}

	public static Options opts = new Options();

	private List<String> requests; // List of types of fields to get (e.g., NUMBER)

	private boolean request(final String req)
	{
		return requests == null || requests.contains(req);
	}

	@Override
	public void init(final LispTree tree)
	{
		super.init(tree);
		if (tree.children.size() > 1)
		{
			requests = new ArrayList<>();
			for (int i = 1; i < tree.children.size(); i++)
				requests.add(tree.child(1).value);
		}
	}

	// TODO(pliang): handle measurements too (e.g., 3cm)
	@Override
	public DerivationStream call(final Example ex, final Callable c)
	{
		return new SingleDerivationStream()
		{
			@Override
			public Derivation createDerivation()
			{
				// Test using NER span
				Derivation deriv = check(ex.languageInfo, c.getStart(), c.getEnd());
				if (deriv != null)
					return deriv;

				// Test by converting string to number directly (don't look at NER)
				if (opts.alsoTestByConversion && request("NUMBER") & c.getEnd() - c.getStart() == 1)
				{
					final String value = ex.languageInfo.tokens.get(c.getStart());
					if (value != null)
						try
						{
							final NumberValue numberValue = new NumberValue(Double.parseDouble(value));
							final SemType type = numberValue._value == (int) numberValue._value ? SemType.intType : SemType.floatType;
							return new Derivation.Builder().withCallable(c).formula(new ValueFormula<>(numberValue)).type(type).createDerivation();
						}
						catch (@SuppressWarnings("unused") final NumberFormatException e)
						{
							// Don't issue warnings; most spans are not numbers
						}
				}

				// Test by applying NER on just the phrase
				if (opts.alsoTestByIsolatedNER)
				{
					final String phrase = ex.phraseString(c.getStart(), c.getEnd());
					final LanguageInfo languageInfo = LanguageAnalyzer.getSingleton().analyze(phrase);
					deriv = check(languageInfo, 0, languageInfo.numTokens());
					if (deriv != null)
						return deriv;
				}

				return null;
			}

			public Derivation check(final LanguageInfo languageInfo, final int start, final int end)
			{
				// Numbers: If it is an integer, set its type to integer.  Otherwise, use float.
				if (request("NUMBER"))
				{
					final String value = languageInfo.getNormalizedNerSpan("NUMBER", start, end);
					if (value != null)
						try
						{
							final NumberValue numberValue = new NumberValue(Double.parseDouble(value));
							if (opts.allowedRange != null)
								if (numberValue._value < opts.allowedRange.get(0) || numberValue._value > opts.allowedRange.get(1))
								{
									LogInfo.warnings("NumberFn: %f is outside of the allowed range %s", numberValue._value, opts.allowedRange);
									return null;
								}

							final SemType type = numberValue._value == (int) numberValue._value ? SemType.intType : SemType.floatType;
							return new Derivation.Builder().withCallable(c).formula(new ValueFormula<>(numberValue)).type(type).createDerivation();
						}
						catch (@SuppressWarnings("unused") final NumberFormatException e)
						{
							LogInfo.warnings("NumberFn: Cannot convert NerSpan \"%s\" to a number", value);
						}
				}

				// Ordinals
				if (request("ORDINAL"))
				{
					final String value = languageInfo.getNormalizedNerSpan("ORDINAL", start, end);
					if (value != null)
						try
						{
							final NumberValue numberValue = opts.unitless ? new NumberValue(Double.parseDouble(value)) : new NumberValue(Double.parseDouble(value), "fb:en.ordinal_number");
							final SemType type = SemType.intType;
							return new Derivation.Builder().withCallable(c).formula(new ValueFormula<>(numberValue)).type(type).createDerivation();
						}
						catch (@SuppressWarnings("unused") final NumberFormatException e)
						{
							LogInfo.warnings("NumberFn: Cannot convert NerSpan \"%s\" to a number", value);
						}
				}

				// Percents
				if (request("PERCENT"))
				{
					final String value = languageInfo.getNormalizedNerSpan("PERCENT", start, end);
					if (value != null)
						try
						{
							final NumberValue numberValue = opts.unitless ? new NumberValue(Double.parseDouble(value.substring(1))) : new NumberValue(0.01 * Double.parseDouble(value.substring(1)));
							final SemType type = SemType.floatType;
							return new Derivation.Builder().withCallable(c).formula(new ValueFormula<>(numberValue)).type(type).createDerivation();
						}
						catch (@SuppressWarnings("unused") final NumberFormatException e)
						{
							LogInfo.warnings("NumberFn: Cannot convert NerSpan \"%s\" to a number", value);
						}
				}

				// Money
				if (request("MONEY"))
				{
					final String value = languageInfo.getNormalizedNerSpan("MONEY", start, end);
					if (value != null)
						try
						{
							final NumberValue numberValue = opts.unitless ? new NumberValue(Double.parseDouble(value.substring(1))) : new NumberValue(Double.parseDouble(value.substring(1)), "fb:en.dollar");
							final SemType type = SemType.floatType;
							return new Derivation.Builder().withCallable(c).formula(new ValueFormula<>(numberValue)).type(type).createDerivation();
						}
						catch (@SuppressWarnings("unused") final NumberFormatException e)
						{
							LogInfo.warnings("NumberFn: Cannot convert NerSpan \"%s\" to a number", value);
						}
				}

				return null;
			}
		};
	}
}
