package edu.stanford.nlp.sempre.overnight;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.sempre.CallFormula;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FeatureComputer;
import edu.stanford.nlp.sempre.FeatureVector;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.LanguageInfo;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.Rule;
import edu.stanford.nlp.sempre.SimpleLexicon;
import edu.stanford.nlp.sempre.StringValue;
import edu.stanford.nlp.sempre.Value;
import edu.stanford.nlp.sempre.ValueFormula;
import fig.basic.BipartiteMatcher;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import fig.basic.Option;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Define features on the input utterance and a partial canonical utterance. Feature computation recipe: - For both the input and (partial) canonical utterance,
 * extract a list of tokens (perhaps with POS tags). - Given a list of tokens, extract a set of items, where an item is a (tag, data) pair, where the tag
 * specifies the "type" of the data, and is used to determine features. Example: ("bigram", "not contains"), ("unigram", "not"), ("unigram-RB", "not") - Given
 * the input and canonical items, define recall features (how much of the input items is the canononical covering). This recipe allows us to decouple the
 * extraction of items on one utterance from the computation of actual precision/recall features.
 *
 * @author Percy Liang
 * @author Yushi Wang
 */
public class OvernightFeatureComputer implements FeatureComputer
{
	public static class Options
	{
		@Option(gloss = "Set of paraphrasing feature domains to include")
		public Set<String> featureDomains = new HashSet<>();

		@Option(gloss = "Whether or not to count intermediate categories for size feature")
		public boolean countIntermediate = true;

		@Option(gloss = "Whether or not to do match/ppdb analysis")
		public boolean itemAnalysis = true;

		@Option(gloss = "Whether or not to learn paraphrases")
		public boolean learnParaphrase = true;

		@Option(gloss = "Verbose flag")
		public int verbose = 0;

		@Option(gloss = "Path to alignment file")
		public String wordAlignmentPath;
		@Option(gloss = "Path to phrase alignment file")
		public String phraseAlignmentPath;
		@Option(gloss = "Threshold for phrase table co-occurrence")
		public int phraseTableThreshold = 3;
	}

	public static Options opts = new Options();

	private static Aligner aligner;
	private static Map<String, Map<String, Double>> phraseTable;
	public final SimpleLexicon simpleLexicon = SimpleLexicon.getSingleton();

	@Override
	public void extractLocal(final Example ex, final Derivation deriv)
	{
		if (deriv.rule.rhs == null)
			return;

		// Optimization: feature vector same as child, so don't do anything.
		if (deriv.rule.isCatUnary())
			if (deriv.isRootCat())
			{
				extractValueInFormulaFeature(deriv);
				extractRootFeatures(ex, deriv);
				return;
			}

		// Important!  We want to define the global feature vector for this
		// derivation, but we can only specify the local feature vector.  So to
		// make things cancel out, we subtract out the unwanted feature vectors of
		// descendents.
		subtractDescendentsFeatures(deriv, deriv);

		deriv.addFeature("paraphrase", "size", derivationSize(deriv));
		extractRootFeatures(ex, deriv);
		extractLexicalFeatures(ex, deriv);
		extractPhraseAlignmentFeatures(ex, deriv);
		extractLogicalFormFeatures(ex, deriv);

		if (!opts.itemAnalysis)
			return;

		final List<Item> inputItems = computeInputItems(ex);
		final List<Item> candidateItems = computeCandidateItems(ex, deriv);

		for (final Item input : inputItems)
		{
			double match = 0;
			double ppdb = 0;
			double skipBigram = 0;
			double skipPpdb = 0;
			for (final Item candidate : candidateItems)
			{
				if (!input.tag.equals(candidate.tag))
					continue;
				if (input.tag.equals("skip-bigram"))
				{
					skipBigram = Math.max(skipBigram, computeMatch(input.data, candidate.data));
					skipPpdb = Math.max(skipPpdb, computeParaphrase(input.data, candidate.data));
				}
				else
				{

					match = Math.max(match, computeMatch(input.data, candidate.data));
					ppdb = Math.max(ppdb, computeParaphrase(input.data, candidate.data));
				}
			}
			if (match > 0 && opts.featureDomains.contains("match"))
				deriv.addFeature("paraphrase", "match");
			if (ppdb > 0 && opts.featureDomains.contains("ppdb"))
				deriv.addFeature("paraphrase", "ppdb");
			if (skipBigram > 0 && opts.featureDomains.contains("skip-bigram"))
				deriv.addFeature("paraphrase", "skip-bigram");
			if (skipPpdb > 0 && opts.featureDomains.contains("skip-ppdb"))
				deriv.addFeature("paraphrase", "skip-ppdb");
		}

		final HashMap<String, Double> features = new LinkedHashMap<>();
		deriv.incrementAllFeatureVector(+1, features);
		if (opts.verbose >= 1)
		{
			LogInfo.logs("category %s, %s %s", deriv.cat, inputItems, candidateItems);
			FeatureVector.logFeatures(features);
		}
	}

	private void extractValueInFormulaFeature(final Derivation deriv)
	{
		if (!opts.featureDomains.contains("denotation"))
			return;

		if (deriv.value instanceof StringValue)
		{

			//get strings from value
			final List<String> valueList = new ArrayList<>();

			String value = ((StringValue) deriv.value).value;

			if (value.charAt(0) == '[')
				value = value.substring(1, value.length() - 1); //strip "[]"
			final String[] tokens = value.split(",");
			for (String token : tokens)
			{
				token = token.trim(); //strip spaces
				if (token.length() > 0)
					valueList.add(token);
			}

			//get strings from formula
			final List<Formula> formulaList = deriv.formula.mapToList(formula ->
			{
				final List<Formula> res = new ArrayList<>();
				if (formula instanceof ValueFormula)
					res.add(formula);
				return res;
			}, true);

			for (final Formula f : formulaList)
			{
				final Value formulaValue = ((ValueFormula) f).value;
				final String valueStr = formulaValue instanceof StringValue ? ((StringValue) formulaValue).value : formulaValue.toString();
				if (valueList.contains(valueStr))
					deriv.addFeature("denotation", "value_in_formula");
			}
		}
	}

	private void extractRootFeatures(final Example ex, final Derivation deriv)
	{
		if (!deriv.isRootCat())
			return;
		if (!opts.featureDomains.contains("root") && !opts.featureDomains.contains("root_lexical"))
			return;

		final List<String> derivTokens = Arrays.asList(deriv.canonicalUtterance.split("\\s+"));
		final List<String> inputTokens = ex.getTokens();
		//alignment features
		final BipartiteMatcher bMatcher = new BipartiteMatcher();
		final List<String> filteredInputTokens = filterStopWords(inputTokens);
		final List<String> filteredDerivTokens = filterStopWords(derivTokens);

		final int[] assignment = bMatcher.findMaxWeightAssignment(buildAlignmentMatrix(filteredInputTokens, filteredDerivTokens));

		if (opts.featureDomains.contains("root"))
		{
			//number of unmathced words based on exact match and ppdb
			int matches = 0;
			for (int i = 0; i < filteredInputTokens.size(); ++i)
				if (assignment[i] != i)
					matches++;
			deriv.addFeature("root", "unmatched_input", filteredInputTokens.size() - matches);
			deriv.addFeature("root", "unmatched_deriv", filteredDerivTokens.size() - matches);
			if (deriv.value != null)
				if (deriv.value instanceof ListValue)
				{
					final ListValue list = (ListValue) deriv.value;
					deriv.addFeature("root", String.format("pos0=%s&returnType=%s", ex.posTag(0), list.values.get(0).getClass()));
				}
		}

		if (opts.featureDomains.contains("root_lexical"))
			for (int i = 0; i < assignment.length; ++i)
				if (assignment[i] == i)
					if (i < filteredInputTokens.size())
					{
						final String inputToken = filteredInputTokens.get(i).toLowerCase();
						deriv.addFeature("root_lexical", "deleted_token=" + inputToken);
						if (!simpleLexicon.lookup(inputToken).isEmpty())
							deriv.addFeature("root_lexical", "deleted_entity");
					}
					else
					{
						final String derivToken = filteredDerivTokens.get(i - filteredInputTokens.size());
						deriv.addFeature("root_lexical", "deleted_token=" + derivToken);
						if (!simpleLexicon.lookup(derivToken).isEmpty())
							deriv.addFeature("root_lexical", "deleted_entity");
					}
	}

	private List<Formula> getCallFormulas(final Derivation deriv)
	{
		return deriv.formula.mapToList(formula ->
		{
			final List<Formula> res = new ArrayList<>();
			if (formula instanceof CallFormula)
				res.add(((CallFormula) formula).func);
			return res;
		}, true);
	}

	private void extractLogicalFormFeatures(final Example ex, final Derivation deriv)
	{
		if (!opts.featureDomains.contains("lf"))
			return;
		for (int i = 0; i < ex.numTokens(); ++i)
		{
			final List<Formula> callFormulas = getCallFormulas(deriv);
			if (ex.posTag(i).equals("JJS"))
			{
				if (ex.token(i).equals("least") || ex.token(i).equals("most")) //at least and at most are not what we want
					continue;
				for (final Formula callFormula : callFormulas)
				{
					final String callFormulaDesc = callFormula.toString();
					//LogInfo.logs("SUPER: utterance=%s, formula=%s", ex.utterance, deriv.formula);
					deriv.addFeature("lf", callFormulaDesc + "& superlative");
				}
			}
		}
		if (!opts.featureDomains.contains("simpleworld"))
			return;
		//specific handling of simple world methods
		if (deriv.formula instanceof CallFormula)
		{
			final CallFormula callFormula = (CallFormula) deriv.formula;
			final String desc = callFormula.func.toString();
			switch (desc)
			{
				case "edu.stanford.nlp.sempre.overnight.SimpleWorld.filter":
					deriv.addFeature("simpleworld", "filter&" + callFormula.args.get(1));
					break;
				case "edu.stanford.nlp.sempre.overnight.SimpleWorld.getProperty":
					deriv.addFeature("simpleworld", "getProperty&" + callFormula.args.get(1));
					break;
				case "edu.stanford.nlp.sempre.overnight.SimpleWorld.superlative":
					deriv.addFeature("simpleworld", "superlative&" + callFormula.args.get(1) + "&" + callFormula.args.get(2));
					break;
				case "edu.stanford.nlp.sempre.overnight.SimpleWorld.countSuperlative":
					deriv.addFeature("simpleworld", "countSuperlative&" + callFormula.args.get(1) + "&" + callFormula.args.get(2));
					break;
				case "edu.stanford.nlp.sempre.overnight.SimpleWorld.countComparative":
					deriv.addFeature("simpleworld", "countComparative&" + callFormula.args.get(2) + "&" + callFormula.args.get(1));
					break;
				case "edu.stanford.nlp.sempre.overnight.SimpleWorld.aggregate":
					deriv.addFeature("simpleworld", "countComparative&" + callFormula.args.get(0));
					break;
				default:
					break;
			}
		}
	}

	private void extractPhraseAlignmentFeatures(final Example ex, final Derivation deriv)
	{

		if (!opts.featureDomains.contains("alignment"))
			return;
		if (phraseTable == null)
			phraseTable = loadPhraseTable();

		//get the tokens
		final List<String> derivTokens = Arrays.asList(deriv.canonicalUtterance.split("\\s+"));
		final Set<String> inputSubspans = ex.languageInfo.getLowerCasedSpans();

		for (int i = 0; i < derivTokens.size(); ++i)
			for (int j = i + 1; j <= derivTokens.size() && j <= i + 4; ++j)
			{

				final String lhs = Joiner.on(' ').join(derivTokens.subList(i, j));
				if (entities.contains(lhs))
					continue; //optimization

				if (phraseTable.containsKey(lhs))
				{
					final Map<String, Double> rhsCandidates = phraseTable.get(lhs);
					final Set<String> intersection = Sets.intersection(rhsCandidates.keySet(), inputSubspans);
					for (final String rhs : intersection)
						addAndFilterLexicalFeature(deriv, "alignment", rhs, lhs);
				}
			}
	}

	private Map<String, Map<String, Double>> loadPhraseTable()
	{
		final Map<String, Map<String, Double>> res = new HashMap<>();
		int num = 0;
		for (final String line : IOUtils.readLines(opts.phraseAlignmentPath))
		{
			final String[] tokens = line.split("\t");
			if (tokens.length != 3)
				throw new RuntimeException("Bad alignment line: " + line);
			MapUtils.putIfAbsent(res, tokens[0], new HashMap<>());

			final double value = Double.parseDouble(tokens[2]);
			if (value >= opts.phraseTableThreshold)
			{
				res.get(tokens[0]).put(tokens[1], value);
				num++;
			}
		}
		LogInfo.logs("Number of entries=%s", num);
		return res;
	}

	private void addAndFilterLexicalFeature(final Derivation deriv, final String domain, final String str1, final String str2)
	{

		final String[] str1Tokens = str1.split("\\s+");
		final String[] str2Tokens = str2.split("\\s+");
		for (final String str1Token : str1Tokens)
			if (entities.contains(str1Token))
				return;
		for (final String str2Token : str2Tokens)
			if (entities.contains(str2Token))
				return;

		if (stopWords.contains(str1) || stopWords.contains(str2))
			return;
		deriv.addFeature(domain, str1 + "--" + str2);
	}

	private void extractLexicalFeatures(final Example ex, final Derivation deriv)
	{

		if (!opts.featureDomains.contains("lexical"))
			return;

		final List<String> derivTokens = Arrays.asList(deriv.canonicalUtterance.split("\\s+"));
		final List<String> inputTokens = ex.getTokens();
		//alignment features
		final BipartiteMatcher bMatcher = new BipartiteMatcher();
		final List<String> filteredInputTokens = filterStopWords(inputTokens);
		final List<String> filteredDerivTokens = filterStopWords(derivTokens);

		final double[][] alignmentMatrix = buildLexicalAlignmentMatrix(filteredInputTokens, filteredDerivTokens);
		final int[] assignment = bMatcher.findMaxWeightAssignment(alignmentMatrix);
		for (int i = 0; i < filteredInputTokens.size(); ++i)
			if (assignment[i] != i)
			{
				final int derivIndex = assignment[i] - filteredInputTokens.size();
				final String inputToken = filteredInputTokens.get(i).toLowerCase();

				if (entities.contains(inputToken))
					continue; //optimization - stop here

				final String derivToken = filteredDerivTokens.get(derivIndex).toLowerCase();
				if (!inputToken.equals(derivToken))
				{
					addAndFilterLexicalFeature(deriv, "lexical", inputToken, derivToken);
					extractStringSimilarityFeatures(deriv, inputToken, derivToken);

					//2:2 features
					if (i < filteredInputTokens.size() - 1)
						if (assignment[i + 1] == assignment[i] + 1)
						{
							final String inputBigram = Joiner.on(' ').join(inputToken, filteredInputTokens.get(i + 1)).toLowerCase();
							final String derivBigram = Joiner.on(' ').join(derivToken, filteredDerivTokens.get(derivIndex + 1)).toLowerCase();
							if (!inputBigram.equals(derivBigram))
								addAndFilterLexicalFeature(deriv, "lexical", inputBigram, derivBigram);
						}
					//1:2 features
					if (derivIndex > 0)
						addAndFilterLexicalFeature(deriv, "lexical", inputToken, Joiner.on(' ').join(filteredDerivTokens.get(derivIndex - 1), filteredDerivTokens.get(derivIndex)));
					if (derivIndex < filteredDerivTokens.size() - 1)
						addAndFilterLexicalFeature(deriv, "lexical", inputToken, Joiner.on(' ').join(filteredDerivTokens.get(derivIndex), filteredDerivTokens.get(derivIndex + 1)));
				}
			}
	}

	private void extractStringSimilarityFeatures(final Derivation deriv, final String inputToken, final String derivToken)
	{
		if (inputToken.startsWith(derivToken) || derivToken.startsWith(inputToken))
			deriv.addFeature("lexical", "starts_with");
		else
			if (inputToken.length() > 4 && derivToken.length() > 4)
				if (inputToken.substring(0, 4).equals(derivToken.substring(0, 4)))
					deriv.addFeature("lexical", "common_prefix");
	}

	//return a list without wtop words
	private List<String> filterStopWords(final List<String> tokens)
	{
		final List<String> res = new ArrayList<>();
		for (final String token : tokens)
			if (!stopWords.contains(token))
				res.add(token);
		return res;
	}

	private double[][] buildAlignmentMatrix(final List<String> inputTokens, final List<String> derivTokens)
	{

		final double[][] res = new double[inputTokens.size() + derivTokens.size()][inputTokens.size() + derivTokens.size()];
		for (int i = 0; i < inputTokens.size(); ++i)
			for (int j = 0; j < derivTokens.size(); ++j)
			{
				final String inputToken = inputTokens.get(i);
				final String derivToken = derivTokens.get(j);

				if (computeMatch(inputToken, derivToken) > 0d)
				{
					res[i][inputTokens.size() + j] = 1d;
					res[inputTokens.size() + j][i] = 1d;
				}
				else
					if (computeParaphrase(inputToken, derivToken) > 0d)
					{
						res[i][inputTokens.size() + j] = 0.5d;
						res[inputTokens.size() + j][i] = 0.5d;
					}
			}
		for (int i = 0; i < res.length - 1; i++)
			for (int j = i + 1; j < res.length; j++)
				if (i != j && res[i][j] < 1)
				{
					res[i][j] = Double.NEGATIVE_INFINITY;
					res[j][i] = Double.NEGATIVE_INFINITY;
				}
		return res;
	}

	private double[][] buildLexicalAlignmentMatrix(final List<String> inputTokens, final List<String> derivTokens)
	{
		if (aligner == null)
			aligner = Aligner.read(opts.wordAlignmentPath);

		final double[][] res = new double[inputTokens.size() + derivTokens.size()][inputTokens.size() + derivTokens.size()];
		//init with -infnty and low score on the diagonal
		for (int i = 0; i < res.length - 1; i++)
			for (int j = i; j < res.length; j++)
				if (i == j)
				{
					res[i][j] = 0d;
					res[j][i] = 0d;
				}
				else
				{
					res[i][j] = -1000d;
					res[j][i] = -1000d;
				}

		for (int i = 0; i < inputTokens.size(); ++i)
			for (int j = 0; j < derivTokens.size(); ++j)
			{
				final String inputToken = inputTokens.get(i).toLowerCase();
				final String derivToken = derivTokens.get(j).toLowerCase();

				if (computeMatch(inputToken, derivToken) > 0)
				{
					res[i][inputTokens.size() + j] = 1d;
					res[inputTokens.size() + j][i] = 1d;
				}
				else
					if (computeParaphrase(inputToken, derivToken) > 0)
					{
						res[i][inputTokens.size() + j] = 0.5d;
						res[inputTokens.size() + j][i] = 0.5d;
					}
					else
						if (aligner.getCondProb(inputToken, derivToken) > 0d && aligner.getCondProb(derivToken, inputToken) > 0d)
						{
							final double product = aligner.getCondProb(inputToken, derivToken) * aligner.getCondProb(derivToken, inputToken);
							res[i][inputTokens.size() + j] = product;
							res[inputTokens.size() + j][i] = product;
						}
			}
		return res;
	}

	// Represents a local pattern on an utterance.
	private static class Item
	{
		public final String tag;
		public final String data;

		public Item(final String tag, final String data)
		{
			this.tag = tag;
			this.data = data;
		}

		@Override
		public String toString()
		{
			return tag + ":" + data;
		}
	}

	// Fetch items from the temporary state.
	// If it doesn't exist, create one.
	private static List<Item> getItems(final Map<String, Object> tempState)
	{
		List<Item> items = (List<Item>) tempState.get("items");
		if (items == null)
			tempState.put("items", items = new ArrayList<>());
		return items;
	}

	private static void setItems(final Map<String, Object> tempState, final List<Item> items)
	{
		tempState.put("items", items);
	}

	// TODO(yushi): make this less hacky
	private static final List<String> stopWords = Arrays.asList("\' \" `` ` \'\' a an the that which . what ? is are am be of".split(" "));
	private static final Set<String> entities = new HashSet<>(Arrays.asList("alice", "bob", "greenberg", "greenberg cafe", "central office", "sacramento", "austin", "california", "texas", "colorado", "colorado river", "red river", "lake tahoe", "tahoe", "lake huron", "huron", "mount whitney", "whitney", "mount rainier", "rainier", "death valley", "pacific ocean", "pacific", "sesame", "mission ave", "mission", "chelsea", "multivariate data analysis", "multivariate data", "data analysis", "multivariate", "data", "efron", "lakoff", "annals of statistics", "annals", "annals of", "of statistics", "statistics", "computational linguistics", "computational", "linguistics", "thai cafe", "pizzeria juno", "new york", "york", "beijing", "brown university", "ucla", "mckinsey", "google"));

	private static boolean isStopWord(final String token)
	{
		return stopWords.contains(token);
	}

	private static void populateItems(final List<String> tokens, final List<Item> items)
	{
		final List<String> prunedTokens = new ArrayList<>();
		// Populate items with unpruned tokens
		for (int i = 0; i < tokens.size(); i++)
		{
			items.add(new Item("unigram", tokens.get(i)));
			if (i - 1 >= 0)
				items.add(new Item("bigram", tokens.get(i - 1) + " " + tokens.get(i)));
			if (!isStopWord(tokens.get(i)) || i > 0 && (tokens.get(i - 1).equals('`') || tokens.get(i - 1).equals("``")))
				prunedTokens.add(tokens.get(i));
		}

		// Populate items with skip words removed
		for (int i = 1; i < prunedTokens.size(); i++)
			items.add(new Item("skip-bigram", prunedTokens.get(i - i) + " " + prunedTokens.get(i)));
	}

	// Compute the items for the input utterance.
	private static List<Item> computeInputItems(final Example ex)
	{
		final List<Item> items = getItems(ex.getTempState());
		if (items.size() != 0)
			return items;
		final List<String> tokens = new ArrayList<>(ex.getTokens());
		populateItems(tokens, items);
		LogInfo.logs("input %s, items %s", ex, items);
		return items;
	}

	// Return the set of tokens (partial canonical utterance) produced by the
	// derivation.
	public static List<String> extractTokens(final Example ex, final Derivation deriv, final List<String> tokens)
	{
		int childIndex = 0;
		if (deriv.rule.rhs != null)
		{
			for (final String p : deriv.rule.rhs)
				if (Rule.isCat(p))
					extractTokens(ex, deriv.children.get(childIndex++), tokens);
				else
					tokens.add(p);

		}
		else
			if (deriv.start != -1 && deriv.end != -1)
				for (int i = deriv.start; i < deriv.end; i++)
					tokens.add(ex.token(i));
		return tokens;
	}

	// Compute the items for a partial canonical utterance.
	private static List<Item> computeCandidateItems(final Example ex, final Derivation deriv)
	{
		// Get tokens
		final List<String> tokens = new ArrayList<>();
		extractTokens(ex, deriv, tokens);
		// Compute items
		final List<Item> items = new ArrayList<>();
		populateItems(tokens, items);
		return items;
	}

	private static void subtractDescendentsFeatures(final Derivation deriv, final Derivation subderiv)
	{
		if (subderiv.children != null)
			for (final Derivation child : subderiv.children)
			{
				deriv.getLocalFeatureVector().add(-1, child.getLocalFeatureVector());
				subtractDescendentsFeatures(deriv, child);
			}
	}

	// Return the "complexity" of the given derivation.
	private static int derivationSize(final Derivation deriv)
	{
		int sum = 0;
		if (opts.countIntermediate || !deriv.rule.lhs.contains("Intermediate"))
			sum++;
		if (deriv.children != null)
			for (final Derivation child : deriv.children)
				sum += derivationSize(child);
		return sum;
	}

	private static double computeMatch(final String a, final String b)
	{
		if (a.equals(b))
			return 1;
		if (LanguageInfo.LanguageUtils.stem(a).equals(LanguageInfo.LanguageUtils.stem(b)))
			return 1;
		return 0;
	}

	private static double computeParaphrase(final String a, final String b)
	{
		if (computeMatch(a, b) > 0)
			return 0;

		final String[] aGrams = a.split(" ");
		final String[] bGrams = b.split(" ");
		if (aGrams.length != bGrams.length)
			return 0;

		final PPDBModel model = PPDBModel.getSingleton();
		int numPpdb = 0;
		int numMisses = 0;
		for (int i = 0; i < aGrams.length; i++)
			if (computeMatch(aGrams[i], bGrams[i]) == 0d)
				if (model.get(aGrams[i], bGrams[i]) > 0d || model.get(LanguageInfo.LanguageUtils.stem(aGrams[i]), LanguageInfo.LanguageUtils.stem(bGrams[i])) > 0d)
					numPpdb++;
				else
					numMisses++;
		return numMisses == 0 && numPpdb <= 1 ? 1d : 0d;
	}
}
