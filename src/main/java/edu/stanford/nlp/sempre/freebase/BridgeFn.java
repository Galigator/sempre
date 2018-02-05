package edu.stanford.nlp.sempre.freebase;

import edu.stanford.nlp.sempre.AtomicSemType;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.DerivationStream;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FeatureExtractor;
import edu.stanford.nlp.sempre.FeatureVector;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.LambdaFormula;
import edu.stanford.nlp.sempre.LanguageInfo.DependencyEdge;
import edu.stanford.nlp.sempre.MergeFormula;
import edu.stanford.nlp.sempre.MergeFormula.Mode;
import edu.stanford.nlp.sempre.MultipleDerivationStream;
import edu.stanford.nlp.sempre.NotFormula;
import edu.stanford.nlp.sempre.Params;
import edu.stanford.nlp.sempre.ReverseFormula;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.SemTypeHierarchy;
import edu.stanford.nlp.sempre.SemanticFn;
import edu.stanford.nlp.sempre.UnionSemType;
import edu.stanford.nlp.sempre.ValueFormula;
import edu.stanford.nlp.sempre.VariableFormula;
import edu.stanford.nlp.sempre.freebase.FbFormulasInfo.BinaryFormulaInfo;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.Option;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bridge between two derivations by type-raising one of them.
 * 
 * @author jonathanberant
 */
public class BridgeFn extends SemanticFn
{

	private static final Formula intFormula = Formulas.fromLispTree(LispTree.proto.parseFromString("(fb:type.object.type fb:type.int)"));
	private static final Formula floatFormula = Formulas.fromLispTree(LispTree.proto.parseFromString("(fb:type.object.type fb:type.float)"));

	public static class Options
	{
		@Option(gloss = "Verbose")
		public int verbose = 0;
		@Option(gloss = "Whether to have binary predicate features (ovrefits on small data)")
		public boolean useBinaryPredicateFeatures = true;
		@Option(gloss = "Whether to filter bad domains such as user and common")
		public boolean filterBadDomain = true;
	}

	public static Options opts = new Options();

	private FbFormulasInfo fbFormulaInfo = null;
	private String description;
	private boolean headFirst;
	private final TextToTextMatcher textToTextMatcher;

	public void init(final LispTree tree)
	{
		super.init(tree);
		if (tree.children.size() != 3)
			throw new RuntimeException("Number of children is: " + tree.children.size());
		if (!tree.child(2).value.equals("headFirst") && !tree.child(2).value.equals("headLast"))
			throw new RuntimeException("Bad argument for head position: " + tree.child(2).value);
		if (!tree.child(1).value.equals("unary") && !tree.child(1).value.equals("inject") && !tree.child(1).value.equals("entity"))
			throw new RuntimeException("Bad description: " + tree.child(1).value);

		description = tree.child(1).value;
		headFirst = tree.child(2).value.equals("headFirst");
	}

	public BridgeFn()
	{
		fbFormulaInfo = FbFormulasInfo.getSingleton();
		textToTextMatcher = TextToTextMatcher.getSingleton();
	}

	@Override
	public DerivationStream call(final Example ex, final Callable c)
	{
		try
		{
			switch (description)
			{
				case "unary":
					return bridgeUnary(ex, c);
				case "inject":
					return injectIntoCvt(ex, c);
				case "entity":
					return bridgeEntity(ex, c);
				default:
					throw new RuntimeException("Invalid (expected unary, inject, or entity): " + description);
			}
		}
		catch (final Exception e)
		{
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	@Override
	public void sortOnFeedback(final Params params)
	{
		LogInfo.begin_track("Learner.BridgeFeedback");
		final FbFormulasInfo fbFormulasInfo = FbFormulasInfo.getSingleton();
		final Comparator<Formula> feedbackComparator = fbFormulasInfo.new FormulaByFeaturesComparator(params);
		fbFormulasInfo.sortType2ToBinaryMaps(feedbackComparator);
		LogInfo.end_track();
	}

	private boolean isCvt(final Derivation headDeriv)
	{
		if (!(headDeriv.formula instanceof JoinFormula))
			return false;
		final JoinFormula join = (JoinFormula) headDeriv.formula;
		return join.relation instanceof LambdaFormula || join.child instanceof JoinFormula || join.child instanceof MergeFormula;
	}

	// Return all the entity supertypes of |type|.
	// TODO(joberant): make this more efficient.
	private Set<String> getSupertypes(final SemType type, final Set<String> supertypes)
	{
		if (type instanceof AtomicSemType)
			supertypes.addAll(SemTypeHierarchy.singleton.getSupertypes(((AtomicSemType) type).name));
		else
			if (type instanceof UnionSemType)
				for (final SemType baseType : ((UnionSemType) type).baseTypes)
					getSupertypes(baseType, supertypes);
			else
				// TODO(joberant): FIXME HACK for when passing binary into lambda formula and
				// getSuperTypes doesn't work
				getSupertypes(SemType.fromString("topic"), supertypes);
		// throw new RuntimeException("Unexpected type (must be unary): " + type);
		return supertypes;
	}

	private DerivationStream bridgeUnary(final Example ex, final Callable c) throws IOException
	{

		assert ex != null;
		// Example (headFirst = false): modifier[Hanks] head[movies]
		final Derivation headDeriv = headFirst ? c.child(0) : c.child(1);
		final Derivation modifierDeriv = !headFirst ? c.child(0) : c.child(1);

		final Set<String> headTypes = getSupertypes(headDeriv.type, new HashSet<>());
		final Set<String> modifierTypes = getSupertypes(modifierDeriv.type, new HashSet<>());
		final ArrayList<BridgingInfo> bridgingInfoList = new ArrayList<>();

		for (final String modifierType : modifierTypes)
		{ // For each head type...
			final List<Formula> binaries = fbFormulaInfo.getBinariesForType2(modifierType);
			for (final Formula binary : binaries)
			{ // For each possible binary...
				if (opts.filterBadDomain && badDomain(binary))
					continue;
				final BinaryFormulaInfo binaryInfo = fbFormulaInfo.getBinaryInfo(binary);

				if (opts.verbose >= 3)
					LogInfo.logs("%s => %s", modifierType, binary);

				if (headTypes.contains(binaryInfo.expectedType1))
				{
					final BridgingInfo bridgingInfo = new BridgingInfo(ex, c, binaryInfo, headFirst, headDeriv, modifierDeriv);
					bridgingInfoList.add(bridgingInfo);
				}
			}
		}
		Collections.sort(bridgingInfoList);
		return new LazyBridgeFnDerivs(bridgingInfoList);
	}

	// bridge without a unary - simply by looking at binaries leading to the entity and string matching binary description to example tokens/lemmas/stems
	private DerivationStream bridgeEntity(final Example ex, final Callable c) throws IOException
	{

		assert ex != null;
		final Derivation modifierDeriv = c.child(0);
		final Set<String> modifierTypes = getSupertypes(modifierDeriv.type, new HashSet<>());
		final ArrayList<BridgingInfo> bridgingInfoList = new ArrayList<>();

		if (opts.verbose >= 1)
			LogInfo.logs("bridgeEntity: %s | %s", modifierDeriv, modifierTypes);

		for (final String modifierType : modifierTypes)
		{ // For each head type...
			final List<Formula> binaries = fbFormulaInfo.getBinariesForType2(modifierType);
			for (final Formula binary : binaries)
			{ // For each possible binary...
				if (opts.filterBadDomain && badDomain(binary))
					continue;
				final BinaryFormulaInfo binaryInfo = fbFormulaInfo.getBinaryInfo(binary);

				if (opts.verbose >= 3)
					LogInfo.logs("%s => %s", modifierType, binary);

				final BridgingInfo bridgingInfo = new BridgingInfo(ex, c, binaryInfo, headFirst, null, modifierDeriv);
				bridgingInfoList.add(bridgingInfo);
			}
		}
		Collections.sort(bridgingInfoList);
		return new LazyBridgeFnDerivs(bridgingInfoList);
	}

	private boolean badDomain(final String binary)
	{
		return binary.contains("fb:user.") || binary.contains("fb:base.") || binary.contains("fb:dataworld.") || binary.contains("fb:type.") || binary.contains("fb:common.") || binary.contains("fb:freebase.");
	}

	private boolean badDomain(final Formula formula)
	{
		if (formula instanceof VariableFormula)
			return false;
		if (formula instanceof ValueFormula)
			return badDomain(formula.toString());
		if (formula instanceof JoinFormula)
		{
			final JoinFormula jFormula = (JoinFormula) formula;
			return badDomain(jFormula.relation) || badDomain(jFormula.child);
		}
		if (formula instanceof LambdaFormula)
		{
			final LambdaFormula lambdaFormula = (LambdaFormula) formula;
			return badDomain(lambdaFormula.body);
		}
		if (formula instanceof ReverseFormula)
		{
			final ReverseFormula reverseFormula = (ReverseFormula) formula;
			return badDomain(reverseFormula.child);
		}
		if (formula instanceof NotFormula)
		{
			final NotFormula notFormula = (NotFormula) formula;
			return badDomain(notFormula.child);
		}
		throw new RuntimeException("Binary has formula type that is not supported");
	}

	// generate from example array of content word tokens/lemmas/stems that are not dominated by child derivations
	private List<List<String>> generateExampleInfo(final Example ex, final Callable c)
	{

		final List<String> tokens = new ArrayList<>();
		final List<String> posTags = new ArrayList<>();
		final List<String> lemmas = new ArrayList<>();
		final List<List<String>> res = new ArrayList<>();
		res.add(tokens);
		res.add(posTags);
		res.add(lemmas);

		final Derivation modifierDeriv = headFirst ? c.child(1) : c.child(0);

		for (int i = 0; i < ex.languageInfo.tokens.size(); ++i)
		{
			if (i >= modifierDeriv.start && i < modifierDeriv.end)
				continue;
			tokens.add(ex.languageInfo.tokens.get(i));
			posTags.add(ex.languageInfo.posTags.get(i));
			lemmas.add(ex.languageInfo.lemmaTokens.get(i));
		}
		return res;
	}

	private DerivationStream injectIntoCvt(final Example ex, final Callable c)
	{
		assert ex != null;

		if (opts.verbose >= 2)
			LogInfo.logs("child1=%s, child2=%s", ex.phrase(c.child(0).start, c.child(0).end), ex.phrase(c.child(1).start, c.child(1).end));

		// Example: modifier[Braveheart] head[Mel Gibson plays in]
		final Derivation headDeriv = headFirst ? c.child(0) : c.child(1);
		if (!isCvt(headDeriv)) // only works on cvts
			return new LazyBridgeFnDerivs(new ArrayList<>());
		final Derivation modifierDeriv = !headFirst ? c.child(0) : c.child(1);
		final JoinFormula headFormula = (JoinFormula) Formulas.betaReduction(headDeriv.formula);
		// find the type of the cvt node
		final Set<String> headTypes = Collections.singleton(fbFormulaInfo.getBinaryInfo(headFormula.relation).expectedType2);
		final Set<String> modifierTypes = getSupertypes(modifierDeriv.type, new HashSet<>());
		final ArrayList<BridgingInfo> bridgingInfoList = new ArrayList<>();

		for (final String modifierType : modifierTypes)
		{
			final List<Formula> binaries = fbFormulaInfo.getAtomicBinariesForType2(modifierType); // here we use atomic binaries since we inject into a CVT
			for (final Formula binary : binaries)
			{ // For each possible binary...
				if (opts.filterBadDomain && badDomain(binary))
					continue;
				final BinaryFormulaInfo info = fbFormulaInfo.getBinaryInfo(binary);

				if (headTypes.contains(info.expectedType1))
				{
					final BridgingInfo bridgingInfo = new BridgingInfo(ex, c, info, headFirst, headDeriv, modifierDeriv);
					bridgingInfoList.add(bridgingInfo);
				}
			}
		}
		Collections.sort(bridgingInfoList);
		return new LazyBridgeFnDerivs(bridgingInfoList);
	}

	// Checks whether "var" is used as a binary in "formula"
	private boolean varIsBinary(final Formula formula, final String var)
	{
		boolean isBinary = false;
		final LispTree tree = formula.toLispTree();
		final VariableFormula vf = new VariableFormula(var);
		for (final LispTree child : tree.children)
		{
			if (child.isLeaf())
				continue;
			if (child.children.size() == 2 && vf.equals(Formulas.fromLispTree(child.child(0))))
			{
				isBinary = true;
				break;
			}
			if (varIsBinary(Formulas.fromLispTree(child), var))
			{
				isBinary = true;
				break;
			}
		}
		return isBinary;
	}

	private Formula buildBridge(final Formula headFormula, final Formula modifierFormula, final Formula binary)
	{
		// Handle cases like "what state has the most cities" where "has the" is mapped
		// to "contains" predicate via bridging but "most" triggers a nested lambda w/
		// argmax on a count relation
		// (Corresponds to $MetaMetaOperator in grammar)
		if (modifierFormula instanceof LambdaFormula)
		{
			final LambdaFormula lf = (LambdaFormula) modifierFormula;
			if (varIsBinary(lf, lf.var))
			{
				final Formula newBinary = Formulas.lambdaApply(lf, binary);
				if (newBinary instanceof LambdaFormula)
					return Formulas.lambdaApply((LambdaFormula) newBinary, headFormula);
			}
		}

		final Formula join = new JoinFormula(binary, modifierFormula);
		final Formula merge = new MergeFormula(Mode.and, headFormula, join);
		// Don't merge on ints and floats
		return headFormula.equals(intFormula) || headFormula.equals(floatFormula) ? join : merge;
	}

	private Formula buildBridgeFromCvt(final JoinFormula headFormula, final Formula modifierFormula, final Formula binary)
	{
		final Formula join = new JoinFormula(binary, modifierFormula);
		final Formula merge = new MergeFormula(Mode.and, headFormula.child, join);
		return new JoinFormula(headFormula.relation, merge);
	}

	public static FeatureVector getBinaryBridgeFeatures(final BinaryFormulaInfo bInfo)
	{
		final FeatureVector features = new FeatureVector();
		if (opts.useBinaryPredicateFeatures)
			features.add("BridgeFn", "binary=" + bInfo.formula);
		features.add("BridgeFn", "domain=" + bInfo.extractDomain(bInfo.formula));
		features.addWithBias("BridgeFn", "popularity", Math.log(bInfo.popularity + 1));
		return features;
	}

	/**
	 * Lazy iterator for bridging - we always have the next derivation ready since it is possible that items in the list do not produce a derivation
	 */
	public class LazyBridgeFnDerivs extends MultipleDerivationStream
	{

		private final ArrayList<BridgingInfo> bridgingInfoList;
		private int currIndex = 0;

		public LazyBridgeFnDerivs(final ArrayList<BridgingInfo> bridgingInfoList)
		{
			this.bridgingInfoList = bridgingInfoList;
		}

		@Override
		public int estimatedSize()
		{
			return bridgingInfoList.size() - currIndex;
		}

		@Override
		public Derivation createDerivation()
		{
			if (currIndex == bridgingInfoList.size())
				return null;
			Derivation res;
			switch (description)
			{
				case "unary":
					res = nextUnary();
					break;
				case "inject":
					res = nextInject();
					break;
				case "entity":
					res = nextEntity();
					break;
				default:
					throw new RuntimeException("Bad description " + description);
			}
			if (opts.verbose >= 2)
				LogInfo.logs("mode=%s,deriv=%s", description, res);
			return res;
		}

		// not every BridgingInfo produces a derivation so we iterate until we find one
		private Derivation nextEntity()
		{

			if (opts.verbose >= 3)
				LogInfo.begin_track("Compute next entity");
			final BridgingInfo currBridgingInfo = bridgingInfoList.get(currIndex++);
			if (opts.verbose >= 2)
				LogInfo.logs("BridgeFn.nextEntity: binary=%s, popularity=%s", currBridgingInfo.bInfo.formula, currBridgingInfo.bInfo.popularity);
			final List<List<String>> exampleInfo = generateExampleInfo(currBridgingInfo.ex, currBridgingInfo.c); // this is not done in text to text matcher so done here
			final Formula join = new JoinFormula(currBridgingInfo.bInfo.formula, currBridgingInfo.modifierDeriv.formula);

			final Derivation res = new Derivation.Builder().withCallable(currBridgingInfo.c).formula(join).type(SemType.newAtomicSemType(currBridgingInfo.bInfo.expectedType1)).createDerivation();

			if (SemanticFn.opts.trackLocalChoices)
				res.addLocalChoice(String.format("BridgeFn: entity %s --> %s %s", currBridgingInfo.bInfo.formula, currBridgingInfo.modifierDeriv.startEndString(currBridgingInfo.ex.getTokens()), currBridgingInfo.modifierDeriv.formula));

			if (opts.verbose >= 2)
				LogInfo.logs("BridgeStringFn: %s", join);

			// features
			res.addFeature("BridgeFn", "entity");
			res.addFeatures(getBinaryBridgeFeatures(currBridgingInfo.bInfo));

			// Adds dependencies for every bridging relation/entity
			if (FeatureExtractor.containsDomain("dependencyBridge"))
				addBridgeDependency(res, currBridgingInfo, "entity");

			final FeatureVector textMatchFeatures = textToTextMatcher.extractFeatures(exampleInfo.get(0), exampleInfo.get(1), exampleInfo.get(2), new HashSet<>(currBridgingInfo.bInfo.descriptions));
			res.addFeatures(textMatchFeatures);

			if (opts.verbose >= 3)
				LogInfo.end_track();
			return res;
		}

		/* Adds dependencies for every bridging relation/entity. */
		private void addBridgeDependency(final Derivation res, final BridgingInfo currBridgingInfo, final String type)
		{
			final List<List<DependencyEdge>> deps = currBridgingInfo.ex.languageInfo.dependencyChildren;
			final Derivation entityDeriv = currBridgingInfo.modifierDeriv;
			for (int currWord = 0; currWord < deps.size(); currWord++)
			{
				if (entityDeriv.containsIndex(currWord))
					continue;
				for (final DependencyEdge e : deps.get(currWord))
					if (entityDeriv.containsIndex(e.modifier))
						res.addFeature("dependencyBridge", "type=" + type + "," + e.label + " -- " + "relation=" + currBridgingInfo.bInfo.formula);
			}
		}

		private Derivation nextInject()
		{
			final BridgingInfo currBridgingInfo = bridgingInfoList.get(currIndex++);
			if (opts.verbose >= 2)
				LogInfo.logs("BridgingFn.nextInject: binary=%s, popularity=%s", currBridgingInfo.bInfo.formula, currBridgingInfo.bInfo.popularity);
			final JoinFormula headFormula = (JoinFormula) Formulas.betaReduction(currBridgingInfo.headDeriv.formula);

			final Formula bridgedFormula = buildBridgeFromCvt(headFormula, currBridgingInfo.modifierDeriv.formula, currBridgingInfo.bInfo.formula);
			final Derivation res = new Derivation.Builder().withCallable(currBridgingInfo.c).formula(bridgedFormula).type(currBridgingInfo.headDeriv.type).createDerivation();
			if (SemanticFn.opts.trackLocalChoices)
				res.addLocalChoice(String.format("BridgeFn: %s %s --> %s %s --> %s %s", currBridgingInfo.headDeriv.startEndString(currBridgingInfo.ex.getTokens()), currBridgingInfo.headDeriv.formula, currBridgingInfo.ex.getTokens().subList(currBridgingInfo.c.child(0).end, currBridgingInfo.c.child(1).start), currBridgingInfo.bInfo.formula, currBridgingInfo.modifierDeriv.startEndString(currBridgingInfo.ex.getTokens()), currBridgingInfo.modifierDeriv.formula));

			if (opts.verbose >= 3)
				LogInfo.logs("BridgeFn: injecting %s to %s --> %s ", currBridgingInfo.modifierDeriv.formula, headFormula, bridgedFormula);

			final String headModifierOrder = headFirst ? "head-modifier" : "modifier-head";
			res.addFeature("BridgeFn", "inject_order=" + headModifierOrder + "," + "pos=" + currBridgingInfo.ex.languageInfo.getCanonicalPos(currBridgingInfo.headDeriv.start) + "-" + currBridgingInfo.ex.languageInfo.getCanonicalPos(currBridgingInfo.modifierDeriv.start));

			res.addFeature("BridgeFn", "binary=" + currBridgingInfo.bInfo.formula);
			res.addFeature("BridgeFn", "domain=" + currBridgingInfo.bInfo.extractDomain(currBridgingInfo.bInfo.formula));
			res.addFeatureWithBias("BridgeFn", "popularity", Math.log(currBridgingInfo.bInfo.popularity + 1));
			/* Adds dependencies for every bridging relation/entity */
			if (FeatureExtractor.containsDomain("dependencyBridge"))
				addBridgeDependency(res, currBridgingInfo, "inject");
			return res;
		}

		private Derivation nextUnary()
		{
			final BridgingInfo currBridgingInfo = bridgingInfoList.get(currIndex++);

			if (opts.verbose >= 2)
				LogInfo.logs("BridgingFn.nextUnary: binary=%s, popularity=%s", currBridgingInfo.bInfo.formula, currBridgingInfo.bInfo.popularity);

			final Formula bridgedFormula = buildBridge(currBridgingInfo.headDeriv.formula, currBridgingInfo.modifierDeriv.formula, currBridgingInfo.bInfo.formula);

			final Derivation res = new Derivation.Builder().withCallable(currBridgingInfo.c).formula(bridgedFormula).type(currBridgingInfo.headDeriv.type).createDerivation();

			if (SemanticFn.opts.trackLocalChoices)
				res.addLocalChoice(String.format("BridgeFn: %s %s --> %s %s --> %s %s", currBridgingInfo.headDeriv.startEndString(currBridgingInfo.ex.getTokens()), currBridgingInfo.headDeriv.formula, currBridgingInfo.ex.getTokens().subList(currBridgingInfo.c.child(0).end, currBridgingInfo.c.child(1).start), currBridgingInfo.bInfo.formula, currBridgingInfo.modifierDeriv.startEndString(currBridgingInfo.ex.getTokens()), currBridgingInfo.modifierDeriv.formula));

			// Add features
			res.addFeature("BridgeFn", "unary");
			res.addFeatures(getBinaryBridgeFeatures(currBridgingInfo.bInfo));

			// head modifier POS tags
			final String headModifierOrder = headFirst ? "head-modifier" : "modifier-head";
			res.addFeature("BridgeFn", "order=" + headModifierOrder + "," + "pos=" + currBridgingInfo.ex.languageInfo.getCanonicalPos(currBridgingInfo.headDeriv.start) + "-" + currBridgingInfo.ex.languageInfo.getCanonicalPos(currBridgingInfo.modifierDeriv.start));

			final List<List<String>> exampleInfo = generateExampleInfo(currBridgingInfo.ex, currBridgingInfo.c); // this is not done in text to text matcher so done here

			final FeatureVector vector = textToTextMatcher.extractFeatures(exampleInfo.get(0), exampleInfo.get(1), exampleInfo.get(2), new HashSet<>(currBridgingInfo.bInfo.descriptions));

			res.addFeatures(vector);
			/* Adds dependencies for every bridging relation/entity */
			if (FeatureExtractor.containsDomain("dependencyBridge"))
				addBridgeDependency(res, currBridgingInfo, "unary");
			return res;
		}

		@Override
		public void remove()
		{
			throw new RuntimeException("Does not support remove");
		}
	}

	public class BridgingInfo implements Comparable<BridgingInfo>
	{
		public final Example ex;
		public final Callable c;
		public final BinaryFormulaInfo bInfo;
		public final boolean headFirst;
		public final Derivation headDeriv;
		public final Derivation modifierDeriv;

		public BridgingInfo(final Example ex, final Callable c, final BinaryFormulaInfo bInfo, final boolean headFirst, final Derivation headDeriv, final Derivation modifierDeriv)
		{
			this.ex = ex;
			this.c = c;
			this.bInfo = bInfo;
			this.headFirst = headFirst;
			this.headDeriv = headDeriv;
			this.modifierDeriv = modifierDeriv;
		}

		@Override
		public int compareTo(final BridgingInfo o)
		{
			return fbFormulaInfo.compare(bInfo.formula, o.bInfo.formula);
		}
	}
}
