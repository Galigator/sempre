package edu.stanford.nlp.sempre;

import fig.basic.Option;
import fig.basic.Utils;

/**
 * Preprocess the targetValue of an example.
 *
 * @author ppasupat
 */
public abstract class TargetValuePreprocessor
{
	public static class Options
	{
		@Option
		public String targetValuePreprocessor = null;
	}

	public static Options opts = new Options();

	private static TargetValuePreprocessor singleton;

	public static TargetValuePreprocessor getSingleton()
	{
		if (singleton == null)
			if (opts.targetValuePreprocessor == null || opts.targetValuePreprocessor.isEmpty())
				singleton = new IdentityTargetValuePreprocessor();
			else
				singleton = (TargetValuePreprocessor) Utils.newInstanceHard(SempreUtils.resolveClassName(opts.targetValuePreprocessor));
		return singleton;
	}

	public static void setSingleton(final TargetValuePreprocessor processor)
	{
		singleton = processor;
	}

	public abstract Value preprocess(Value value, Example ex);

}

class IdentityTargetValuePreprocessor extends TargetValuePreprocessor
{
	public Value preprocess(final Value value, final Example ex)
	{
		return value;
	}
}
