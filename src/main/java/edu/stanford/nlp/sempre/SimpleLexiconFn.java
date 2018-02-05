package edu.stanford.nlp.sempre;

import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import fig.basic.StringDoubleVec;
import java.util.ArrayList;
import java.util.List;

/**
 * Uses the SimpleLexicon. Example: (rule $ROOT ($PHRASE) (SimpleLexiconFn (type fb:type.any)))
 *
 * @author Percy Liang
 */
public class SimpleLexiconFn extends SemanticFn
{
	public static class Options
	{
		@Option(gloss = "Number of entities to return from entity lexicon")
		public int maxEntityEntries = 100;

		@Option(gloss = "Verbosity level")
		public int verbose = 0;
	}

	public static Options opts = new Options();

	private static SimpleLexicon lexicon;

	// Only return entries whose type matches this
	private SemType restrictType = SemType.topType;

	public SimpleLexiconFn()
	{
		lexicon = SimpleLexicon.getSingleton();
	}

	public void init(final LispTree tree)
	{
		super.init(tree);
		for (int i = 1; i < tree.children.size(); i++)
		{
			// (type fb:people.person): allow us to restrict the type
			final LispTree arg = tree.child(i);
			if ("type".equals(arg.child(0).value))
				restrictType = SemType.fromLispTree(arg.child(1));
		}
	}

	public DerivationStream call(final Example ex, final Callable c)
	{
		final String phrase = c.childStringValue(0);
		List<SimpleLexicon.Entry> entries = lexicon.lookup(phrase);

		// Filter by type
		final List<SimpleLexicon.Entry> newEntries = new ArrayList<>();
		for (final SimpleLexicon.Entry e : entries)
		{
			if (opts.verbose >= 3)
				LogInfo.logs("SimpleLexiconFn: %s => %s [type = %s meet-> %s]", phrase, e.formula, e.type, restrictType.meet(e.type));
			if (!restrictType.meet(e.type).isValid())
				continue;
			newEntries.add(e);
		}
		entries = newEntries;

		return new MyDerivationStream(ex, c, entries, phrase);
	}

	public class MyDerivationStream extends MultipleDerivationStream
	{
		private final Example ex;
		private final Callable callable;
		private final List<SimpleLexicon.Entry> entries;
		private final String phrase;
		private int currIndex = 0;

		public MyDerivationStream(final Example ex, final Callable c, final List<SimpleLexicon.Entry> entries, final String phrase)
		{
			this.ex = ex;
			callable = c;
			this.entries = entries;
			this.phrase = phrase;
		}

		@Override
		public int estimatedSize()
		{
			return entries.size();
		}

		@Override
		public Derivation createDerivation()
		{
			if (currIndex == entries.size())
				return null;

			final SimpleLexicon.Entry entry = entries.get(currIndex++);
			final FeatureVector features = new FeatureVector();
			final Derivation deriv = new Derivation.Builder().withCallable(callable).formula(entry.formula).type(entry.type).localFeatureVector(features).createDerivation();

			if (FeatureExtractor.containsDomain("basicStats"))
				if (entry.features != null)
					for (final StringDoubleVec.Entry e : entry.features)
						features.add("basicStats", e.getFirst(), e.getSecond());

			// Doesn't generalize, but add it for now, otherwise not separable
			if (FeatureExtractor.containsDomain("lexAlign"))
				deriv.addFeature("lexAlign", phrase + " --- " + entry.formula);

			if (SemanticFn.opts.trackLocalChoices)
				deriv.addLocalChoice("SimpleLexiconFn " + deriv.startEndString(ex.getTokens()) + " " + entry);

			return deriv;
		}
	}
}
