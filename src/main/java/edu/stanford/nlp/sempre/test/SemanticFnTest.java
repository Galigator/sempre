package edu.stanford.nlp.sempre.test;

import static org.testng.AssertJUnit.assertEquals;

import edu.stanford.nlp.sempre.ConcatFn;
import edu.stanford.nlp.sempre.ConstantFn;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.DerivationStream;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FilterPosTagFn;
import edu.stanford.nlp.sempre.FilterSpanLengthFn;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.LanguageAnalyzer;
import edu.stanford.nlp.sempre.Rule;
import edu.stanford.nlp.sempre.SemanticFn;
import edu.stanford.nlp.sempre.SimpleAnalyzer;
import fig.basic.LispTree;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Test Formulas.
 * 
 * @author Percy Liang
 */
public class SemanticFnTest
{
	private static Formula F(final String s)
	{
		return Formula.fromString(s);
	}

	void check(final Formula target, final DerivationStream derivations)
	{
		if (!derivations.hasNext())
			throw new RuntimeException("Expected 1 derivation, got " + derivations);
		assertEquals(target, derivations.next().formula);
	}

	void check(final Formula target, final String utterance, final SemanticFn fn, final List<Derivation> children)
	{
		final Example ex = TestUtils.makeSimpleExample(utterance);
		check(target, fn.call(ex, new SemanticFn.CallInfo(null, 0, ex.numTokens(), Rule.nullRule, children)));
	}

	void check(final Formula target, final String utterance, final SemanticFn fn)
	{
		final List<Derivation> empty = Collections.emptyList();
		check(target, utterance, fn, empty);
	}

	void checkNumDerivations(final DerivationStream derivations, final int num)
	{
		assertEquals(num, derivations.estimatedSize());
	}

	@Test
	public void constantFn()
	{
		LanguageAnalyzer.setSingleton(new SimpleAnalyzer());
		check(F("(number 3)"), "whatever", new ConstantFn(F("(number 3)")));
	}

	Derivation D(final Formula f)
	{
		return new Derivation.Builder().formula(f).prob(1.0).createDerivation();
	}

	LispTree T(final String str)
	{
		return LispTree.proto.parseFromString(str);
	}

	// TODO(chaganty): Test bridge fn - requires freebase

	@Test
	public void concatFn()
	{
		LanguageAnalyzer.setSingleton(new SimpleAnalyzer());
		check(F("(string \"a b\")"), "a b", new ConcatFn(" "), Arrays.asList(D(F("(string a)")), D(F("(string b)"))));
	}

	// TODO(chaganty): Test context fn

	@Test
	public void filterPosTagFn()
	{
		LanguageAnalyzer.setSingleton(new SimpleAnalyzer());
		final FilterPosTagFn filter = new FilterPosTagFn();
		filter.init(T("(FilterPosTagFn token NNP)"));
		final Derivation child = new Derivation.Builder().createDerivation();
		final Example ex = TestUtils.makeSimpleExample("where is Obama");
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 1, Rule.nullRule, Collections.singletonList(child))).hasNext(), false);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 1, 2, Rule.nullRule, Collections.singletonList(child))).hasNext(), false);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 2, 3, Rule.nullRule, Collections.singletonList(child))).hasNext(), true);
	}

	@Test
	public void filterSpanLengthFn()
	{
		LanguageAnalyzer.setSingleton(new SimpleAnalyzer());
		FilterSpanLengthFn filter = new FilterSpanLengthFn();
		filter.init(T("(FilterSpanLengthFn 2)"));
		final Derivation child = new Derivation.Builder().createDerivation();
		final Example ex = TestUtils.makeSimpleExample("This is a sentence with some words");
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 1, Rule.nullRule, Collections.singletonList(child))).hasNext(), false);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 2, Rule.nullRule, Collections.singletonList(child))).hasNext(), true);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 2, Rule.nullRule, Collections.singletonList(child))).hasNext(), true);

		filter = new FilterSpanLengthFn();
		filter.init(T("(FilterSpanLengthFn 2 4)"));
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 1, Rule.nullRule, Collections.singletonList(child))).hasNext(), false);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 2, Rule.nullRule, Collections.singletonList(child))).hasNext(), true);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 3, Rule.nullRule, Collections.singletonList(child))).hasNext(), true);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 4, Rule.nullRule, Collections.singletonList(child))).hasNext(), true);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 5, Rule.nullRule, Collections.singletonList(child))).hasNext(), false);
	}

	// TODO(chaganty): Test fuzzy match fn
	// TODO(chaganty): Test identity fn
	// TODO(chaganty): Test join fn
	// TODO(chaganty): Test lexicon fn
	// TODO(chaganty): Test merge fn
	// TODO(chaganty): Test select fn
	// TODO(chaganty): Test simple lexicon fn

}
