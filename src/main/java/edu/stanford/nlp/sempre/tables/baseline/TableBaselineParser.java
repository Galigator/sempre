package edu.stanford.nlp.sempre.tables.baseline;

import edu.stanford.nlp.sempre.Derivation;
import edu.stanford.nlp.sempre.ErrorValue;
import edu.stanford.nlp.sempre.Example;
import edu.stanford.nlp.sempre.Formula;
import edu.stanford.nlp.sempre.FuzzyMatchFn;
import edu.stanford.nlp.sempre.JoinFormula;
import edu.stanford.nlp.sempre.ListValue;
import edu.stanford.nlp.sempre.Params;
import edu.stanford.nlp.sempre.Parser;
import edu.stanford.nlp.sempre.ParserState;
import edu.stanford.nlp.sempre.Rule;
import edu.stanford.nlp.sempre.TypeInference;
import edu.stanford.nlp.sempre.tables.TableKnowledgeGraph;
import edu.stanford.nlp.sempre.tables.TableTypeSystem;
import fig.basic.LogInfo;
import java.util.Collections;
import java.util.HashMap;

/**
 * Baseline parser for table. Choose the answer from a table cell.
 *
 * @author ppasupat
 */
public class TableBaselineParser extends Parser
{

	public TableBaselineParser(final Spec spec)
	{
		super(spec);
	}

	@Override
	public ParserState newParserState(final Params params, final Example ex, final boolean computeExpectedCounts)
	{
		return new TableBaselineParserState(this, params, ex, computeExpectedCounts);
	}

}

/**
 * Actual logic for generating candidates.
 */
class TableBaselineParserState extends ParserState
{

	public TableBaselineParserState(final Parser parser, final Params params, final Example ex, final boolean computeExpectedCounts)
	{
		super(parser, params, ex, computeExpectedCounts);
	}

	@Override
	public void infer()
	{
		LogInfo.begin_track("TableBaselineParser.infer()");
		// Add all entities and possible normalizations to the list of candidates
		final TableKnowledgeGraph graph = (TableKnowledgeGraph) ex.context.graph;
		for (final Formula f : graph.getAllFormulas(FuzzyMatchFn.FuzzyMatchFnMode.ENTITY))
			buildAllDerivations(f);
		// Execute + Compute expected counts
		ensureExecuted();
		if (computeExpectedCounts)
		{
			expectedCounts = new HashMap<>();
			ParserState.computeExpectedCounts(predDerivations, expectedCounts);
		}
		LogInfo.end_track();
	}

	private void buildAllDerivations(final Formula f)
	{
		generateDerivation(f);
		// Try number and date normalizations as well
		generateDerivation(new JoinFormula(Formula.fromString("!" + TableTypeSystem.CELL_NUMBER_VALUE._id), f));
		generateDerivation(new JoinFormula(Formula.fromString("!" + TableTypeSystem.CELL_DATE_VALUE._id), f));
	}

	private void generateDerivation(final Formula f)
	{
		final Derivation deriv = new Derivation.Builder().cat(Rule.rootCat).start(-1).end(-1).formula(f).children(Collections.emptyList()).type(TypeInference.inferType(f)).createDerivation();
		deriv.ensureExecuted(parser.executor, ex.context);
		if (deriv.value instanceof ErrorValue)
			return;
		if (deriv.value instanceof ListValue && ((ListValue) deriv.value).values.isEmpty())
			return;
		if (!deriv.isFeaturizedAndScored())
			featurizeAndScoreDerivation(deriv);
		predDerivations.add(deriv);
	}

}
