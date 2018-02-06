package edu.stanford.nlp.sempre.interactive.voxelurn;

import com.google.common.collect.Sets;
import edu.stanford.nlp.sempre.ContextValue;
import edu.stanford.nlp.sempre.Json;
import edu.stanford.nlp.sempre.NaiveKnowledgeGraph;
import edu.stanford.nlp.sempre.StringValue;
import edu.stanford.nlp.sempre.interactive.Item;
import edu.stanford.nlp.sempre.interactive.World;
import fig.basic.Option;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

// the world of stacks
public class VoxelWorld extends World
{
	public static class Options
	{
		@Option(gloss = "maximum number of cubes to convert")
		public int maxBlocks = 1024 ^ 2;
	}

	public static Options opts = new Options();

	public final static String SELECT = "S";

	public static VoxelWorld fromContext(final ContextValue context)
	{
		if (context == null || context.graph == null)
			return fromJSON("[[3,3,1,\"gray\",[\"S\"]],[4,4,1,\"blue\",[]]]");
		final NaiveKnowledgeGraph graph = (NaiveKnowledgeGraph) context.graph;
		final String wallString = ((StringValue) graph._triples.get(0)._e1).value;
		return fromJSON(wallString);
	}

	public void base(final int x, final int y)
	{
		final Voxel basecube = new Voxel(x, y, 0, Color.Fake.toString());
		allItems = new HashSet<>(allItems);
		selected = new HashSet<>(selected);
		allItems.add(basecube);
		selected.add(basecube);
	}

	public Set<Item> origin()
	{
		for (final Item i : allItems)
		{
			final Voxel b = (Voxel) i;
			if (b.col == 0 && b.row == 0 & b.height == 0)
				return Sets.newHashSet(b);
		}
		final Voxel basecube = new Voxel(0, 0, 0, Color.Fake.toString());
		return Sets.newHashSet(basecube);
	}

	@SuppressWarnings("unchecked")
	public VoxelWorld(final Set<Item> blockset)
	{
		super();
		allItems = blockset;
		selected = blockset.stream().filter(b -> ((Voxel) b).names.contains(SELECT)).collect(Collectors.toSet());
		selected.forEach(i -> i.names.remove(SELECT));
	}

	// we only use names S to communicate with the client, internally its just the
	// select variable
	@Override
	public String toJSON()
	{
		// selected thats no longer in the world gets nothing
		// allitems.removeIf(c -> ((Block)c).color == CubeColor.Fake &&
		// !this.selected.contains(c));
		// allitems.stream().filter(c -> selected.contains(c)).forEach(i ->
		// i.names.add(SELECT));

		return Json.writeValueAsStringHard(allItems.stream().map(c ->
		{
			final Voxel b = ((Voxel) c).clone();
			if (selected.contains(b))
				b.names.add("S");
			return b.toJSON();
		}).collect(Collectors.toList()));
		// return this.worldlist.stream().map(c -> c.toJSON()).reduce("", (o, n) ->
		// o+","+n);
	}

	private static VoxelWorld fromJSON(final String wallString)
	{
		@SuppressWarnings("unchecked")
		final List<List<Object>> cubestr = Json.readValueHard(wallString, List.class);
		final Set<Item> cubes = cubestr.stream().map(c ->
		{
			return Voxel.fromJSONObject(c);
		}).collect(Collectors.toSet());
		// throw new RuntimeException(a.toString()+a.get(1).toString());
		final VoxelWorld world = new VoxelWorld(cubes);
		// world.previous.addAll(world.selected);
		// we can only use previous within a block;
		return world;
	}

	@Override
	public Set<Item> has(final String rel, final Set<Object> values)
	{
		// LogInfo.log(values);
		return allItems.stream().filter(i -> values.contains(i.get(rel))).collect(Collectors.toSet());
	}

	@Override
	public Set<Object> get(final String rel, final Set<Item> subset)
	{
		return subset.stream().map(i -> i.get(rel)).collect(Collectors.toSet());
	}

	@Override
	public void update(final String rel, final Object value, final Set<Item> selected)
	{
		selected.forEach(i -> i.update(rel, value));
		keyConsistency();
	}

	// if selected is no longer in all, make it fake colored, and add to all;
	// likewise, if some fake colored block is no longer selected, remove it
	@Override
	public void merge()
	{
		Sets.difference(selected, allItems).forEach(i -> ((Voxel) i).color = Color.Fake);
		allItems.removeIf(c -> ((Voxel) c).color.equals(Color.Fake) && !selected.contains(c));
		allItems.addAll(selected);
		if (allItems.size() > opts.maxBlocks)
			throw new RuntimeException(String.format("Number of blocks (%d) exceeds the upperlimit %d", allItems.size(), opts.maxBlocks));
	}

	// block world specific actions, overriding move
	public void move(final String dir, final Set<Item> selected)
	{
		// allitems.removeAll(selected);
		selected.forEach(b -> ((Voxel) b).move(Direction.fromString(dir)));
		keyConsistency();
		// allitems.addAll(selected); // this is not overriding
	}

	public void add(final String colorstr, final String dirstr, final Set<Item> selected)
	{
		final Direction dir = Direction.fromString(dirstr);
		final Color color = Color.fromString(colorstr);

		if (dir == Direction.None)
			selected.forEach(b -> ((Voxel) b).color = color);
		else
		{
			final Set<Item> extremeCubes = extremeCubes(dir, selected);
			allItems.addAll(extremeCubes.stream().map(c ->
			{
				final Voxel d = ((Voxel) c).copy(dir);
				d.color = color;
				return d;
			}).collect(Collectors.toList()));
		}
	}

	// get cubes at extreme positions
	public Set<Item> veryx(final String dirstr, final Set<Item> selected)
	{
		final Direction dir = Direction.fromString(dirstr);
		switch (dir)
		{
			case Back:
				return argmax(c -> c.row, selected);
			case Front:
				return argmax(c -> -c.row, selected);
			case Left:
				return argmax(c -> c.col, selected);
			case Right:
				return argmax(c -> -c.col, selected);
			case Top:
				return argmax(c -> c.height, selected);
			case Bot:
				return argmax(c -> -c.height, selected);
			default:
				throw new RuntimeException("invalid direction");
		}
	}

	// return retrieved from allitems, along with any potential selectors which
	// are empty.
	public Set<Item> adj(final String dirstr, final Set<Item> selected)
	{
		final Direction dir = Direction.fromString(dirstr);
		final Set<Item> selectors = selected.stream().map(c ->
		{
			final Voxel b = ((Voxel) c).copy(dir);
			b.color = Color.Fake;
			return b;
		}).collect(Collectors.toSet());

		allItems.addAll(selectors);

		final Set<Item> actual = allItems.stream().filter(c -> selectors.contains(c)).collect(Collectors.toSet());

		return actual;
	}

	public static Set<Item> argmax(final Function<Voxel, Integer> f, final Set<Item> items)
	{
		int maxvalue = Integer.MIN_VALUE;
		for (final Item i : items)
		{
			final int cvalue = f.apply((Voxel) i);
			if (cvalue > maxvalue)
				maxvalue = cvalue;
		}
		final int maxValue = maxvalue;
		return items.stream().filter(c -> f.apply((Voxel) c) >= maxValue).collect(Collectors.toSet());
	}

	@Override
	public void noop()
	{
		keyConsistency();
	}

	// get cubes at the outer locations
	private Set<Item> extremeCubes(final Direction dir, final Set<Item> selected)
	{
		final Set<Item> realCubes = realBlocks(allItems);
		return selected.stream().map(c ->
		{
			Voxel d = (Voxel) c;
			while (realCubes.contains(d.copy(dir)))
				d = d.copy(dir);
			return d;
		}).collect(Collectors.toSet());
	}

	// ensures key coherence on mutations
	private void refreshSet(final Set<Item> set)
	{
		final List<Item> s = new LinkedList<>(set);
		set.clear();
		set.addAll(s);
	}

	private void keyConsistency()
	{
		refreshSet(allItems);
		refreshSet(selected);
		refreshSet(previous);
	}

	private Set<Item> realBlocks(final Set<Item> all)
	{
		return all.stream().filter(b -> !((Voxel) b).color.equals(Color.Fake)).collect(Collectors.toSet());
	}
}
