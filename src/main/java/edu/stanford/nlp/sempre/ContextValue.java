package edu.stanford.nlp.sempre;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import fig.basic.LispTree;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents the discourse context (time, place, history of exchanges). This is part of an Example and used by ContextFn.
 *
 * @author Percy Liang
 */
public class ContextValue extends Value
{
	// A single exchange between the user and the system
	// Note: we are not storing the entire derivation right now.
	public static class Exchange
	{
		public final String utterance;
		public final Formula formula;
		public final Value value;

		public Exchange(final String utterance_, final Formula formula_, final Value value_)
		{
			utterance = utterance_;
			formula = formula_;
			value = value_;
		}

		public Exchange(final LispTree tree)
		{
			utterance = tree.child(1).value;
			formula = Formulas.fromLispTree(tree.child(2));
			value = Values.fromLispTree(tree.child(3));
		}

		public LispTree toLispTree()
		{
			final LispTree tree = LispTree.proto.newList();
			tree.addChild("exchange");
			tree.addChild(utterance);
			tree.addChild(formula.toLispTree());
			tree.addChild(value.toLispTree());
			return tree;
		}

		@Override
		public String toString()
		{
			return toLispTree().toString();
		}
	}

	public final String user;
	public final DateValue date;
	public final List<Exchange> exchanges; // List of recent exchanges with the user
	public final KnowledgeGraph graph; // Mini-knowledge graph that captures the context

	public ContextValue withDate(final DateValue newDate)
	{
		return new ContextValue(user, newDate, exchanges, graph);
	}

	public ContextValue withNewExchange(final List<Exchange> newExchanges)
	{
		return new ContextValue(user, date, newExchanges, graph);
	}

	public ContextValue withGraph(final KnowledgeGraph newGraph)
	{
		return new ContextValue(user, date, exchanges, newGraph);
	}

	public ContextValue(final String user_, final DateValue date_, final List<Exchange> exchanges_, final KnowledgeGraph graph_)
	{
		user = user_;
		date = date_;
		exchanges = exchanges_;
		graph = graph_;
	}

	public ContextValue(final String user_, final DateValue date_, final List<Exchange> exchanges_)
	{
		this(user_, date_, exchanges_, null);
	}

	public ContextValue(final KnowledgeGraph graph_)
	{
		this(null, null, new ArrayList(), graph_);
	}

	// Example:
	//   (context (user pliang)
	//            (date 2014 4 20)
	//            (exchange "when was chopin born" (!fb:people.person.date_of_birth fb:en.frederic_chopin) (date 1810 2 22))
	//            (graph NaiveKnowledgeGraph ((string Obama) (string "born in") (string Hawaii)) ...))
	public ContextValue(final LispTree tree)
	{
		String user = null;
		DateValue date = null;
		KnowledgeGraph graph = null;
		exchanges = new ArrayList<>();
		for (int i = 1; i < tree.children.size(); i++)
		{
			final String key = tree.child(i).child(0).value;
			if (key.equals("user"))
				user = tree.child(i).child(1).value;
			else
				if (key.equals("date"))
					date = new DateValue(tree.child(i));
				else
					if (key.equals("graph"))
						graph = KnowledgeGraph.fromLispTree(tree.child(i));
					else
						if (key.equals("exchange"))
							exchanges.add(new Exchange(tree.child(i)));
						else
							throw new RuntimeException("Invalid: " + tree.child(i));
		}
		this.user = user;
		this.date = date;
		this.graph = graph;
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("context");
		if (user != null)
			tree.addChild(LispTree.proto.newList("user", user));
		if (date != null)
			tree.addChild(date.toLispTree());
		// When logging examples, logging the entire graph takes too much screen space.
		// I don't think that we ever deserialize a graph from a serialized context,
		// so this should be fine.
		if (graph != null)
			tree.addChild(graph.toShortLispTree());
		for (final Exchange e : exchanges)
			tree.addChild(LispTree.proto.newList("exchange", e.toLispTree()));
		return tree;
	}

	@Override
	public int hashCode()
	{
		int hash = 0x7ed55d16;
		hash = hash * 0xd3a2646c + user.hashCode();
		hash = hash * 0xd3a2646c + date.hashCode();
		hash = hash * 0xd3a2646c + exchanges.hashCode();
		hash = hash * 0xd3a2646c + graph.hashCode();
		return hash;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final ContextValue that = (ContextValue) o;
		if (!user.equals(that.user))
			return false;
		if (!date.equals(that.date))
			return false;
		if (!exchanges.equals(that.exchanges))
			return false;
		if (!graph.equals(that.graph))
			return false;
		return true;
	}

	@Override
	@JsonValue
	public String toString()
	{
		return toLispTree().toString();
	}

	@JsonCreator
	public static ContextValue fromString(final String str)
	{
		return new ContextValue(LispTree.proto.parseFromString(str));
	}
}
