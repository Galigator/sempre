package edu.stanford.nlp.sempre;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Joiner;
import fig.basic.IntPair;
import fig.basic.LispTree;
import fig.basic.MemUsage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents an linguistic analysis of a sentence (provided by some LanguageAnalyzer).
 *
 * @author akchou
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class LanguageInfo implements MemUsage.Instrumented
{

	// Tokenization of input.
	@JsonProperty
	public final List<String> tokens;
	@JsonProperty
	public final List<String> lemmaTokens; // Lemmatized version

	// Syntactic information from JavaNLP.
	@JsonProperty
	public final List<String> posTags; // POS tags
	@JsonProperty
	public final List<String> nerTags; // NER tags
	@JsonProperty
	public final List<String> nerValues; // NER values (contains times, dates, etc.)

	private Map<String, IntPair> lemmaSpans;
	private Set<String> lowercasedSpans;

	public static class DependencyEdge
	{
		@JsonProperty
		public final String label; // Dependency label
		@JsonProperty
		public final int modifier; // Position of modifier

		@JsonCreator
		public DependencyEdge(@JsonProperty("label") final String label, @JsonProperty("modifier") final int modifier)
		{
			this.label = label;
			this.modifier = modifier;
		}

		@Override
		public String toString()
		{
			return label + "->" + modifier;
		}
	}

	@JsonProperty
	// Dependencies of each token, represented as a (relation, parentIndex) pair
	public final List<List<DependencyEdge>> dependencyChildren;

	public LanguageInfo()
	{
		this(new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(), new ArrayList<String>(), new ArrayList<List<DependencyEdge>>());
	}

	@JsonCreator
	public LanguageInfo(@JsonProperty("tokens") final List<String> tokens, @JsonProperty("lemmaTokens") final List<String> lemmaTokens, @JsonProperty("posTags") final List<String> posTags, @JsonProperty("nerTags") final List<String> nerTags, @JsonProperty("nerValues") final List<String> nerValues, @JsonProperty("dependencyChildren") final List<List<DependencyEdge>> dependencyChildren)
	{
		this.tokens = tokens;
		this.lemmaTokens = lemmaTokens;
		this.posTags = posTags;
		this.nerTags = nerTags;
		this.nerValues = nerValues;
		this.dependencyChildren = dependencyChildren;
	}

	// Return a string representing the tokens between start and end.
	public String phrase(final int start, final int end)
	{
		return sliceSequence(tokens, start, end);
	}

	public String lemmaPhrase(final int start, final int end)
	{
		return sliceSequence(lemmaTokens, start, end);
	}

	public String posSeq(final int start, final int end)
	{
		return sliceSequence(posTags, start, end);
	}

	public String canonicalPosSeq(final int start, final int end)
	{
		if (start >= end)
			throw new RuntimeException("Bad indices, start=" + start + ", end=" + end);
		if (end - start == 1)
			return LanguageUtils.getCanonicalPos(posTags.get(start));
		final StringBuilder out = new StringBuilder();
		for (int i = start; i < end; i++)
		{
			if (out.length() > 0)
				out.append(' ');
			out.append(LanguageUtils.getCanonicalPos(posTags.get(i)));
		}
		return out.toString();
	}

	public String nerSeq(final int start, final int end)
	{
		return sliceSequence(nerTags, start, end);
	}

	private static String sliceSequence(final List<String> items, final int start, final int end)
	{
		if (start >= end)
			throw new RuntimeException("Bad indices, start=" + start + ", end=" + end);
		if (end - start == 1)
			return items.get(start);
		final StringBuilder out = new StringBuilder();
		for (int i = start; i < end; i++)
		{
			if (out.length() > 0)
				out.append(' ');
			out.append(items.get(i));
		}
		return out.toString();
	}

	// If all the tokens in [start, end) have the same nerValues, but not
	// start - 1 and end + 1 (in other words, [start, end) is maximal), then return
	// the normalizedTag.  Example: queryNerTag = "DATE".
	public String getNormalizedNerSpan(final String queryTag, final int start, final int end)
	{
		String value = nerValues.get(start);
		if (value == null)
			return null;
		if (!queryTag.equals(nerTags.get(start)))
			return null;
		if (start - 1 >= 0 && value.equals(nerValues.get(start - 1)))
			return null;
		if (end < nerValues.size() && value.equals(nerValues.get(end)))
			return null;
		for (int i = start + 1; i < end; i++)
			if (!value.equals(nerValues.get(i)))
				return null;
		value = omitComparative(value);
		return value;
	}

	private String omitComparative(final String value)
	{
		if (value.startsWith("<=") || value.startsWith(">="))
			return value.substring(2);
		if (value.startsWith("<") || value.startsWith(">"))
			return value.substring(1);
		return value;
	}

	public String getCanonicalPos(final int index)
	{
		if (index == -1)
			return "OUT";
		return LanguageUtils.getCanonicalPos(posTags.get(index));
	}

	public boolean equalTokens(final LanguageInfo other)
	{
		if (tokens.size() != other.tokens.size())
			return false;
		for (int i = 0; i < tokens.size(); ++i)
			if (!tokens.get(i).equals(other.tokens.get(i)))
				return false;
		return true;
	}

	public boolean equalLemmas(final LanguageInfo other)
	{
		if (lemmaTokens.size() != other.lemmaTokens.size())
			return false;
		for (int i = 0; i < tokens.size(); ++i)
			if (!lemmaTokens.get(i).equals(other.lemmaTokens.get(i)))
				return false;
		return true;
	}

	public int numTokens()
	{
		return tokens.size();
	}

	public LanguageInfo remove(final int startIndex, final int endIndex)
	{

		if (startIndex > endIndex || startIndex < 0 || endIndex > numTokens())
			throw new RuntimeException("Illegal start or end index, start: " + startIndex + ", end: " + endIndex + ", info size: " + numTokens());

		final LanguageInfo res = new LanguageInfo();
		for (int i = 0; i < numTokens(); ++i)
			if (i < startIndex || i >= endIndex)
			{
				res.tokens.add(tokens.get(i));
				res.lemmaTokens.add(lemmaTokens.get(i));
				res.nerTags.add(nerTags.get(i));
				res.nerValues.add(nerValues.get(i));
				res.posTags.add(posTags.get(i));
			}
		return res;
	}

	public void addSpan(final LanguageInfo other, final int start, final int end)
	{
		for (int i = start; i < end; ++i)
		{
			tokens.add(other.tokens.get(i));
			lemmaTokens.add(other.lemmaTokens.get(i));
			posTags.add(other.posTags.get(i));
			nerTags.add(other.nerTags.get(i));
			nerValues.add(other.nerValues.get(i));
		}
	}

	public List<String> getSpanProperties(final int start, final int end)
	{
		final List<String> res = new ArrayList<>();
		res.add("lemmas=" + lemmaPhrase(start, end));
		res.add("pos=" + posSeq(start, end));
		res.add("ner=" + nerSeq(start, end));
		return res;
	}

	public void addWordInfo(final WordInfo wordInfo)
	{
		tokens.add(wordInfo.token);
		lemmaTokens.add(wordInfo.lemma);
		posTags.add(wordInfo.pos);
		nerTags.add(wordInfo.nerTag);
		nerValues.add(wordInfo.nerValue);
	}

	public void addWordInfos(final List<WordInfo> wordInfos)
	{
		for (final WordInfo wInfo : wordInfos)
			addWordInfo(wInfo);
	}

	public WordInfo getWordInfo(final int i)
	{
		return new WordInfo(tokens.get(i), lemmaTokens.get(i), posTags.get(i), nerTags.get(i), nerValues.get(i));
	}

	/**
	 * returns spans of named entities
	 * 
	 * @return
	 */
	public Set<IntPair> getNamedEntitySpans()
	{
		final Set<IntPair> res = new LinkedHashSet<>();
		int start = -1;
		String prevTag = "O";

		for (int i = 0; i < nerTags.size(); ++i)
		{
			final String currTag = nerTags.get(i);
			if (currTag.equals("O"))
			{
				if (!prevTag.equals("O"))
				{
					res.add(new IntPair(start, i));
					start = -1;
				}
			}
			else
				if (!currTag.equals(prevTag))
				{
					if (!prevTag.equals("O"))
						res.add(new IntPair(start, i));
					start = i;
				}
			prevTag = currTag;
		}
		if (start != -1)
			res.add(new IntPair(start, nerTags.size()));
		return res;
	}

	/**
	 * returns spans of named entities
	 * 
	 * @return
	 */
	public Set<IntPair> getProperNounSpans()
	{
		final Set<IntPair> res = new LinkedHashSet<>();
		int start = -1;
		String prevTag = "O";

		for (int i = 0; i < posTags.size(); ++i)
		{
			final String currTag = posTags.get(i);
			if (LanguageUtils.isProperNoun(currTag))
			{
				if (!LanguageUtils.isProperNoun(prevTag))
					start = i;
			}
			else
				if (LanguageUtils.isProperNoun(prevTag))
				{
					res.add(new IntPair(start, i));
					start = -1;
				}
			prevTag = currTag;
		}
		if (start != -1)
			res.add(new IntPair(start, posTags.size()));
		return res;
	}

	public Set<IntPair> getNamedEntitiesAndProperNouns()
	{
		final Set<IntPair> res = getNamedEntitySpans();
		res.addAll(getProperNounSpans());
		return res;
	}

	public Map<String, IntPair> getLemmaSpans()
	{
		if (lemmaSpans == null)
		{
			lemmaSpans = new HashMap<>();
			for (int i = 0; i < numTokens() - 1; ++i)
				for (int j = i + 1; j < numTokens(); ++j)
					lemmaSpans.put(lemmaPhrase(i, j), new IntPair(i, j));
		}
		return lemmaSpans;
	}

	public Set<String> getLowerCasedSpans()
	{
		if (lowercasedSpans == null)
		{
			lowercasedSpans = new HashSet<>();
			for (int i = 0; i < numTokens() - 1; ++i)
				for (int j = i + 1; j < numTokens(); ++j)
					lowercasedSpans.add(phrase(i, j).toLowerCase());
		}
		return lowercasedSpans;
	}

	public boolean matchLemmas(final List<WordInfo> wordInfos)
	{
		for (int i = 0; i < numTokens(); ++i)
			if (matchLemmasFromIndex(wordInfos, i))
				return true;
		return false;
	}

	private boolean matchLemmasFromIndex(final List<WordInfo> wordInfos, final int start)
	{
		if (start + wordInfos.size() > numTokens())
			return false;
		for (int j = 0; j < wordInfos.size(); ++j)
			if (!wordInfos.get(j).lemma.equals(lemmaTokens.get(start + j)))
				return false;
		return true;
	}

	/**
	 * Static methods with langauge utilities
	 * 
	 * @author jonathanberant
	 */
	public static class LanguageUtils
	{

		public static boolean sameProperNounClass(final String noun1, final String noun2)
		{
			if ((noun1.equals("NNP") || noun1.equals("NNPS")) && (noun2.equals("NNP") || noun2.equals("NNPS")))
				return true;
			return false;
		}

		public static boolean isProperNoun(final String pos)
		{
			return pos.startsWith("NNP");
		}

		public static boolean isSuperlative(final String pos)
		{
			return pos.equals("RBS") || pos.equals("JJS");
		}

		public static boolean isComparative(final String pos)
		{
			return pos.equals("RBR") || pos.equals("JJR");
		}

		public static boolean isEntity(final LanguageInfo info, final int i)
		{
			return isProperNoun(info.posTags.get(i)) || !info.nerTags.get(i).equals("O");
		}

		public static boolean isNN(final String pos)
		{
			return pos.startsWith("NN") && !pos.startsWith("NNP");
		}

		public static boolean isContentWord(final String pos)
		{
			return pos.startsWith("N") || pos.startsWith("V") || pos.startsWith("J");
		}

		public static String getLemmaPhrase(final List<WordInfo> wordInfos)
		{
			final String[] res = new String[wordInfos.size()];
			for (int i = 0; i < wordInfos.size(); ++i)
				res[i] = wordInfos.get(i).lemma;
			return Joiner.on(' ').join(res);
		}

		public static String getCanonicalPos(final String pos)
		{
			if (pos.startsWith("N"))
				return "N";
			if (pos.startsWith("V"))
				return "V";
			if (pos.startsWith("W"))
				return "W";
			return pos;
		}

		// Uses a few rules to stem tokens
		public static String stem(final String a)
		{
			final int i = a.indexOf(' ');
			if (i != -1)
				return stem(a.substring(0, i)) + ' ' + stem(a.substring(i + 1));
			//Maybe we should just use the Stanford stemmer
			String res = a;
			//hard coded words
			if (a.equals("having") || a.equals("has"))
				res = "have";
			else
				if (a.equals("using"))
					res = "use";
				else
					if (a.equals("including"))
						res = "include";
					else
						if (a.equals("beginning"))
							res = "begin";
						else
							if (a.equals("utilizing"))
								res = "utilize";
							else
								if (a.equals("featuring"))
									res = "feature";
								else
									if (a.equals("preceding"))
										res = "precede";
									//rules
									else
										if (a.endsWith("ing"))
											res = a.substring(0, a.length() - 3);
										else
											if (a.endsWith("s") && !a.equals("'s"))
												res = a.substring(0, a.length() - 1);
			//don't return an empty string
			if (res.length() > 0)
				return res;
			return a;
		}

	}

	@Override
	public long getBytes()
	{
		return MemUsage.objectSize(MemUsage.pointerSize * 2) + MemUsage.getBytes(tokens) + MemUsage.getBytes(lemmaTokens) + MemUsage.getBytes(posTags) + MemUsage.getBytes(nerTags) + MemUsage.getBytes(nerValues) + MemUsage.getBytes(lemmaSpans);
	}

	public boolean isNumberAndDate(final int index)
	{
		return posTags.get(index).equals("CD") && nerTags.get(index).equals("DATE");
	}

	public static boolean isContentWord(final String pos)
	{
		return pos.equals("NN") || pos.equals("NNS") || pos.startsWith("V") && !pos.equals("VBD-AUX") || pos.startsWith("J");
	}

	public static class WordInfo
	{
		public final String token;
		public final String lemma;
		public final String pos;
		public final String nerTag;
		public final String nerValue;

		public WordInfo(final String token, final String lemma, final String pos, final String nerTag, final String nerValue)
		{
			this.token = token;
			this.lemma = lemma;
			this.pos = pos;
			this.nerTag = nerTag;
			this.nerValue = nerValue;
		}

		public String toString()
		{
			return toLispTree().toString();
		}

		public LispTree toLispTree()
		{
			final LispTree tree = LispTree.proto.newList();
			tree.addChild("wordinfo");
			tree.addChild(token);
			tree.addChild(lemma);
			tree.addChild(pos);
			tree.addChild(nerTag);
			return tree;
		}
	}
}
