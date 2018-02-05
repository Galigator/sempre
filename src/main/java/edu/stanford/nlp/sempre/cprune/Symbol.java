package edu.stanford.nlp.sempre.cprune;

/**
 * Represents the leaf node of the parse tree. Any sub-derivation whose category is in CustomGrammar.baseCategories becomes a Symbol.
 */
public class Symbol implements Comparable<Symbol>
{
	String category;
	String formula;
	Integer frequency;
	Integer index;

	public Symbol(final String category, final String formula, final int frequency)
	{
		this.category = category;
		this.formula = formula;
		this.frequency = frequency;
	}

	public void computeIndex(final String referenceString)
	{
		index = referenceString.indexOf(formula);
		if (index < 0)
			index = Integer.MAX_VALUE;
	}

	@Override
	public int compareTo(final Symbol that)
	{
		return index.compareTo(that.index);
	}
}
