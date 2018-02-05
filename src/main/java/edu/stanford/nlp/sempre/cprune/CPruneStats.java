package edu.stanford.nlp.sempre.cprune;

/**
 * Stores various statistic.
 */
public class CPruneStats
{
	public String iter;
	public int totalExplore = 0;
	public int successfulExplore = 0;
	public int totalExploit = 0;
	public int successfulExploit = 0;

	public void reset(final String iter_)
	{
		iter = iter_;
		totalExplore = 0;
		successfulExplore = 0;
		totalExploit = 0;
		successfulExploit = 0;
	}
}
