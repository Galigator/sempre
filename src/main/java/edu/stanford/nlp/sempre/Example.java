package edu.stanford.nlp.sempre;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Joiner;
import com.google.common.collect.Sets;
import fig.basic.Evaluation;
import fig.basic.LispTree;
import fig.basic.LogInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An example corresponds roughly to an input-output pair, the basic unit which we make predictions on. The Example object stores both the input, preprocessing,
 * and output of the parser.
 *
 * @author Percy Liang
 * @author Roy Frostig
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Example
{
	//// Information from the input file.

	// Unique identifier for this example.
	@JsonProperty
	public final String id;

	// Input utterance
	@JsonProperty
	public final String utterance;

	// Context
	@JsonProperty
	public ContextValue context;

	// What we should try to predict.
	@JsonProperty
	public Formula targetFormula; // Logical form (e.g., database query)
	public List<Formula> alternativeFormulas; // Alternative logical form (less canonical)
	@JsonProperty
	public Value targetValue; // Denotation (e.g., answer)

	//// Information after preprocessing (e.g., tokenization, POS tagging, NER, syntactic parsing, etc.).
	public LanguageInfo languageInfo = null;

	//// Output of the parser.

	// Predicted derivations (sorted by score).
	public List<Derivation> predDerivations;

	// Temporary state while parsing an Example (see Derivation.java for analogous structure).
	private Map<String, Object> tempState;

	// Statistics relating to processing the example.
	public Evaluation evaluation;

	public static class Builder
	{
		private String id;
		private String utterance;
		private ContextValue context;
		private Formula targetFormula;
		private Value targetValue;
		private LanguageInfo languageInfo;

		public Builder setId(final String id_)
		{
			id = id_;
			return this;
		}

		public Builder setUtterance(final String utterance_)
		{
			utterance = utterance_;
			return this;
		}

		public Builder setContext(final ContextValue context_)
		{
			context = context_;
			return this;
		}

		public Builder setTargetFormula(final Formula targetFormula_)
		{
			targetFormula = targetFormula_;
			return this;
		}

		public Builder setTargetValue(final Value targetValue_)
		{
			targetValue = targetValue_;
			return this;
		}

		public Builder setLanguageInfo(final LanguageInfo languageInfo_)
		{
			languageInfo = languageInfo_;
			return this;
		}

		public Builder withExample(final Example ex)
		{
			setId(ex.id);
			setUtterance(ex.utterance);
			setContext(ex.context);
			setTargetFormula(ex.targetFormula);
			setTargetValue(ex.targetValue);
			return this;
		}

		public Example createExample()
		{
			return new Example(id, utterance, context, targetFormula, targetValue, languageInfo);
		}
	}

	@JsonCreator
	public Example(@JsonProperty("id") final String id_, @JsonProperty("utterance") final String utterance_, @JsonProperty("context") final ContextValue context_, @JsonProperty("targetFormula") final Formula targetFormula_, @JsonProperty("targetValue") final Value targetValue_, @JsonProperty("languageInfo") final LanguageInfo languageInfo_)
	{
		id = id_;
		utterance = utterance_;
		context = context_;
		targetFormula = targetFormula_;
		targetValue = targetValue_;
		languageInfo = languageInfo_;
	}

	// Accessors
	public String getId()
	{
		return id;
	}

	public String getUtterance()
	{
		return utterance;
	}

	public int numTokens()
	{
		return languageInfo.tokens.size();
	}

	public List<Derivation> getPredDerivations()
	{
		return predDerivations;
	}

	public void setContext(final ContextValue context_)
	{
		context = context_;
	}

	public void setTargetFormula(final Formula targetFormula_)
	{
		targetFormula = targetFormula_;
	}

	public void setAlternativeFormulas(final List<Formula> alternativeFormulas_)
	{
		alternativeFormulas = alternativeFormulas_;
	}

	public void addAlternativeFormula(final Formula alternativeFormula)
	{
		if (alternativeFormulas == null)
			alternativeFormulas = new ArrayList<>();
		alternativeFormulas.add(alternativeFormula);
	}

	public void setTargetValue(final Value targetValue_)
	{
		targetValue = targetValue_;
	}

	public String spanString(final int start, final int end)
	{
		return String.format("%d:%d[%s]", start, end, start != -1 ? phraseString(start, end) : "...");
	}

	public String phraseString(final int start, final int end)
	{
		return Joiner.on(' ').join(languageInfo.tokens.subList(start, end));
	}

	// Return a string representing the tokens between start and end.
	public List<String> getTokens()
	{
		return languageInfo.tokens;
	}

	public List<String> getLemmaTokens()
	{
		return languageInfo.lemmaTokens;
	}

	public String token(final int i)
	{
		return languageInfo.tokens.get(i);
	}

	public String lemmaToken(final int i)
	{
		return languageInfo.lemmaTokens.get(i);
	}

	public String posTag(final int i)
	{
		return languageInfo.posTags.get(i);
	}

	public String phrase(final int start, final int end)
	{
		return languageInfo.phrase(start, end);
	}

	public String lemmaPhrase(final int start, final int end)
	{
		return languageInfo.lemmaPhrase(start, end);
	}

	public String toJson()
	{
		return Json.writeValueAsStringHard(this);
	}

	public static Example fromJson(final String json)
	{
		return Json.readValueHard(json, Example.class);
	}

	public static Example fromLispTree(final LispTree tree, final String defaultId)
	{
		final Builder b = new Builder().setId(defaultId);

		for (int i = 1; i < tree.children.size(); i++)
		{
			final LispTree arg = tree.child(i);
			final String label = arg.child(0).value;
			if ("id".equals(label))
				b.setId(arg.child(1).value);
			else
				if ("utterance".equals(label))
					b.setUtterance(arg.child(1).value);
				else
					if ("canonicalUtterance".equals(label))
						b.setUtterance(arg.child(1).value);
					else
						if ("targetFormula".equals(label))
							b.setTargetFormula(Formulas.fromLispTree(arg.child(1)));
						else
							if ("targetValue".equals(label) || "targetValues".equals(label))
							{
								if (arg.children.size() != 2)
									throw new RuntimeException("Expect one target value");
								b.setTargetValue(Values.fromLispTree(arg.child(1)));
							}
							else
								if ("context".equals(label))
									b.setContext(new ContextValue(arg));
		}
		b.setLanguageInfo(new LanguageInfo());

		final Example ex = b.createExample();

		for (int i = 1; i < tree.children.size(); i++)
		{
			final LispTree arg = tree.child(i);
			final String label = arg.child(0).value;
			if ("tokens".equals(label))
				for (final LispTree child : arg.child(1).children)
					ex.languageInfo.tokens.add(child.value);
			else
				if ("lemmaTokens".equals(label))
					for (final LispTree child : arg.child(1).children)
						ex.languageInfo.lemmaTokens.add(child.value);
				else
					if ("posTags".equals(label))
						for (final LispTree child : arg.child(1).children)
							ex.languageInfo.posTags.add(child.value);
					else
						if ("nerTags".equals(label))
							for (final LispTree child : arg.child(1).children)
								ex.languageInfo.nerTags.add(child.value);
						else
							if ("nerValues".equals(label))
								for (final LispTree child : arg.child(1).children)
									ex.languageInfo.nerValues.add("null".equals(child.value) ? null : child.value);
							else
								if ("alternativeFormula".equals(label))
									ex.addAlternativeFormula(Formulas.fromLispTree(arg.child(1)));
								else
									if ("evaluation".equals(label))
										ex.evaluation = Evaluation.fromLispTree(arg.child(1));
									else
										if ("predDerivations".equals(label))
										{
											// Featurized
											ex.predDerivations = new ArrayList<>();
											for (int j = 1; j < arg.children.size(); j++)
												ex.predDerivations.add(derivationFromLispTree(arg.child(j)));
										}
										else
											if ("rawDerivations".equals(label) || "derivations".equals(label))
											{
												// Unfeaturized
												ex.predDerivations = new ArrayList<>();
												for (int j = 1; j < arg.children.size(); j++)
													ex.predDerivations.add(rawDerivationFromLispTree(arg.child(j)));
											}
											else
												if (!Sets.newHashSet("id", "utterance", "targetFormula", "targetValue", "targetValues", "context", "original").contains(label))
													throw new RuntimeException("Invalid example argument: " + arg);
		}

		return ex;
	}

	public void preprocess()
	{
		languageInfo = LanguageAnalyzer.getSingleton().analyze(utterance);
		targetValue = TargetValuePreprocessor.getSingleton().preprocess(targetValue, this);
	}

	public void log()
	{
		LogInfo.begin_track("Example: %s", utterance);
		LogInfo.logs("Tokens: %s", getTokens());
		LogInfo.logs("Lemmatized tokens: %s", getLemmaTokens());
		LogInfo.logs("POS tags: %s", languageInfo.posTags);
		LogInfo.logs("NER tags: %s", languageInfo.nerTags);
		LogInfo.logs("NER values: %s", languageInfo.nerValues);
		if (context != null)
			LogInfo.logs("context: %s", context);
		if (targetFormula != null)
			LogInfo.logs("targetFormula: %s", targetFormula);
		if (targetValue != null)
			LogInfo.logs("targetValue: %s", targetValue);
		LogInfo.logs("Dependency children: %s", languageInfo.dependencyChildren);
		LogInfo.end_track();
	}

	public void logWithoutContext()
	{
		LogInfo.begin_track("Example: %s", utterance);
		LogInfo.logs("Tokens: %s", getTokens());
		LogInfo.logs("Lemmatized tokens: %s", getLemmaTokens());
		LogInfo.logs("POS tags: %s", languageInfo.posTags);
		LogInfo.logs("NER tags: %s", languageInfo.nerTags);
		LogInfo.logs("NER values: %s", languageInfo.nerValues);
		if (targetFormula != null)
			LogInfo.logs("targetFormula: %s", targetFormula);
		if (targetValue != null)
			LogInfo.logs("targetValue: %s", targetValue);
		LogInfo.logs("Dependency children: %s", languageInfo.dependencyChildren);
		LogInfo.end_track();
	}

	public List<Derivation> getCorrectDerivations()
	{
		final List<Derivation> res = new ArrayList<>();
		for (final Derivation deriv : predDerivations)
		{
			if (deriv.compatibility == Double.NaN)
				throw new RuntimeException("Compatibility is not set");
			if (deriv.compatibility > 0)
				res.add(deriv);
		}
		return res;
	}

	public LispTree toLispTree(final boolean outputPredDerivations)
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("example");

		if (id != null)
			tree.addChild(LispTree.proto.newList("id", id));
		if (utterance != null)
			tree.addChild(LispTree.proto.newList("utterance", utterance));
		if (targetFormula != null)
			tree.addChild(LispTree.proto.newList("targetFormula", targetFormula.toLispTree()));
		if (targetValue != null)
			tree.addChild(LispTree.proto.newList("targetValue", targetValue.toLispTree()));

		if (languageInfo != null)
		{
			if (languageInfo.tokens != null)
				tree.addChild(LispTree.proto.newList("tokens", LispTree.proto.newList(languageInfo.tokens)));
			if (languageInfo.posTags != null)
				tree.addChild(LispTree.proto.newList("posTags", Joiner.on(' ').join(languageInfo.posTags)));
			if (languageInfo.nerTags != null)
				tree.addChild(LispTree.proto.newList("nerTags", Joiner.on(' ').join(languageInfo.nerTags)));
		}

		if (evaluation != null)
			tree.addChild(LispTree.proto.newList("evaluation", evaluation.toLispTree()));

		if (predDerivations != null && outputPredDerivations)
		{
			final LispTree list = LispTree.proto.newList();
			list.addChild("predDerivations");
			for (final Derivation deriv : predDerivations)
				list.addChild(derivationToLispTree(deriv));
			tree.addChild(list);
		}

		return tree;
	}

	/**
	 * Parse a featurized derivation. Format: ({compatibility} {prob} {score} {value|null} {formula} {features}) where {features} = (({key} {value}) ({key}
	 * {value}) ...)
	 * 
	 * @param item is a lisp/sparql formula.
	 * @return a Derivation from the parse
	 */
	public static Derivation derivationFromLispTree(final LispTree item)
	{
		final Derivation.Builder b = new Derivation.Builder().cat(Rule.rootCat).start(-1).end(-1).rule(Rule.nullRule).children(new ArrayList<Derivation>());
		int i = 0;

		b.compatibility(Double.parseDouble(item.child(i++).value));
		b.prob(Double.parseDouble(item.child(i++).value));
		b.score(Double.parseDouble(item.child(i++).value));

		final LispTree valueTree = item.child(i++);
		if (!valueTree.isLeaf() || !"null".equals(valueTree.value))
			b.value(Values.fromLispTree(valueTree));

		b.formula(Formulas.fromLispTree(item.child(i++)));

		final FeatureVector fv = new FeatureVector();
		final LispTree features = item.child(i++);
		for (int j = 0; j < features.children.size(); j++)
			fv.addFromString(features.child(j).child(0).value, Double.parseDouble(features.child(j).child(1).value));

		b.localFeatureVector(fv);

		return b.createDerivation();
	}

	public static LispTree derivationToLispTree(final Derivation deriv)
	{
		final LispTree item = LispTree.proto.newList();

		item.addChild(deriv.compatibility + "");
		item.addChild(deriv.prob + "");
		item.addChild(deriv.score + "");
		if (deriv.value != null)
			item.addChild(deriv.value.toLispTree());
		else
			item.addChild("null");
		item.addChild(deriv.formula.toLispTree());

		final HashMap<String, Double> features = new HashMap<>();
		deriv.incrementAllFeatureVector(1, features);
		item.addChild(LispTree.proto.newList(features));

		return item;
	}

	/**
	 * Parse a LispTree with the format created by deriv.toLispTree(). Due to the complexity, rules and children are not parsed. Format: (derivation [(formula
	 * {formula})] [(value {value})] [(type {type})] [(canonicalUtterance {canonicalUtterance})])
	 * 
	 * @param item todo
	 * @return todo
	 */
	public static Derivation rawDerivationFromLispTree(final LispTree item)
	{
		final Derivation.Builder b = new Derivation.Builder().cat(Rule.rootCat).start(-1).end(-1).rule(Rule.nullRule).children(new ArrayList<Derivation>());
		for (int i = 1; i < item.children.size(); i++)
		{
			final LispTree arg = item.child(i);
			final String label = arg.child(0).value;
			if ("formula".equals(label))
				b.formula(Formulas.fromLispTree(arg.child(1)));
			else
				if ("value".equals(label))
					b.value(Values.fromLispTree(arg.child(1)));
				else
					if ("type".equals(label))
						b.type(SemType.fromLispTree(arg.child(1)));
					else
						if ("canonicalUtterance".equals(label))
							b.canonicalUtterance(arg.child(1).value);
						else
							throw new RuntimeException("Invalid example argument: " + arg);
		}
		return b.createDerivation();
	}

	public static LispTree rawDerivationToLispTree(final Derivation deriv)
	{
		return deriv.toLispTree();
	}

	public Map<String, Object> getTempState()
	{
		// Create the tempState if it doesn't exist.
		if (tempState == null)
			tempState = new HashMap<>();
		return tempState;
	}

	public void clearTempState()
	{
		tempState = null;
	}

	/**
	 * Clean up things to save memory
	 */
	public void clean()
	{
		predDerivations.clear();
		if (context.graph != null)
			context.graph.clean();
	}
}
