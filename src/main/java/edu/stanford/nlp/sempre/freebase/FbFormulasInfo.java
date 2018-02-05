package edu.stanford.nlp.sempre.freebase;

import com.google.common.base.Joiner;
import edu.stanford.nlp.sempre.FeatureVector;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.Formulas;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.LambdaFormula;
import edu.stanford.nlp.sempre.Params;
import edu.stanford.nlp.sempre.SemType;
import edu.stanford.nlp.sempre.VariableFormula;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import fig.basic.MapUtils;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Class for keeping info and manipulating FB formulas. For example, given a Freebase formula computes the reverse of the formula (flipping arguments) and the
 * equivalent formula (using reverse property from Freebase) Reversing works now only for chains
 *
 * @author jonathanberant
 */
public final class FbFormulasInfo
{
	// Everyone should use the singleton.
	private static FbFormulasInfo fbFormulaInfo;

	public static FbFormulasInfo getSingleton()
	{
		if (fbFormulaInfo == null)
			fbFormulaInfo = new FbFormulasInfo();
		return fbFormulaInfo;
	}

	private FreebaseInfo freebaseInfo = null;
	public Map<Formula, BinaryFormulaInfo> binaryFormulaInfoMap = new HashMap<>();
	public Map<Formula, UnaryFormulaInfo> unaryFormulaInfoMap = new HashMap<>();
	private final Map<String, Set<BinaryFormulaInfo>> typeToNumericalPredicates = new HashMap<>();
	private final Map<String, List<Formula>> atomicExtype2ToBinaryMap = new HashMap<>(); // contains map to all atomic properties
	private final Map<String, List<Formula>> extype2ToNonCvtBinaryMap = new HashMap<>(); // contains map to all binaries for which extype 1 is not a CVT
	private final Map<Formula, Set<BinaryFormulaInfo>> cvtExpansionsMap = new HashMap<>();
	private final Map<String, Set<Formula>> cvtTypeToBinaries = new HashMap<>();

	private Comparator<Formula> formulaComparator;

	private FbFormulasInfo()
	{
		try
		{
			freebaseInfo = FreebaseInfo.getSingleton();
			loadFormulaInfo();
			computeNumericalPredicatesMap();
		}
		catch (IOException | NumberFormatException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Map that given a type provides all Freebase predicates that have that type as expected type 2 and a number for expected type 1
	 */
	private void computeNumericalPredicatesMap()
	{
		for (final BinaryFormulaInfo info : binaryFormulaInfoMap.values())
			if (info.expectedType1.equals("fb:type.int") || info.expectedType1.equals("fb:type.float"))
				MapUtils.addToSet(typeToNumericalPredicates, info.expectedType2, info);
	}

	public Set<BinaryFormulaInfo> getNumericalPredicates(final String expectedType)
	{
		return MapUtils.get(typeToNumericalPredicates, expectedType, new HashSet<BinaryFormulaInfo>());
	}

	private void computeReverseFormulaInfo()
	{
		final List<BinaryFormulaInfo> entriesToAdd = new LinkedList<>();
		for (final Formula formula : binaryFormulaInfoMap.keySet())
		{
			final BinaryFormulaInfo info = binaryFormulaInfoMap.get(formula);
			final Formula reverseFormula = Formulas.reverseFormula(formula);

			if (!binaryFormulaInfoMap.containsKey(reverseFormula))
				entriesToAdd.add(new BinaryFormulaInfo(reverseFormula, info.expectedType2, info.expectedType1, info.unitId, info.unitDesc, info.descriptions, info.popularity));
		}
		LogInfo.log("Adding reverse formulas: " + entriesToAdd.size());
		for (final BinaryFormulaInfo e : entriesToAdd)
			binaryFormulaInfoMap.put(e.formula, e);
	}

	public BinaryFormulaInfo getBinaryInfo(final Formula formula)
	{
		return binaryFormulaInfoMap.get(formula);
	}

	public UnaryFormulaInfo getUnaryInfo(final Formula formula)
	{
		return unaryFormulaInfoMap.get(formula);
	}

	private void loadFormulaInfo() throws IOException
	{

		LogInfo.begin_track("Loading formula info...");
		LogInfo.log("Adding schema properties");
		binaryFormulaInfoMap = freebaseInfo.createBinaryFormulaInfoMap();
		unaryFormulaInfoMap = freebaseInfo.createUnaryFormulaInfoMap();
		LogInfo.log("Current number of binary formulas: " + binaryFormulaInfoMap.size());
		LogInfo.log("Current number of unary formulas: " + unaryFormulaInfoMap.size());

		LogInfo.log("Compuing reverse for schema formulas");
		computeReverseFormulaInfo();
		LogInfo.log("Current number of binary formulas: " + binaryFormulaInfoMap.size());
		for (final BinaryFormulaInfo info : binaryFormulaInfoMap.values())
		{

			MapUtils.addToList(atomicExtype2ToBinaryMap, info.expectedType2, info.formula);
			if (!isCvt(info.expectedType1))
				addMappingFromType2ToFormula(info.expectedType2, info.formula);
		}

		LogInfo.log("Generate formulas through CVTs");
		generateCvtFormulas(); // generate formulas for CVTs
		LogInfo.log("Current number of binary formulas: " + binaryFormulaInfoMap.size());
		// we first sort by popularity
		final Comparator<Formula> comparator = getPopularityComparator();
		sortType2ToBinaryMaps(comparator);
		LogInfo.end_track();
	}

	public void sortType2ToBinaryMaps(final Comparator<Formula> comparator)
	{
		formulaComparator = comparator;
		for (final List<Formula> binaries : atomicExtype2ToBinaryMap.values())
			Collections.sort(binaries, comparator);

		for (final List<Formula> binaries : extype2ToNonCvtBinaryMap.values())
			Collections.sort(binaries, comparator);

	}

	public int compare(final Formula f1, final Formula f2)
	{
		return formulaComparator.compare(f1, f2);
	}

	/**
	 * Adding mapping from type 2 to formula - makes sure to insert just one of the 2 equivalent formulas if they exist
	 */
	private void addMappingFromType2ToFormula(final String type2, final Formula formula)
	{
		MapUtils.addToList(extype2ToNonCvtBinaryMap, type2, formula);
	}

	private void generateCvtFormulas() throws FileNotFoundException
	{

		final List<BinaryFormulaInfo> toAdd = new ArrayList<>();
		for (final BinaryFormulaInfo innerInfo : binaryFormulaInfoMap.values())
			if (isCvt(innerInfo.expectedType1))
			{ // if expected type 1 is a CVT
				MapUtils.addToSet(cvtTypeToBinaries, innerInfo.expectedType1, innerInfo.formula);

				final List<Formula> outers = atomicExtype2ToBinaryMap.get(innerInfo.expectedType1); // find those whose expected type 2 is that CVT
				for (final Formula outer : outers)
				{
					final BinaryFormulaInfo outerInfo = binaryFormulaInfoMap.get(outer);
					if (!isLegalCvt(innerInfo.formula, outer))
						continue;

					// build new formula
					final LambdaFormula cvtFormula = new LambdaFormula("x", new JoinFormula(outer, new JoinFormula(innerInfo.formula, new VariableFormula("x"))));

					BinaryFormulaInfo newFormulaInfo = binaryFormulaInfoMap.get(cvtFormula);
					if (newFormulaInfo == null)
					{
						final String exType1 = outerInfo.expectedType1;
						if (exType1 == null)
							throw new RuntimeException("Missing expected type 1 for formula: " + outer);

						final List<String> newDescriptions = new LinkedList<>();
						newDescriptions.add(outerInfo.descriptions.get(0));
						newDescriptions.add(innerInfo.descriptions.get(0));

						newFormulaInfo = new BinaryFormulaInfo(cvtFormula, exType1, innerInfo.expectedType2, newDescriptions, Math.min(outerInfo.popularity, innerInfo.popularity));
						toAdd.add(newFormulaInfo);
					}
					MapUtils.addToSet(cvtExpansionsMap, innerInfo.formula, newFormulaInfo);
					MapUtils.addToSet(cvtExpansionsMap, outerInfo.formula, newFormulaInfo);
				}
			}
		for (final BinaryFormulaInfo info : toAdd)
		{
			addMappingFromType2ToFormula(info.expectedType2, info.formula);
			binaryFormulaInfoMap.put(info.formula, info);
		}
	}

	private boolean isLegalCvt(final Formula inner, final Formula outer)
	{
		if (FreebaseInfo.isReverseProperty(inner.toString()) && !FreebaseInfo.isReverseProperty(outer.toString()))
			return false;
		if (!FreebaseInfo.isReverseProperty(inner.toString()) && FreebaseInfo.isReverseProperty(outer.toString()))
			return false;
		return true;
	}

	/** supports chains only */
	public boolean hasOpposite(final String formula)
	{
		return hasOpposite(LispTree.proto.parseFromString(formula));
	}

	public boolean hasOpposite(final Formula formula)
	{
		return hasOpposite(formula.toLispTree());
	}

	private boolean hasOpposite(final LispTree tree)
	{
		if (tree.isLeaf())
		{
			final String fbProperty = FreebaseInfo.isReverseProperty(tree.value) ? tree.value.substring(1) : tree.value;
			return freebaseInfo.propertyHasOpposite(fbProperty);
		}
		else
		{
			// Un-reverse everything.
			String binary1 = tree.child(2).child(0).value;
			binary1 = FreebaseInfo.isReverseProperty(binary1) ? binary1.substring(1) : binary1;
			String binary2 = tree.child(2).child(1).child(0).value;
			binary2 = FreebaseInfo.isReverseProperty(binary2) ? binary2.substring(1) : binary2;
			return freebaseInfo.propertyHasOpposite(binary1) && freebaseInfo.propertyHasOpposite(binary2);
		}
	}

	/** supports chains only */
	public boolean isReversed(final Formula formula)
	{
		final LispTree tree = formula.toLispTree();
		if (tree.isLeaf())
			return FreebaseInfo.isReverseProperty(tree.value);
		else
			return FreebaseInfo.isReverseProperty(tree.child(2).child(0).value);
	}

	/** assumes we checked there is an opposite formula */
	public Formula equivalentFormula(final String formula)
	{
		final LispTree tree = LispTree.proto.parseFromString(formula);
		return equivalentFormula(tree);
	}

	public Formula equivalentFormula(final Formula formula)
	{
		final LispTree tree = formula.toLispTree();
		return equivalentFormula(tree);
	}

	// two formulas can be equivalent because there are two names for every edge using the reverse label
	//fb:people.person.place_of_birth --> !fb:location.location.people_born_here
	//!fb:people.person.place_of_birth --> fb:location.location.people_born_here
	public Formula equivalentFormula(final LispTree tree)
	{

		if (tree.isLeaf())
		{
			final boolean rev = FreebaseInfo.isReverseProperty(tree.value);
			final String fbProperty = rev ? tree.value.substring(1) : tree.value;
			final String oppositeProperty = freebaseInfo.getOppositeFbProperty(fbProperty);
			return rev ? Formulas.newNameFormula(oppositeProperty) : Formulas.newNameFormula("!" + oppositeProperty);
		}
		else
		{
			String binary1 = tree.child(2).child(0).value;
			binary1 = FreebaseInfo.isReverseProperty(binary1) ? binary1.substring(1) : binary1;
			String binary2 = tree.child(2).child(1).child(0).value;
			binary2 = FreebaseInfo.isReverseProperty(binary2) ? binary2.substring(1) : binary2;
			final String oppositeBinary1 = freebaseInfo.getOppositeFbProperty(binary1);
			final String oppositeBinary2 = freebaseInfo.getOppositeFbProperty(binary2);
			final boolean rev = FreebaseInfo.isReverseProperty(tree.child(2).child(0).value);
			return buildLambdaFormula(oppositeBinary1, oppositeBinary2, !rev);
		}
	}

	//input: |binary1|=fb:people.place_lived.location,
	// |binary2|=fb:people.person.places_lived, |reverse|=true
	//output: (lambda x (!fb:people.place_lived.location (!fb:people.person.places_lived (var x))))
	public static Formula buildLambdaFormula(final String binary1, final String binary2, final boolean reverse)
	{

		final Formula binary1Formula = reverse ? Formulas.newNameFormula("!" + binary1) : Formulas.newNameFormula(binary1);
		final Formula binary2Formula = reverse ? Formulas.newNameFormula("!" + binary2) : Formulas.newNameFormula(binary2);
		final Formula join1 = new JoinFormula(binary2Formula, new VariableFormula("x"));
		final Formula join2 = new JoinFormula(binary1Formula, join1);
		return new LambdaFormula("x", join2);
	}

	//for binary formulas that are paths in the graph, if formula1 is a path s-->t
	//then formula2 is the opposite if it is the path t-->s
	// fb:people.person.place_of_birth is the opposite of !fb:people.person.place_of_birth
	// fb:people.person.place_of_birth is the opposite of fb:
	private boolean isOpposite(final Formula formula1, final Formula formula2)
	{

		if (isReversed(formula1) && !isReversed(formula2))
		{
			final String formula1Desc = formula1.toString().substring(1);
			return formula1Desc.equals(formula2.toString());
		}
		if (isReversed(formula2) && !isReversed(formula1))
		{
			final String formula2Desc = formula2.toString().substring(1);
			return formula2Desc.equals(formula1.toString());
		}
		if (hasOpposite(formula1))
		{
			final Formula equivalentFormula = equivalentFormula(formula1);
			if (isReversed(equivalentFormula))
			{
				final String equivalentFormaulDesc = equivalentFormula.toString().substring(1);
				return equivalentFormaulDesc.equals(formula2.toString());
			}
			else
			{
				final String formula2Desc = formula2.toString().substring(1);
				return formula2Desc.equals(equivalentFormula.toString());
			}
		}
		return false;
	}

	public List<Formula> getBinariesForType2(final String type)
	{
		return MapUtils.get(extype2ToNonCvtBinaryMap, type, new ArrayList<Formula>());
	}

	public List<Formula> getAtomicBinariesForType2(final String type)
	{
		return MapUtils.get(atomicExtype2ToBinaryMap, type, new ArrayList<Formula>());
	}

	public boolean isCvtFormula(final BinaryFormulaInfo info)
	{
		return isCvt(info.expectedType1) || isCvt(info.expectedType2);
	}

	public Set<BinaryFormulaInfo> getCvtExpansions(final BinaryFormulaInfo info)
	{
		return MapUtils.getSet(cvtExpansionsMap, info.formula);
	}

	public Set<Formula> expandCvts(final String cvt)
	{
		return MapUtils.getSet(cvtTypeToBinaries, cvt);
	}

	//For a binary lambda formula that goes through CVTs, find all binaries that can
	//be injected to this lambda binary formula.
	//example:
	//input: (lambda (fb:people.person.places_lived (fb:people.place_lived.location (var x))))
	//output: fb:people.place_lived/start_date, fb:people.place_lived.end_date
	public List<Formula> getInjectableBinaries(final Formula formula)
	{
		final List<Formula> res = new ArrayList<>();
		if (!(formula instanceof LambdaFormula))
			return res;
		final LambdaFormula lambdaFormula = (LambdaFormula) formula;
		final Formula first = ((JoinFormula) lambdaFormula.body).relation;
		final Formula second = ((JoinFormula) ((JoinFormula) lambdaFormula.body).child).relation;
		final Set<Formula> binaryFormulas = expandCvts(getBinaryInfo(first).expectedType2);

		for (final Formula binaryFormula : binaryFormulas)
			if (!second.equals(binaryFormula) && !isOpposite(first, binaryFormula))
				res.add(binaryFormula);
		return res;
	}

	public boolean isCvt(final String type)
	{
		return freebaseInfo.isCvt(type);
	}

	public Comparator<Formula> getPopularityComparator()
	{
		final Counter<Formula> counter = new ClassicCounter<>();
		for (final Formula binaryFormula : binaryFormulaInfoMap.keySet())
			counter.incrementCount(binaryFormula, binaryFormulaInfoMap.get(binaryFormula).popularity);

		return new FormulaByCounterComparator(counter);
	}

	public class FormulaByCounterComparator implements Comparator<Formula>
	{

		private final Counter<Formula> fCounter;

		public FormulaByCounterComparator(final Counter<Formula> fCounter)
		{
			this.fCounter = fCounter;
		}

		public int compare(final Formula f1, final Formula f2)
		{
			final double count1 = fCounter.getCount(f1);
			final double count2 = fCounter.getCount(f2);
			if (count1 > count2)
				return -1;
			if (count1 < count2)
				return +1;
			final double pop1 = binaryFormulaInfoMap.get(f1).popularity;
			final double pop2 = binaryFormulaInfoMap.get(f2).popularity;
			if (pop1 > pop2)
				return -1;
			if (pop1 < pop2)
				return +1;
			return 0;
		}

		public double getCount(final Formula f)
		{
			return fCounter.getCount(f);
		}
	}

	public class FormulaByFeaturesComparator implements Comparator<Formula>
	{

		private final Params params;

		public FormulaByFeaturesComparator(final Params params)
		{
			this.params = params;
		}

		public int compare(final Formula f1, final Formula f2)
		{

			final FeatureVector features1 = BridgeFn.getBinaryBridgeFeatures(fbFormulaInfo.getBinaryInfo(f1));
			final FeatureVector features2 = BridgeFn.getBinaryBridgeFeatures(fbFormulaInfo.getBinaryInfo(f2));

			final double score1 = features1.dotProduct(params);
			final double score2 = features2.dotProduct(params);
			if (score1 > score2)
				return -1;
			if (score1 < score2)
				return +1;
			final double pop1 = binaryFormulaInfoMap.get(f1).popularity;
			final double pop2 = binaryFormulaInfoMap.get(f2).popularity;
			if (pop1 > pop2)
				return -1;
			if (pop1 < pop2)
				return +1;
			return 0;
		}
	}

	//Information from freebase about binary formulas
	public static class BinaryFormulaInfo
	{
		public Formula formula; //fb:people.person.place_of_birth
		public String expectedType1; //fb:people.person
		public String expectedType2; //fb:location.location
		public String unitId = ""; //fb:en.meter
		public String unitDesc = ""; //Meter
		public List<String> descriptions = new LinkedList<>(); // "place of birth"
		public double popularity; //Number of instances of binary in KB: 16184.0

		public BinaryFormulaInfo(final Formula formula, final String exType1, final String exType2, final List<String> descs, final double popularity)
		{
			this.formula = formula;
			expectedType1 = exType1;
			expectedType2 = exType2;
			descriptions = descs;
			this.popularity = popularity;
			unitId = "";
			unitDesc = "";
		}

		public BinaryFormulaInfo(final Formula formula, final String exType1, final String exType2, final String unitId, final String unitDesc, final List<String> descs, final double popularity)
		{
			this.formula = formula;
			expectedType1 = exType1;
			expectedType2 = exType2;
			descriptions = descs;
			this.popularity = popularity;
			this.unitId = "";
			this.unitDesc = "";
		}

		public String toString()
		{
			return formula.toString() + "\t" + popularity + "\t" + expectedType1 + "\t" + expectedType2 + "\t" + unitId + "\t" + unitDesc + "\t" + Joiner.on("###").join(descriptions);
		}

		public String toReverseString()
		{
			return Formulas.reverseFormula(formula).toString() + "\t" + popularity + "\t" + expectedType2 + "\t" + expectedType1 + "\t" + unitId + "\t" + unitDesc + "\t" + Joiner.on("###").join(descriptions);
		}

		public static List<String> tokenizeFbDescription(final String fbDesc)
		{
			final List<String> res = new ArrayList<>();
			final String[] tokens = fbDesc.split("\\s+");
			for (String token : tokens)
			{
				token = token.replace("(", "");
				token = token.replace(")", "");
				token = token.replace("\"", "");
				res.add(token);
			}
			return res;
		}

		public boolean isComplete()
		{
			if (formula == null || expectedType1 == null || expectedType2 == null || expectedType1.equals("") || expectedType2.equals("") || descriptions == null || descriptions.size() == 0 || popularity == 0.0)
				return false;
			return true;
		}

		public SemType getSemType()
		{
			return SemType.newFuncSemType(expectedType2, expectedType1);
		}

		public String extractDomain(final Formula binary)
		{
			final LispTree tree = binary.toLispTree();
			String property = tree.isLeaf() ? tree.value : tree.child(2).child(0).value;
			if (property.startsWith("!"))
				property = property.substring(1);
			return property.substring(0, property.indexOf('.'));
		}
	}

	public static class UnaryFormulaInfo
	{

		public Formula formula;
		public double popularity;
		public List<String> descriptions;
		public Set<String> types;

		public UnaryFormulaInfo(final Formula formula, final double popularity, final List<String> descriptions, final Set<String> types)
		{

			this.formula = formula;
			this.popularity = popularity;
			this.descriptions = descriptions;
			this.types = types;
		}

		public boolean isComplete()
		{
			if (formula == null || descriptions == null || descriptions.size() == 0 || popularity == 0.0)
				return false;
			return true;
		}

		public String toString()
		{
			return formula + "\t" + popularity + "\t" + Joiner.on("###").join(descriptions);
		}

		public String getRepresentativeDescrption()
		{
			if (descriptions.get(0).contains("/") && descriptions.size() > 1)
				return descriptions.get(1);
			return descriptions.get(0);
		}
	}
}
