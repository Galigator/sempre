package edu.stanford.nlp.sempre.tables.features;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import edu.stanford.nlp.sempre.ContextValue;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.FuzzyMatchFn.FuzzyMatchFnMode;
import edu.stanford.nlp.sempre.LanguageInfo;
import edu.stanford.nlp.sempre.NameValue;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueFormula;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import fig.basic.Option;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

/**
 * Represents a phrase in the utterance. Also contains additional information such as POS and NER tags.
 *
 * @author ppasupat
 */
public class PhraseInfo
{
	public static class Options
	{
		@Option(gloss = "Maximum number of tokens in a phrase")
		public int maxPhraseLength = 3;
		@Option(gloss = "Fuzzy match predicates")
		public boolean computeFuzzyMatchPredicates = false;
		@Option(gloss = "Do not produce lexicalized features if the phrase begins or ends with a stop word")
		public boolean forbidBorderStopWordInLexicalizedFeatures = true;
	}

	public static Options opts = new Options();

	public final int start, end, endOffset;
	public final String text;
	public final String lemmaText;
	public final List<String> tokens;
	public final List<String> lemmaTokens;
	public final List<String> posTags;
	public final List<String> nerTags;
	public final String canonicalPosSeq;
	public final List<String> fuzzyMatchedPredicates;
	public final boolean isBorderStopWord; // true if the first or last word is a stop word

	public PhraseInfo(final Example ex, final int start, final int end)
	{
		this.start = start;
		this.end = end;
		final LanguageInfo languageInfo = ex.languageInfo;
		endOffset = end - languageInfo.numTokens();
		tokens = languageInfo.tokens.subList(start, end);
		lemmaTokens = languageInfo.tokens.subList(start, end);
		posTags = languageInfo.posTags.subList(start, end);
		nerTags = languageInfo.nerTags.subList(start, end);
		text = languageInfo.phrase(start, end).toLowerCase();
		lemmaText = languageInfo.lemmaPhrase(start, end).toLowerCase();
		canonicalPosSeq = languageInfo.canonicalPosSeq(start, end);
		fuzzyMatchedPredicates = opts.computeFuzzyMatchPredicates ? getFuzzyMatchedPredicates(ex.context) : null;
		isBorderStopWord = isStopWord(languageInfo.lemmaTokens.get(start)) || isStopWord(languageInfo.lemmaTokens.get(end - 1));
	}

	private List<String> getFuzzyMatchedPredicates(final ContextValue context)
	{
		if (context == null || context.graph == null || !(context.graph instanceof TableKnowledgeGraph))
			return null;
		final TableKnowledgeGraph graph = (TableKnowledgeGraph) context.graph;
		final List<String> matchedPredicates = new ArrayList<>();
		// Assume everything is ValueFormula with NameValue inside
		final List<Formula> formulas = new ArrayList<>();
		formulas.addAll(graph.getFuzzyMatchedFormulas(text, FuzzyMatchFnMode.ENTITY));
		formulas.addAll(graph.getFuzzyMatchedFormulas(text, FuzzyMatchFnMode.BINARY));
		for (final Formula formula : formulas)
			if (formula instanceof ValueFormula)
			{
				final Value value = ((ValueFormula<?>) formula).value;
				if (value instanceof NameValue)
					matchedPredicates.add(((NameValue) value).id);
			}
		return matchedPredicates;
	}

	static final Pattern ALL_PUNCT = Pattern.compile("^[^A-Za-z0-9]*$");
	static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList("a", "an", "the", "be", "of", "in", "on", "do"));

	static boolean isStopWord(final String x)
	{
		if (ALL_PUNCT.matcher(x).matches())
			return true;
		if (STOP_WORDS.contains(x))
			return true;
		return false;
	}

	@Override
	public String toString()
	{
		return "\"" + text + "\"";
	}

	// Caching
	private static final LoadingCache<Example, List<PhraseInfo>> cache = CacheBuilder.newBuilder().maximumSize(20).build(new CacheLoader<Example, List<PhraseInfo>>()
	{
		@Override
		public List<PhraseInfo> load(final Example ex) throws Exception
		{
			final List<PhraseInfo> phraseInfos = new ArrayList<>();
			final List<String> tokens = ex.languageInfo.tokens;
			for (int s = 1; s <= opts.maxPhraseLength; s++)
				for (int i = 0; i <= tokens.size() - s; i++)
					phraseInfos.add(new PhraseInfo(ex, i, i + s));
			return phraseInfos;
		}
	});

	public static List<PhraseInfo> getPhraseInfos(final Example ex)
	{
		try
		{
			return cache.get(ex);
		}
		catch (final ExecutionException e)
		{
			throw new RuntimeException(e.getCause());
		}
	}

}
