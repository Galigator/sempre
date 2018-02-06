package edu.stanford.nlp.sempre;

import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A parser that mixes the derivation lists from other parsers.
 *
 * @author ppasupat
 */
public class MixParser extends Parser
{
	public static class Options
	{
		@Option(gloss = "verbosity")
		public int verbose = 1;

		/**
		 * Syntax: [className]:[options] - className also includes the package name (BeamParser, FloatingParser, tables.dpd.DPDParser, ...) - options is a
		 * comma-separated list of [group] or [group]-[iter1]-[iter2]-... [iter1], [iter2], ... are iteration indices (0-based), "xc" (computing expected
		 * counts), "nxc" (not computing expected counts), index + "xc", or index + "nxc" Example: Using -MixParser.parsers FloatingParser
		 * tables.serialize.SerializedParser:train-0-2-3,dev will cause derivations from SerializedParser to be mixed in during all dev iterations and training
		 * iterations 0, 2, and 3.
		 */
		@Option(gloss = "list of parsers to use along with options")
		public List<String> parsers = new ArrayList<>();
	}

	public static Options opts = new Options();

	final List<Pair<Parser, MixParserOption>> parsers;
	int iter, numIters;
	String group;

	public MixParser(final Spec spec)
	{
		super(spec);
		parsers = new ArrayList<>();
		for (final String parserAndOptions : opts.parsers)
		{
			if (opts.verbose >= 1)
				LogInfo.logs("Adding parser %s", parserAndOptions);
			final String[] tokens = parserAndOptions.split(":");
			if (tokens.length > 2)
				throw new SempreError("Invalid parser options: " + parserAndOptions);
			final String parserName = tokens[0];
			Parser parser;
			try
			{
				final Class<?> parserClass = Class.forName(SempreUtils.resolveClassName(parserName));
				parser = (Parser) parserClass.getConstructor(spec.getClass()).newInstance(spec);
			}
			catch (final ClassNotFoundException e1)
			{
				throw new SempreError("Illegal parser: " + parserName, e1);
			}
			catch (final Exception e)
			{
				e.printStackTrace();
				throw new SempreError("Error while instantiating parser: " + parserName + "\n" + e);
			}
			if (tokens.length > 1)
				parsers.add(new Pair<>(parser, new MixParserOption(this, tokens[1])));
			else
				parsers.add(new Pair<>(parser, new MixParserOption(this)));
		}
	}

	// Don't do it.
	@Override
	protected void computeCatUnaryRules()
	{
		catUnaryRules = Collections.emptyList();
	}

	@Override
	public ParserState newParserState(final Params params, final Example ex, final boolean computeExpectedCounts)
	{
		return new MixParserState(this, params, ex, computeExpectedCounts);
	}

	@Override
	public void onBeginDataGroup(final int iter_, final int numIters_, final String group_)
	{
		iter = iter_;
		numIters = numIters_;
		group = group_;
	}
}

class MixParserOption
{
	private final MixParser mixParser;
	private boolean allowedAll = false;
	private final List<Pair<String, String>> allowedGroupsAndIter = new ArrayList<>();

	public MixParserOption(final MixParser mixParser_)
	{
		mixParser = mixParser_;
		allowedAll = true; // Allow in all groups
	}

	public MixParserOption(final MixParser mixParser_, final String optionString)
	{
		mixParser = mixParser_;
		final String[] tokens = optionString.split(",");
		for (final String option : tokens)
		{
			final String[] subtokens = option.split("-");
			if (subtokens.length == 1)
				allowedGroupsAndIter.add(new Pair<>(subtokens[0], "all"));
			else
				for (int i = 1; i < subtokens.length; i++)
				{
					if (!Pattern.matches("^([0-9]*(n?xc)?)$", subtokens[i]))
						throw new RuntimeException("Invalid iteration options: " + subtokens[i]);
					allowedGroupsAndIter.add(new Pair<>(subtokens[0], subtokens[i]));
				}
		}
	}

	public boolean isAllowed(final boolean computeExpectedCounts)
	{
		if (allowedAll)
			return true;
		final String xcFlag = computeExpectedCounts ? "xc" : "nxc";
		return allowedGroupsAndIter.contains(new Pair<>(mixParser.group, "all")) || allowedGroupsAndIter.contains(new Pair<>(mixParser.group, "" + mixParser.iter)) || allowedGroupsAndIter.contains(new Pair<>(mixParser.group, xcFlag)) || allowedGroupsAndIter.contains(new Pair<>(mixParser.group, "" + mixParser.iter + xcFlag));
	}
}

class MixParserState extends ParserState
{

	public MixParserState(final Parser parser_, final Params params_, final Example ex_, final boolean computeExpectedCounts_)
	{
		super(parser_, params_, ex_, computeExpectedCounts_);
	}

	@Override
	public void infer()
	{
		for (final Pair<Parser, MixParserOption> pair : ((MixParser) parser).parsers)
		{
			if (!pair.getSecond().isAllowed(computeExpectedCounts))
			{
				if (MixParser.opts.verbose >= 1)
					LogInfo.logs("Skipping %s", pair.getFirst().getClass().getSimpleName());
				continue;
			}
			if (MixParser.opts.verbose >= 1)
				LogInfo.begin_track("Using %s", pair.getFirst().getClass().getSimpleName());
			final ParserState parserState = pair.getFirst().newParserState(params, ex, false);
			parserState.infer();
			predDerivations.addAll(parserState.predDerivations);
			if (MixParser.opts.verbose >= 1)
			{
				LogInfo.logs("Number of derivations: %d", parserState.predDerivations.size());
				LogInfo.end_track();
			}

		}
		ensureExecuted();
		if (computeExpectedCounts)
		{
			expectedCounts = new HashMap<>();
			ParserState.computeExpectedCounts(predDerivations, expectedCounts);
		}
	}

}
