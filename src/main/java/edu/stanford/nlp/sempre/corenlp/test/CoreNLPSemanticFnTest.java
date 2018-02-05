package edu.stanford.nlp.sempre.corenlp.test;

import static org.testng.AssertJUnit.assertEquals;

import edu.stanford.nlp.sempre.DateFn;
import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.DerivationStream;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.FilterNerSpanFn;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.LanguageAnalyzer;
import edu.stanford.nlp.sempre.NumberFn;
import edu.stanford.nlp.sempre.Rule;
import edu.stanford.nlp.sempre.SemanticFn;
import edu.stanford.nlp.sempre.corenlp.CoreNLPAnalyzer;
import edu.stanford.nlp.sempre.test.TestUtils;
import fig.basic.LispTree;
import java.util.Collections;
import java.util.List;
import org.testng.annotations.Test;

/**
 * Test SemanticFns that depend on CoreNLP (e.g., NumberFn on "one thousand")
 * 
 * @author Percy Liang
 */
public class CoreNLPSemanticFnTest
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

	Derivation D(final Formula f)
	{
		return new Derivation.Builder().formula(f).prob(1.0).createDerivation();
	}

	LispTree T(final String str)
	{
		return LispTree.proto.parseFromString(str);
	}

	// TODO(chaganty): Test bridge fn - requires freebase (?)
	// TODO(chaganty): Test context fn

	@Test
	public void dateFn()
	{
		LanguageAnalyzer.setSingleton(new CoreNLPAnalyzer());
		check(F("(date 2013 8 7)"), "August 7, 2013", new DateFn());
		check(F("(date 1982 -1 -1)"), "1982", new DateFn());
		check(F("(date -1 6 4)"), "june 4", new DateFn());
	}

	@Test
	public void filterNerTagFn()
	{
		LanguageAnalyzer.setSingleton(new CoreNLPAnalyzer());
		final FilterNerSpanFn filter = new FilterNerSpanFn();
		filter.init(T("(FilterNerSpanFn token PERSON)"));
		final Derivation child = new Derivation.Builder().createDerivation();
		final Example ex = TestUtils.makeSimpleExample("where is Obama");
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 0, 1, Rule.nullRule, Collections.singletonList(child))).hasNext(), false);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 1, 2, Rule.nullRule, Collections.singletonList(child))).hasNext(), false);
		assertEquals(filter.call(ex, new SemanticFn.CallInfo(null, 2, 3, Rule.nullRule, Collections.singletonList(child))).hasNext(), true);
	}

	// TODO(chaganty): Test fuzzy match fn
	// TODO(chaganty): Test identity fn
	// TODO(chaganty): Test join fn
	// TODO(chaganty): Test lexicon fn
	// TODO(chaganty): Test merge fn

	@Test
	public void numberFn()
	{
		LanguageAnalyzer.setSingleton(new CoreNLPAnalyzer());
		check(F("(number 35000)"), "thirty-five thousand", new NumberFn());
	}

	// TODO(chaganty): Test select fn
	// TODO(chaganty): Test simple lexicon fn

}
