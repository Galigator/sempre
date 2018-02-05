package edu.stanford.nlp.sempre.tables.serialize;

import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Params;
import edu.stanford.nlp.sempre.Parser;
import edu.stanford.nlp.sempre.ParserState;
import edu.stanford.nlp.sempre.tables.alter.TurkEquivalentClassInfo;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.Pair;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser used when loading serialized data. SerializedParser assumes that all candidate derivations were already computed in the dump file. So the parser skips
 * the parsing step and just load the candidates
 *
 * @author ppasupat
 */
public class SerializedParser extends Parser
{
	public static class Options
	{
		// Must be a gzip file or a directory of gzip files
		@Option(gloss = "Path for formula annotation")
		public String annotationPath = null;
		// Skip the example if some criterion is met
		@Option(gloss = "(optional) Path for turk-info.tsv")
		public String turkInfoPath = null;
		@Option(gloss = "(If turkInfoPath is present) Maximum number of numClassesMatched")
		public int maxNumClassesMatched = 50;
		@Option(gloss = "(If turkInfoPath is present) Maximum number of numDerivsMatched")
		public int maxNumDerivsMatched = 50000;
	}

	public static Options opts = new Options();

	// Map from ID string to LazyLoadedExampleList and example index.
	protected Map<String, Pair<LazyLoadedExampleList, Integer>> idToSerializedIndex = null;
	// Map from ID string to TurkEquivalentClassInfo.
	protected Map<String, TurkEquivalentClassInfo> idToTurkInfo = null;

	public SerializedParser(final Spec spec)
	{
		super(spec);
		if (opts.annotationPath != null)
			readSerializedFile(opts.annotationPath);
		if (opts.turkInfoPath != null)
		{
			LogInfo.begin_track("Reading Turk info from %s", opts.turkInfoPath);
			idToTurkInfo = TurkEquivalentClassInfo.fromFile(opts.turkInfoPath);
			LogInfo.end_track();
		}
	}

	// Don't do it.
	@Override
	protected void computeCatUnaryRules()
	{
		catUnaryRules = Collections.emptyList();
	};

	protected void readSerializedFile(final String annotationPath)
	{
		idToSerializedIndex = new HashMap<>();
		final SerializedDataset dataset = new SerializedDataset();
		if (new File(annotationPath).isDirectory())
			dataset.readDir(annotationPath);
		else
			dataset.read("annotated", annotationPath);
		for (final String group : dataset.groups())
		{
			final LazyLoadedExampleList examples = dataset.examples(group);
			final List<String> ids = examples.getAllIds();
			for (int i = 0; i < ids.size(); i++)
				idToSerializedIndex.put(ids.get(i), new Pair<>(examples, i));
		}
	}

	@Override
	public ParserState newParserState(final Params params, final Example ex, final boolean computeExpectedCounts)
	{
		return new SerializedParserState(this, params, ex, computeExpectedCounts);
	}

}

class SerializedParserState extends ParserState
{

	public SerializedParserState(final Parser parser, final Params params, final Example ex, final boolean computeExpectedCounts)
	{
		super(parser, params, ex, computeExpectedCounts);
	}

	@Override
	public void infer()
	{
		final SerializedParser parser = (SerializedParser) this.parser;
		if (parser.idToTurkInfo != null)
		{
			final TurkEquivalentClassInfo info = parser.idToTurkInfo.get(ex.id);
			if (info != null)
			{
				if (info.numClassesMatched > SerializedParser.opts.maxNumClassesMatched)
				{
					LogInfo.logs("Skipped %s since numClassesMatched = %d > %d", ex.id, info.numClassesMatched, SerializedParser.opts.maxNumClassesMatched);
					if (computeExpectedCounts)
						expectedCounts = new HashMap<>();
					return;
				}
				if (info.numDerivsMatched > SerializedParser.opts.maxNumDerivsMatched)
				{
					LogInfo.logs("Skipped %s since numDerivsMatched = %d > %d", ex.id, info.numDerivsMatched, SerializedParser.opts.maxNumDerivsMatched);
					if (computeExpectedCounts)
						expectedCounts = new HashMap<>();
					return;
				}
			}
		}
		if (parser.idToSerializedIndex != null)
		{
			final Pair<LazyLoadedExampleList, Integer> pair = parser.idToSerializedIndex.get(ex.id);
			if (pair != null)
			{
				final Example annotatedEx = pair.getFirst().get(pair.getSecond());
				for (final Derivation deriv : annotatedEx.predDerivations)
				{
					featurizeAndScoreDerivation(deriv);
					predDerivations.add(deriv);
				}
			}
		}
		else
		{
			// Assume that the example already has all derivations.
			if (ex.predDerivations == null)
				ex.predDerivations = new ArrayList<>();
			for (final Derivation deriv : ex.predDerivations)
			{
				featurizeAndScoreDerivation(deriv);
				predDerivations.add(deriv);
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
