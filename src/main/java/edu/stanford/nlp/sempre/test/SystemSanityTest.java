package edu.stanford.nlp.sempre.test;

import static org.testng.AssertJUnit.assertEquals;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import edu.stanford.nlp.sempre.Builder;
import edu.stanford.nlp.sempre.Dataset;
import edu.stanford.nlp.sempre.FeatureExtractor;
import edu.stanford.nlp.sempre.FormulaMatchExecutor;
import edu.stanford.nlp.sempre.Grammar;
import edu.stanford.nlp.sempre.LanguageAnalyzer;
import edu.stanford.nlp.sempre.Learner;
import edu.stanford.nlp.sempre.SimpleAnalyzer;
import fig.basic.Evaluation;
import fig.basic.Pair;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

/**
 * Various end-to-end sanity checks.
 *
 * @author Roy Frostig
 * @author Percy Liang
 */
public class SystemSanityTest
{
	private static Builder makeBuilder(final String grammarPath)
	{
		final Grammar g = new Grammar();
		g.read(grammarPath);

		final Builder b = new Builder();
		b.grammar = g;
		b.executor = new FormulaMatchExecutor();
		b.buildUnspecified();
		return b;
	}

	private static Dataset makeDataset()
	{
		final Dataset d = new Dataset();
		d.readFromPathPairs(Collections.singletonList(Pair.newPair("train", "freebase/data/unittest-learn.examples")));
		return d;
	}

	private static Map<String, List<Evaluation>> learn(final Builder builder, final Dataset dataset)
	{
		final Map<String, List<Evaluation>> evals = Maps.newHashMap();
		new Learner(builder.parser, builder.params, dataset).learn(3, evals);
		return evals;
	}

	@Test(groups = { "sparql", "corenlp" })
	public void easyEndToEnd()
	{
		LanguageAnalyzer.setSingleton(new SimpleAnalyzer());
		// Make sure learning works
		final Dataset dataset = makeDataset();
		final String[] grammarPaths = new String[] { "freebase/data/unittest-learn.grammar", "freebase/data/unittest-learn-ccg.grammar", };
		for (final String grammarPath : grammarPaths)
		{
			final Builder builder = makeBuilder(grammarPath);
			FeatureExtractor.opts.featureDomains.add("rule");
			final Map<String, List<Evaluation>> evals = learn(builder, dataset);
			assertEquals(1.0d, Iterables.getLast(evals.get("train")).getFig("correct").min());
		}
	}
}
