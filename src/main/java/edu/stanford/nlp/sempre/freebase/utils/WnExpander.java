package edu.stanford.nlp.sempre.freebase.utils;

import edu.stanford.nlp.sempre.freebase.utils.WordNet.EdgeType;
import edu.stanford.nlp.sempre.freebase.utils.WordNet.WordID;
import edu.stanford.nlp.sempre.freebase.utils.WordNet.WordNetID;
import fig.basic.LogInfo;
import fig.basic.Option;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class WnExpander
{

	public static class Options
	{
		@Option(gloss = "Verbose")
		public int verbose = 0;
		@Option(gloss = "Path to Wordnet file")
		public String wnFile = "lib/wordnet-3.0-prolog";
		@Option(gloss = "Relations to expand with wordnet")
		public Set<String> wnRelations = new HashSet<>();
	}

	public static Options opts = new Options();

	private final WordNet wn;
	private final Set<EdgeType> edgeTypes = new HashSet<>();

	/**
	 * Initializing wordnet and the relations to expand with
	 *
	 * @throws IOException
	 */
	public WnExpander() throws IOException
	{
		wn = WordNet.loadPrologWordNet(new File(opts.wnFile));
		for (final String wnRelation : opts.wnRelations)
			switch (wnRelation)
			{
				case "derives":
					edgeTypes.add(EdgeType.DERIVES);
					break;
				case "derived_from":
					edgeTypes.add(EdgeType.DERIVED_FROM);
					break;
				case "hyponym":
					edgeTypes.add(EdgeType.HYPONYM);
					break;
				default:
					throw new RuntimeException("Invalid relation: " + wnRelation);
			}
	}

	public Set<String> expandPhrase(final String phrase)
	{

		// find synsetse for phrase
		final Set<WordNetID> phraseSynsets = phraseToSynsets(phrase);
		// expand synsets
		for (final EdgeType edgeType : edgeTypes)
			phraseSynsets.addAll(expandSynsets(phraseSynsets, edgeType));
		// find phrases for synsets
		final Set<String> expansions = synsetsToPhrases(phraseSynsets);
		if (opts.verbose > 0)
			for (final String expansion : expansions)
				LogInfo.logs("WordNetExpansionLexicon: expanding %s to %s", phrase, expansion);
		return expansions;
	}

	public Set<String> getSynonyms(final String phrase)
	{
		final Set<WordNetID> phraseSynsets = phraseToSynsets(phrase);
		final Set<String> expansions = synsetsToPhrases(phraseSynsets);
		expansions.remove(phrase);
		return expansions;
	}

	public Set<String> getDerivations(final String phrase)
	{
		final Set<WordNetID> phraseSynsets = phraseToSynsets(phrase);
		final Set<WordNetID> derivations = new HashSet<>();
		derivations.addAll(expandSynsets(phraseSynsets, EdgeType.DERIVED_FROM));
		derivations.addAll(expandSynsets(phraseSynsets, EdgeType.DERIVES));
		final Set<String> expansions = synsetsToPhrases(derivations);
		expansions.remove(phrase);
		return expansions;
	}

	public Set<String> getHypernyms(final String phrase)
	{
		final Set<WordNetID> phraseSynsets = phraseToSynsets(phrase);
		final Set<WordNetID> hypernyms = new HashSet<>();
		hypernyms.addAll(expandSynsets(phraseSynsets, EdgeType.HYPONYM));
		final Set<String> expansions = synsetsToPhrases(hypernyms);
		expansions.remove(phrase);
		return expansions;
	}

	private Set<String> synsetsToPhrases(final Set<WordNetID> phraseSynsets)
	{

		final Set<String> res = new HashSet<>();
		for (final WordNetID phraseSynset : phraseSynsets)
			res.addAll(synsetToPhrases(phraseSynset));
		return res;
	}

	private Collection<String> synsetToPhrases(final WordNetID phraseSynset)
	{
		final Set<String> res = new HashSet<>();
		final List<WordNetID> wordTags = phraseSynset.get(EdgeType.SYNSET_HAS_WORDTAG);
		for (final WordNetID wordTag : wordTags)
		{
			final List<WordNetID> words = wordTag.get(EdgeType.WORDTAG_TO_WORD);
			for (final WordNetID word : words)
				res.add(((WordID) word).word);
		}
		return res;
	}

	/** Given a phrase find all synsets containing this phrase */
	private Set<WordNetID> phraseToSynsets(final String phrase)
	{

		final List<WordNetID> wordTags = new LinkedList<>();
		final WordID word = wn.getWordID(phrase);
		if (word != null)
			wordTags.addAll(word.get(EdgeType.WORD_TO_WORDTAG));
		final Set<WordNetID> synsets = new HashSet<>();
		for (final WordNetID wordTag : wordTags)
			synsets.addAll(wordTag.get(EdgeType.WORDTAG_IN_SYNSET));
		return synsets;
	}

	private List<WordNetID> expandSynset(final WordNetID synset, final EdgeType edgeType)
	{
		return synset.get(edgeType);
	}

	private Set<WordNetID> expandSynsets(final Collection<WordNetID> synsets, final EdgeType edgeType)
	{
		final Set<WordNetID> res = new HashSet<>();
		for (final WordNetID synset : synsets)
			res.addAll(expandSynset(synset, edgeType));
		return res;
	}

	public static void main(final String[] args) throws IOException
	{

		final WnExpander wnLexicon = new WnExpander();
		wnLexicon.expandPhrase("assassinate");
		System.out.println();
	}

}
