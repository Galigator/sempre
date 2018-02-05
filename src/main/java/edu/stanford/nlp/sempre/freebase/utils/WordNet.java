package edu.stanford.nlp.sempre.freebase.utils;

import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.objectbank.ObjectBank;

/*
 * Distributed as part of WordWalk, a Java package for lexical
 * semantic relatedness using random graph walks.
 *
 * Copyright (C) 2008 Daniel Ramage
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.

 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.

 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110 USA
 */

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>
 * Fast, lightweight, constant time library for accessing WordNet. Use one of the public load methods to create an instance by reading the WordNet data files
 * from disk. WordNet is represented with a set of WordID, WordTagID, and SynsetID objects, each of which is unique within the WordNet instance (so you can use
 * == for equality checks). There are many types of edges between these nodes (mostly SynsetID to SynsetID), where each edge type is defined in the EdgeType
 * enum.
 * </p>
 * <p>
 * This class would be Serializable except that the default Java serialization mechanism can't handle the depth of recursion between the inner WordTagID objects
 * -- hence an instance can be created only by loading the requisite file from disk.
 * </p>
 *
 * @author dramage
 * @author Chris Manning made minimal changes to make it JavaNLP land not Ramage land
 */
public final class WordNet
{

	/** Global counter for assigning unique index to each loaded id. */
	private final ArrayList<WordNetID> all = new ArrayList<>(500000);

	/** Global immutable view of all */
	private final List<WordNetID> immutableAll = Collections.unmodifiableList(all);

	/** Set of loaded edges types */
	private final Set<EdgeType> loadedEdges = new HashSet<>();

	/** Indexes mapping canonical strings to their typed versions */
	private final Map<String, SynsetID> synsets = new HashMap<>();
	private final Map<String, WordID> words = new HashMap<>();
	private final Map<String, WordTagID> wordtags = new HashMap<>();

	/** File resource backing this WordNet instance */
	private final File path;

	/**
	 * Private constructor - use one of the static constructor methods.
	 *
	 * @param path Path on disk to the underlying wordnet resource.
	 */
	private WordNet(final File path)
	{
		this.path = path;
	}

	/**
	 * All types of edges in wordnet. Some are transposes of each other, as specified by parallel objects in edgeTransposePairs, edgeTransposeTarget.
	 */
	public enum EdgeType
	{
		// structural relationships between words, wordtags, and synsets. etc
		WORD_TO_WORDTAG, WORDTAG_TO_WORD, // word to all wordtag in play
		WORDTAG_IN_SYNSET, SYNSET_HAS_WORDTAG, // wordtag to all synsets
		SYNSET_WORDTAGS_OVERLAP, // two synsets share wordtag

		// invertible synset relations
		HYPONYM, HYPERNYM, // from hyp; nouns and verbs
		INSTANCE_OF, HAS_INSTANCE, // from ins
		ENTAILS, ENTAILED_BY, // from ent; verbs only
		SIM_HEAD, SIM_SATELLITE, // from sim; adjectives only
		MM_HOLONYM, MM_MERONYM, // from mm; member meronyms
		MS_HOLONYM, MS_MERONYM, // from ms; substance meronym
		MP_HOLONYM, MP_MERONYM, // from mp; part meronym
		CAUSED_BY, CAUSES, // from cs; for verbs
		DERIVES, DERIVED_FROM, // from der; for noun to adj

		// self reflexive synset relations
		ATTRIBUTE, // from at

		// invertible word relations
		PARTICIPLE_OF, HAS_PARTICIPLE, // from ppl
		PERTAINS_TO, PERTANYM_OF, // from per
		SEE_ALSO_TO, SEE_ALSO_FROM, // from sa

		// self reflexive word relations
		ANTONYM, // from ant
		SIMILAR_VERBS, // from vgp

		// weird relations (words or synsets) from cls relation
		TERM_HAS_TOPIC, TOPIC_FROM_TERM, TERM_HAS_USAGE, USAGE_FROM_TERM, TERM_IN_REGION, REGION_HAS_TERM,
	}

	/** Set of relations that are transposes of eachother. */
	private static final EdgeType[][] transpose = { { EdgeType.HYPONYM, EdgeType.HYPERNYM }, { EdgeType.INSTANCE_OF, EdgeType.HAS_INSTANCE }, { EdgeType.ENTAILS, EdgeType.ENTAILED_BY }, { EdgeType.SIM_HEAD, EdgeType.SIM_SATELLITE }, { EdgeType.MM_HOLONYM, EdgeType.MM_MERONYM }, { EdgeType.MS_HOLONYM, EdgeType.MS_MERONYM }, { EdgeType.MP_HOLONYM, EdgeType.MP_MERONYM }, { EdgeType.CAUSED_BY, EdgeType.CAUSES }, { EdgeType.DERIVES, EdgeType.DERIVED_FROM }, { EdgeType.PARTICIPLE_OF, EdgeType.HAS_PARTICIPLE }, { EdgeType.PERTAINS_TO, EdgeType.PERTANYM_OF }, { EdgeType.SEE_ALSO_TO, EdgeType.SEE_ALSO_FROM }, { EdgeType.TERM_HAS_TOPIC, EdgeType.TOPIC_FROM_TERM }, { EdgeType.TERM_HAS_USAGE, EdgeType.USAGE_FROM_TERM }, { EdgeType.TERM_IN_REGION, EdgeType.REGION_HAS_TERM } };

	/** Set of relations that are self-reflexive, i.e. if a->b then b-> a */
	private static final EdgeType[] reflexive = { EdgeType.SYNSET_WORDTAGS_OVERLAP, EdgeType.ATTRIBUTE, EdgeType.SIMILAR_VERBS, EdgeType.ANTONYM, };

	/**
	 * WordNet-defined part of speech tags. Folds adjective satellite in with adjective.
	 */
	public enum PartOfSpeech
	{
		NOUN('n'), VERB('v'), ADJECTIVE('a'), ADVERB('r');

		/** WordNet character for encoding the part of speech. */
		public final char ssType;

		private PartOfSpeech(final char ssType)
		{
			this.ssType = ssType;
		}

		public static PartOfSpeech fromWordNetSSType(final char ssType)
		{
			for (final PartOfSpeech pos : PartOfSpeech.values())
				if (pos.ssType == ssType)
					return pos;
			if (ssType == 's')
				return ADJECTIVE;
			throw new IllegalArgumentException("Unexpected ss_type: " + ssType);
		}
	}

	/** Base of all wordnet ids. Comparison is based on index number. */
	public abstract class WordNetID implements Comparable<WordNetID>
	{
		/** Globally unique index of this WordNetID */
		private int index;

		/** Outgoing links from this node */
		private final Map<EdgeType, ArrayList<WordNetID>> links = new EnumMap<>(EdgeType.class);

		/** Private no-arg constructor prevents new subclasses */
		private WordNetID()
		{
			index = all.size();
			all.add(this);
		}

		/**
		 * Returns the set of nodes linked from this note by the given edge type.
		 */
		public List<WordNetID> get(final EdgeType type)
		{
			List<WordNetID> set = links.get(type);
			if (set == null)
				set = Collections.emptyList();
			return Collections.unmodifiableList(set);
		}

		/** Returns the index of this WordNetID in the enclosing WordNet instance */
		public int index()
		{
			return index;
		}

		/** Adds a link with the given edge type to the object */
		protected void add(final EdgeType type, final WordNetID target)
		{
			ArrayList<WordNetID> list = links.get(type);
			if (list == null)
			{
				list = new ArrayList<>();
				links.put(type, list);
			}
			if (!list.contains(target))
				list.add(target);
		}

		/** Compacts and sorts each edge list. */
		protected void compact()
		{
			for (final ArrayList<WordNetID> edge : links.values())
			{
				Collections.sort(edge);
				edge.trimToSize();
			}
		}

		/** Ordered by index in enclosing WordNet */
		public int compareTo(final WordNetID other)
		{
			return index < other.index ? -1 : index > other.index ? 1 : 0;
		}

		/** Returns the enclosing WordNet instance */
		public WordNet getEnclosingWordNet()
		{
			return WordNet.this;
		}

		/** Ordered by index in enclosing WordNet */
		@Override
		public boolean equals(final Object other)
		{
			if (other == this)
				return true;
			if (!(other instanceof WordNetID))
				return false;
			final WordNetID w = (WordNetID) other;
			return index == w.index;
		}

		@Override
		public int hashCode()
		{
			return index;
		}

	}

	/**
	 * Represents a word, e.g. "dog". Instances of this class are unique for a given {@link WordNet}, so direct object equality and hashcode are the default
	 * behavior.
	 *
	 * @author dramage
	 */
	public final class WordID extends WordNetID
	{

		/** WordTags for this Word */
		private final EnumMap<PartOfSpeech, WordTagID> mWordTags = new EnumMap<>(PartOfSpeech.class);

		/** The string of our word (with spaces converted to underscore) */
		public final String word;

		/** Immutable view of all WordTagIDs this WordID is part of. */
		public final Map<PartOfSpeech, WordTagID> wordTags = Collections.unmodifiableMap(mWordTags);

		private WordID(final String word)
		{
			this.word = word;
		}

		/**
		 * Gets the WordTagID for this word with the given part of speech or null if the part of speech does not apply to this word.
		 */
		public WordTagID getWordTag(final PartOfSpeech tag)
		{
			return mWordTags.get(tag);
		}

		/** Adds a reference to the given WordTagID */
		private void addWordTag(final WordTagID wordTagId)
		{
			assert !mWordTags.containsKey(wordTagId.tag) || mWordTags.get(wordTagId.tag) == wordTagId : "Unexpected duplicate WordTagID";

			mWordTags.put(wordTagId.tag, wordTagId);
		}

		@Override
		public String toString()
		{
			return word;
		}
	}

	/**
	 * Represents a word with part of speech tag, e.g. "dog#n". Instances of this class are unique for a given {@link WordNet}, so direct object equality and
	 * hashcode are the default behavior.
	 *
	 * @author dramage
	 */
	public final class WordTagID extends WordNetID
	{

		/** Mutable view of word senses for this WordTag. */
		private final ArrayList<SynsetID> mSynsets = new ArrayList<>(1);

		/** The WordID we are an instance of */
		public final WordID word;

		/** Our part of speech tag */
		public PartOfSpeech tag;

		/** Immutable list of all synsets this WordTag takes part in. */
		public final List<SynsetID> synsets = Collections.unmodifiableList(mSynsets);

		public List<SynsetID> getSynsets()
		{
			return synsets;
		}

		private WordTagID(final WordID word, final PartOfSpeech tag)
		{
			this.word = word;
			this.tag = tag;
		}

		/**
		 * Gets the n'th synset associated with this word tag. This index is 1-based to be consistent with wordnet's numbering scheme; i.e. getSynset(0) will
		 * throw an IllegalArgumentException.
		 */
		public SynsetID getSynset(int num)
		{
			num--; // convert to 0-indexed
			if (num < 0)
				throw new IllegalArgumentException("SynsetIDs are 1-based");
			return mSynsets.get(num);
		}

		/** Adds the given Synset into this WordTag with the given number */
		private void addSynset(final SynsetID synsetId, int num)
		{
			num--; // convert to 0-indexed
			assert num >= 0 && (mSynsets.size() <= num || mSynsets.get(num) == null || mSynsets.get(num) == synsetId) : "Unexpected repeat of word sense " + synsetId + " on " + this + ": already " + mSynsets.get(num);

			while (mSynsets.size() <= num)
				mSynsets.add(null);
			mSynsets.set(num, synsetId);
		}

		@Override
		public String toString()
		{
			return word + "#" + tag.ssType;
		}
	}

	/**
	 * Represents a WordNet synset id as an int, e.g. 301380127. Instances of this class are unique for a given {@link WordNet}, so direct object equality and
	 * hashcode are the default behavior. Each SynsetID has potentially many WordTagSenseID's.
	 *
	 * @author dramage
	 */
	public final class SynsetID extends WordNetID
	{

		/** Modifiable WordTagIDs associated with this SynsetID */
		private final ArrayList<WordTagID> mWordTags = new ArrayList<>(1);

		/** The synset id number */
		public final int synset;

		/** Number of times this sense was tagged in a corpus or 0 for not seen. */
		public final int count;

		/** The number of this synset in the first WordTagID (for toString) */
		private int numberInFirstWordTagID = 0;

		/** All WordTags that this Synset is a part of */
		public final List<WordTagID> wordtags = Collections.unmodifiableList(mWordTags);

		private SynsetID(final int synset, final int count)
		{
			this.synset = synset;
			this.count = count;
		}

		/** Returns the n'th word sense */
		public WordTagID getWordTag(int num)
		{
			num--; // convert to 0-indexed
			if (num < 0)
				throw new IllegalArgumentException("WordTagIDs are 1-based");

			return mWordTags.get(num);
		}

		/**
		 * Adds the given WordTagSense into this synset
		 */
		private void addWordSense(final WordTagID wordTagId, int num)
		{
			num--; // convert to 0-indexed
			assert num >= 0 && (mWordTags.size() <= num || mWordTags.get(num) == null || mWordTags.get(num) == wordTagId) : "Unexpected repeat of word " + wordTagId + " on " + this + ": already " + mWordTags.get(num);

			while (mWordTags.size() <= num)
				mWordTags.add(null);
			mWordTags.set(num, wordTagId);
		}

		@Override
		public String toString()
		{
			return mWordTags.get(0) + "#" + numberInFirstWordTagID;
		}
	}

	/** Returns a collection of all the WordNetIDs loaded. */
	public Collection<WordNetID> getAllWordNetIDs()
	{
		return immutableAll;
	}

	/** Returns a collection of all the words loaded. */
	public Collection<String> getAllWords()
	{
		return words.keySet();
	}

	/**
	 * Returns the given WordNetID by it's index. The return value could be cast to one of the three possible subclasses: WordID, WordTagID, or SynsetID.
	 */
	public WordNetID getWordNetID(final int index)
	{
		return all.get(index);
	}

	/**
	 * Returns the WordNetID referred to by the given string. Delegates to one of getSynsetID, getWordTagID, or getWordID depending on the format of the string
	 * or null if no such ID can be found.
	 */
	public WordNetID getWordNetID(final String string)
	{
		final int firstHash = string.indexOf('#');
		if (firstHash < 0)
		{
			final WordNetID rv = getWordID(string);
			return rv != null ? rv : getSynsetID(string);
		}
		else
		{
			final int secondHash = string.lastIndexOf('#');
			if (firstHash == secondHash)
				return getWordTagID(string);
			else
				return getSynsetID(string);
		}
	}

	/**
	 * Returns the SynsetID instance as described by the given id string or null if there is no such id. The string can either be a synset id such as
	 * "100001740" or a word sense such as "entity#n#1".
	 */
	public SynsetID getSynsetID(final String string)
	{
		// check if specified as "100001740"
		SynsetID id = synsets.get(string);
		if (id == null)
		{
			// check if specified as "entity#n#1"
			final int split = string.lastIndexOf('#');
			if (split >= 1)
			{
				int num;
				try
				{
					num = Integer.parseInt(string.substring(split + 1));
				}
				catch (final NumberFormatException e)
				{
					return null;
				}
				final WordTagID wordTagId = wordtags.get(string.substring(0, split));
				if (wordTagId != null)
					id = wordTagId.getSynset(num);
			}
		}
		return id;
	}

	/**
	 * Returns the WordID instance from a string such as "dog" or null if there is no such id.
	 */
	public WordID getWordID(final String string)
	{
		return words.get(string);
	}

	/**
	 * Returns the WordTagID instance from a string such as "dog#n" or null if there is no such id.
	 */
	public WordTagID getWordTagID(final String string)
	{
		return wordtags.get(string);
	}

	/**
	 * The total number of loaded nodes in the WordNet instance.
	 */
	public int size()
	{
		return all.size();
	}

	/**
	 * Returns the path on disk of the underlying WordNet database.
	 */
	@Override
	public String toString()
	{
		return path.toString();
	}

	/**
	 * Stitches together the structural edge types, WORD_TOWORDTAG, SYNSET_HAS_WORDTAG, etc.
	 */
	private void createStructuralEdges()
	{
		// create WORD_TO_WORDTAG and WORDTAG_TO_WORD
		for (final WordID word : words.values())
			for (final WordTagID wordtag : word.wordTags.values())
			{
				word.add(EdgeType.WORD_TO_WORDTAG, wordtag);
				wordtag.add(EdgeType.WORDTAG_TO_WORD, word);
			}

		// create WORTAG_IN_SYNSET and SYNSET_HAS_WORDTAG
		for (final WordTagID wordtag : wordtags.values())
			for (final SynsetID synset : wordtag.synsets)
			{
				wordtag.add(EdgeType.WORDTAG_IN_SYNSET, synset);
				synset.add(EdgeType.SYNSET_HAS_WORDTAG, wordtag);
			}

		// create SYNSET_WORDTAGS_OVERLAP
		for (final WordTagID wordtag : wordtags.values())
			for (final SynsetID synsetA : wordtag.mSynsets)
			{
				if (synsetA == null)
					continue;
				for (final SynsetID synsetB : wordtag.mSynsets)
				{
					if (synsetB == null || synsetA == synsetB)
						continue;
					synsetA.add(EdgeType.SYNSET_WORDTAGS_OVERLAP, synsetB);
				}
			}

		// record that we have created these edges
		loadedEdges.addAll(Arrays.asList(new EdgeType[] { EdgeType.WORD_TO_WORDTAG, EdgeType.WORDTAG_TO_WORD, EdgeType.WORDTAG_IN_SYNSET, EdgeType.SYNSET_HAS_WORDTAG, EdgeType.SYNSET_WORDTAGS_OVERLAP, }));
	}

	/** Compacts and finalizes ordering of all data structures */
	private void compact()
	{
		// sort the WordNetID's by type (then by order added)
		Collections.sort(all, (o1, o2) ->
		{
			if (o1.getClass() == o2.getClass())
				return o1.compareTo(o2);
			else
				if (o1.getClass() == SynsetID.class)
					return -1;
				else
					if (o2.getClass() == SynsetID.class)
						return 1;
					else
						if (o1.getClass() == WordTagID.class)
							return -1;
						else
							if (o2.getClass() == WordTagID.class)
								return 1;
							else
								throw new RuntimeException("Unexpected WordNetID type");
		});

		// re-number the wordnet id's
		for (int i = 0; i < all.size(); i++)
			all.get(i).index = i;

		for (final WordNetID id : all)
			id.compact();
		for (final SynsetID id : synsets.values())
			id.mWordTags.trimToSize();
		for (final WordTagID id : wordtags.values())
			id.mSynsets.trimToSize();
		all.trimToSize();
	}

	/** Checks the representation invariants */
	private void checkrep()
	{
		for (final EdgeType edgetype : EdgeType.values())
			assertIt(loadedEdges.contains(edgetype), "Failed to load " + edgetype);

		for (final SynsetID synset : synsets.values())
		{
			assertIt(all.get(synset.index()) == synset, "Misplaced synset " + synset);
			assertIt(synset.getWordTag(1).getSynset(synset.numberInFirstWordTagID) == synset, "Wrong number in first WordTagID " + synset);
			assertIt(!synset.wordtags.contains(null), synset + " contains null WordTagID");

			for (final WordTagID wordtag : synset.wordtags)
				assertIt(wordtag.synsets.contains(synset), "Miswired Synset " + synset);
		}

		for (final WordTagID wordtag : wordtags.values())
		{
			assertIt(all.get(wordtag.index()) == wordtag, "Misplaced wordtag " + wordtag);
			assertIt(!wordtag.synsets.contains(null), wordtag + " contains null SynsetID");

			for (final SynsetID synset : wordtag.synsets)
				assertIt(synset.wordtags.contains(wordtag), "Miswired WordTag " + wordtag);

			assertIt(wordtag.word.wordTags.containsValue(wordtag), "Miswired WordTag " + wordtag);
		}

		for (final WordID word : words.values())
		{
			assertIt(all.get(word.index()) == word, "Misplaced word " + word);
			for (final WordTagID wordtag : word.wordTags.values())
				assertIt(wordtag.word == word, "Miswired WordTag " + word);
		}

		for (final WordNetID id1 : all)
			for (final EdgeType[] pair : transpose)
				for (final WordNetID id2 : id1.get(pair[0]))
					assertIt(id2.get(pair[1]).contains(id1), "Missing transpose " + Arrays.asList(transpose) + " " + id1 + " " + id2);

		for (final WordNetID id1 : all)
			for (final EdgeType type : reflexive)
				for (final WordNetID id2 : id1.get(type))
					assertIt(id2.get(type).contains(id1), "Missing reflextive " + type + " " + id1 + " " + id2);
	}

	/** Assertion method used by checkRep */
	private static void assertIt(final boolean condition, final String message)
	{
		if (!condition)
			throw new AssertionError(message);
	}

	//
	// WordNet loader
	//

	/** Weak collection of WordNet instances based on file name */
	private static final Collection<WeakReference<WordNet>> instances = new LinkedList<>();

	/**
	 * Loads an instance of WordNet from the given WordNet database -- currently supports only Prolog DB format.
	 */
	public static WordNet load(final File path)
	{
		System.err.println("WordNet.load: " + path);

		// see if already loaded
		for (final Iterator<WeakReference<WordNet>> it = instances.iterator(); it.hasNext();)
		{

			final WordNet wordnet = it.next().get();

			if (wordnet == null)
				it.remove();
			else
				if (wordnet.path.equals(path))
					return wordnet;
		}

		// not already loaded, load now
		try
		{
			final WordNet wordnet = loadPrologWordNet(path);
			instances.add(new WeakReference<>(wordnet));
			return wordnet;
		}
		catch (final RuntimeIOException e)
		{
			throw new IllegalArgumentException("Provided path not a valid WordNet PrologDB directory", e);
		}
	}

	/**
	 * Returns an instance of WordNet based on the contents of the WordNet databases as stored in the given path prolog WordNet 3.0 format.
	 */
	public static WordNet loadPrologWordNet(final File path)
	{
		final WordNet wordnet = new WordNet(path);

		/** Global stashing point for unassigned word senses depending on wn POS tag */
		final Map<WordTagID, SynsetID> deferredPositionSynsets = new HashMap<>();

		//
		// read the synsets file
		//
		{
			for (final String line : ObjectBank.getLineIterator(new File(path, "wn_s.pl")))
			{
				if (line.length() == 0)
					continue;

				// fields from the line
				final String[] fields = line.substring(2, line.length() - 2).split(",");
				final int wordTagNumberInSynset = Integer.parseInt(fields[1]);
				final int synsetNumberInWordTag = Integer.parseInt(fields.length > 4 ? fields[4] : "0");
				final int senseCount = Integer.parseInt(fields.length > 5 ? fields[5] : "0");
				final String word = new String(fields[2].substring(1, fields[2].length() - 1).replaceAll("\\s+", "_")).toLowerCase();
				final PartOfSpeech tag = PartOfSpeech.fromWordNetSSType(fields[3].charAt(0));
				final String wordTag = word + "#" + tag.ssType;
				final String synset = fields[0];

				// add WordID
				WordID wordId = wordnet.words.get(word);
				if (wordId == null)
				{
					wordId = wordnet.new WordID(word);
					wordnet.words.put(word, wordId);
				}

				// add WordTagID
				WordTagID wordTagId = wordnet.wordtags.get(wordTag);
				if (wordTagId == null)
				{
					wordTagId = wordnet.new WordTagID(wordId, tag);
					wordnet.wordtags.put(wordTag, wordTagId);
				}

				// add SynsetID
				SynsetID synsetId = wordnet.synsets.get(synset);
				if (synsetId == null)
				{
					synsetId = wordnet.new SynsetID(Integer.parseInt(synset), senseCount);
					wordnet.synsets.put(synset, synsetId);
				}

				// link WordID to WordTagID
				wordId.addWordTag(wordTagId);

				// link WordTagID to SynsetID
				if (synsetNumberInWordTag == 0)
				{
					if (deferredPositionSynsets.containsKey(wordTagId))
						throw new RuntimeException("Error: don't know what " + "to do when more than one synset doesn't come" + " with a valid sense number");
					else
						deferredPositionSynsets.put(wordTagId, synsetId);
				}
				else
					wordTagId.addSynset(synsetId, synsetNumberInWordTag);
				synsetId.addWordSense(wordTagId, wordTagNumberInSynset);
			}

			// add in deferredPositionSynsets
			for (final Map.Entry<WordTagID, SynsetID> entry : deferredPositionSynsets.entrySet())
			{
				boolean placed = false;
				for (int i = 0; i < entry.getKey().mSynsets.size(); i++)
					if (entry.getKey().mSynsets.get(i) == null)
					{
						entry.getKey().mSynsets.set(i, entry.getValue());
						placed = true;
					}
				if (!placed)
					throw new AssertionError("Unable to place deferred synset");
			}

			// tell each SynsetID its position in its first WordTagID
			for (final SynsetID synset : wordnet.synsets.values())
			{
				final int position = synset.wordtags.get(0).mSynsets.indexOf(synset) + 1;
				assert position >= 1 : "Unexpected: couldn't find the synset";
				synset.numberInFirstWordTagID = position;
			}

			// add all structural edges
			wordnet.createStructuralEdges();
		}

		//
		// read all synset relations defined over SynsetID pairs
		//

		// invert-ready synset relations
		wordnet.loadSynsetRelation(path, "hyp", EdgeType.HYPONYM);
		wordnet.loadSynsetRelation(path, "ins", EdgeType.INSTANCE_OF);
		wordnet.loadSynsetRelation(path, "ent", EdgeType.ENTAILS);
		wordnet.loadSynsetRelation(path, "sim", EdgeType.SIM_HEAD);
		wordnet.loadSynsetRelation(path, "mm", EdgeType.MM_HOLONYM);
		wordnet.loadSynsetRelation(path, "ms", EdgeType.MS_HOLONYM);
		wordnet.loadSynsetRelation(path, "mp", EdgeType.MP_HOLONYM);
		wordnet.loadSynsetRelation(path, "cs", EdgeType.CAUSED_BY);

		// self-reflexive synset relations
		wordnet.loadSynsetRelation(path, "at", EdgeType.ATTRIBUTE);

		// invert-ready word relations
		wordnet.loadWordRelation(path, "ppl", EdgeType.PARTICIPLE_OF);
		wordnet.loadWordRelation(path, "per", EdgeType.PERTAINS_TO);
		wordnet.loadWordRelation(path, "sa", EdgeType.SEE_ALSO_TO);

		// self-reflexive word relations
		wordnet.loadWordRelation(path, "der", EdgeType.DERIVES);
		wordnet.loadWordRelation(path, "vgp", EdgeType.SIMILAR_VERBS);
		wordnet.loadWordRelation(path, "ant", EdgeType.ANTONYM);

		// weird class relations
		wordnet.loadCLSRelations(path);

		// do transposes
		for (final EdgeType[] pair : transpose)
			wordnet.addTranspose(pair[0], pair[1]);

		// compact data structure and check rep invariants

		wordnet.compact();
		wordnet.checkrep();

		return wordnet;
	}

	/**
	 * Loads the given relation from the prolog file, storing the result in the given EdgeType.
	 */
	private void loadSynsetRelation(final File path, final String relation, final EdgeType type)
	{
		if (loadedEdges.contains(type))
			throw new IllegalArgumentException("Unexpected error: trying to load " + type + " twice");
		loadedEdges.add(type);

		for (final String line : ObjectBank.getLineIterator(new File(path, "wn_" + relation + ".pl")))
		{
			if (line.length() == 0)
				continue;
			final String[] fields = line.substring(relation.length() + 1, line.length() - 2).split(",");

			final SynsetID id1 = getSynsetID(fields[0]);
			final SynsetID id2 = getSynsetID(fields[1]);

			id1.add(type, id2);
		}
	}

	/**
	 * Loads the given relation from the prolog file, storing the result in the given EdgeType.
	 */
	private void loadWordRelation(final File path, final String relation, final EdgeType type)
	{
		if (loadedEdges.contains(type))
			throw new IllegalArgumentException("Unexpected error: trying to load " + type + " twice");
		loadedEdges.add(type);

		for (final String line : ObjectBank.getLineIterator(new File(path, "wn_" + relation + ".pl")))
		{
			if (line.length() == 0)
				continue;
			final String[] fields = line.substring(relation.length() + 1, line.length() - 2).split(",");

			final SynsetID sid1 = getSynsetID(fields[0]);
			final SynsetID sid2 = getSynsetID(fields[2]);

			if (sid1 == sid2)
				System.err.println("WordNet.loadWordRelation(" + relation + "): skipping self-loop on " + sid1);
			else
				sid1.add(type, sid2);
		}
	}

	/**
	 * Loads the given relation from the prolog file, storing the result in the given EdgeType.
	 */
	private void loadCLSRelations(final File path)
	{
		final String relation = "cls";

		if (loadedEdges.contains(EdgeType.TERM_HAS_TOPIC) || loadedEdges.contains(EdgeType.TERM_HAS_USAGE) || loadedEdges.contains(EdgeType.TERM_IN_REGION))
			throw new IllegalArgumentException("Unexpected error while loading " + relation);

		loadedEdges.add(EdgeType.TERM_HAS_TOPIC);
		loadedEdges.add(EdgeType.TERM_HAS_USAGE);
		loadedEdges.add(EdgeType.TERM_IN_REGION);

		for (final String line : ObjectBank.getLineIterator(new File(path, "wn_" + relation + ".pl")))
		{
			if (line.length() == 0)
				continue;
			final String[] fields = line.substring(relation.length() + 1, line.length() - 2).split(",");

			if (fields.length != 5 || fields[4].length() != 1)
				throw new IllegalArgumentException("Badly formed file for " + relation);

			final SynsetID sid1 = getSynsetID(fields[0]);
			final SynsetID sid2 = getSynsetID(fields[2]);

			final int num1 = Integer.parseInt(fields[1]);
			final int num2 = Integer.parseInt(fields[3]);
			assert !(num1 == 0 ^ num2 == 0);

			final WordNetID id1 = num1 == 0 ? sid1 : sid1.getWordTag(num1);
			final WordNetID id2 = num2 == 0 ? sid2 : sid2.getWordTag(num2);

			switch (fields[4].charAt(0))
			{
				case 't':
					id1.add(EdgeType.TERM_HAS_TOPIC, id2);
					break;

				case 'u':
					id1.add(EdgeType.TERM_HAS_USAGE, id2);
					break;

				case 'r':
					id1.add(EdgeType.TERM_IN_REGION, id2);
					break;

				default:
					throw new IllegalArgumentException("Unexpected relation type " + fields[4]);
			}
		}
	}

	/** Adds a new edge type t2 as the transpose of t1 */
	private void addTranspose(final EdgeType t1, final EdgeType t2)
	{
		if (!loadedEdges.contains(t1))
			throw new IllegalArgumentException("Cannot transpose: doesn't contain " + t1);
		else
			if (loadedEdges.contains(t2))
				throw new IllegalArgumentException("Cannot transpose: already contains " + t2);
		loadedEdges.add(t2);

		/** Add reverse links for all types */
		for (final WordNetID ab : all)
			for (final WordNetID ba : ab.get(t1))
				ba.add(t2, ab);
	}

	//
	// Sample main method for testing
	//
	public static void main(final String[] args)
	{
		final WordNet wordnet = load(new File(args[0]));

		final SynsetID id1 = wordnet.getSynsetID("run#v#1");

		for (final EdgeType edgetype : EdgeType.values())
			for (final WordNetID id2 : id1.get(edgetype))
				System.out.println(edgetype + " " + id2);

		System.out.println(wordnet.getAllWordNetIDs().size());

		for (final SynsetID synset : wordnet.synsets.values())
			System.out.printf("%06d %s\n", synset.synset, synset.toString());
	}

}
