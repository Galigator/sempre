package edu.stanford.nlp.sempre;

public interface FeatureMatcher
{
	boolean matches(String feature);
}

final class AllFeatureMatcher implements FeatureMatcher
{
	private AllFeatureMatcher()
	{
	}

	@Override
	public boolean matches(final String feature)
	{
		return true;
	}

	public static final AllFeatureMatcher matcher = new AllFeatureMatcher();
}

final class ExactFeatureMatcher implements FeatureMatcher
{
	private final String match;

	public ExactFeatureMatcher(final String match_)
	{
		match = match_;
	}

	@Override
	public boolean matches(final String feature)
	{
		return feature.equals(match);
	}
}

final class DenotationFeatureMatcher implements FeatureMatcher
{
	@Override
	public boolean matches(final String feature)
	{
		return feature.startsWith("denotation-size") || feature.startsWith("count-denotation-size");
	}

	public static final DenotationFeatureMatcher matcher = new DenotationFeatureMatcher();
}
