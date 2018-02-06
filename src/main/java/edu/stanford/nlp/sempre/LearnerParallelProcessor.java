package edu.stanford.nlp.sempre;

import fig.basic.Evaluation;
import fig.basic.LogInfo;
import fig.basic.Parallelizer;
import fig.basic.StopWatchSet;
import fig.exec.Execution;
import java.util.HashMap;
import java.util.Map;

/**
 * Parallel version of the Learner. Most of the codes are copied from the paraphrase package.
 *
 * @author ppasupat
 */
public class LearnerParallelProcessor implements Parallelizer.Processor<Example>
{

	private final Parser parser;
	private final String prefix;
	private final boolean computeExpectedCounts;
	private final Params params; // this is common to threads and should be synchronized
	private final Evaluation evaluation; // this is common to threads and should be synchronized

	public LearnerParallelProcessor(final Parser parser_, final Params params_, final String prefix_, final boolean computeExpectedCounts_, final Evaluation evaluation_)
	{
		prefix = prefix_;
		parser = parser_;
		computeExpectedCounts = computeExpectedCounts_;
		params = params_;
		evaluation = evaluation_;
	}

	@Override
	public void process(final Example ex, final int i, final int n)
	{
		LogInfo.begin_track_printAll("%s: example %s/%s: %s", prefix, i, n, ex.id);
		ex.log();
		Execution.putOutput("example", i);

		StopWatchSet.begin("Parser.parse");
		final ParserState state = parser.parse(params, ex, computeExpectedCounts);
		StopWatchSet.end();

		if (computeExpectedCounts)
		{
			final Map<String, Double> counts = new HashMap<>();
			SempreUtils.addToDoubleMap(counts, state.expectedCounts);

			// Gathered enough examples, update parameters
			StopWatchSet.begin("Learner.updateWeights");
			LogInfo.begin_track("Updating learner weights");
			if (Learner.opts.verbose >= 2)
				SempreUtils.logMap(counts, "gradient");
			double sum = 0;
			for (final double v : counts.values())
				sum += v * v;
			LogInfo.logs("L2 norm: %s", Math.sqrt(sum));
			synchronized (params)
			{
				params.update(counts);
			}
			counts.clear();
			LogInfo.end_track();
			StopWatchSet.end();
		}

		LogInfo.logs("Current: %s", ex.evaluation.summary());
		synchronized (evaluation)
		{
			evaluation.add(ex.evaluation);
			LogInfo.logs("Cumulative(%s): %s", prefix, evaluation.summary());
		}

		LogInfo.end_track();

		// To save memory
		ex.clean();
	}

}
