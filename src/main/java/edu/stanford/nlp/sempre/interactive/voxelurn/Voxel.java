package edu.stanford.nlp.sempre.interactive.voxelurn;

import edu.stanford.nlp.sempre.Json;
import edu.stanford.nlp.sempre.interactive.Item;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.testng.collections.Lists;

//individual stacks
public class Voxel extends Item
{
	public Color color;
	int row, col, height;
	int age;

	public Voxel(final int row, final int col, final int height, final String color)
	{
		this(row, col, height);
		this.color = Color.fromString(color);
	}

	public Voxel()
	{
		row = -1;
		col = 0;
		height = 0;
		color = Color.fromString("None");
		names = new HashSet<>();
		age = 0;
	}

	// used as a key
	public Voxel(final int row, final int col, final int height)
	{
		this();
		this.row = row;
		this.col = col;
		this.height = height;
	}

	public Voxel move(final Direction dir)
	{
		switch (dir)
		{
			case Back:
				row += 1;
				break;
			case Front:
				row -= 1;
				break;
			case Left:
				col += 1;
				break;
			case Right:
				col -= 1;
				break;
			case Top:
				height += 1;
				break;
			case Bot:
				height -= 1;
				break;
			case None:
				break;
		}
		return this;
	}

	public Voxel copy(final Direction dir)
	{
		final Voxel c = clone();
		switch (dir)
		{
			case Back:
				c.row += 1;
				break;
			case Front:
				c.row -= 1;
				break;
			case Left:
				c.col += 1;
				break;
			case Right:
				c.col -= 1;
				break;
			case Top:
				c.height += 1;
				break;
			case Bot:
				c.height -= 1;
				break;
			case None:
				break;
		}
		return c;
	}

	@Override
	public Object get(final String property)
	{
		Object propval;
		if (property.equals("height"))
			propval = new Integer(height);
		else
			if (property.equals("row"))
				propval = new Integer(row);
			else
				if (property.equals("col"))
					propval = new Integer(col);
				else
					if (property.equals("age"))
						propval = new Integer(age);
					else
						if (property.equals("color"))
							propval = color.toString().toLowerCase();
						// else if (property.equals("name"))
						// propval = this.names;
						else
							throw new RuntimeException("getting property " + property + " is not supported.");
		return propval;
	}

	@Override
	public void update(final String property, Object value)
	{
		// updating with empty set does nothing, throw something?
		if (value instanceof Set && ((Set) value).size() == 0)
			return;
		if (value instanceof Set && ((Set) value).size() == 1)
			value = ((Set) value).iterator().next();

		if (property.equals("height") && value instanceof Integer)
			height = (Integer) value;
		else
			if (property.equals("row") && value instanceof Integer)
				row = (Integer) value;
			else
				if (property.equals("col") && value instanceof Integer)
					height = (Integer) value;
				else
					if (property.equals("color") && value instanceof String)
						color = Color.fromString(value.toString());
					else
						if (value instanceof Set)
							throw new RuntimeException(String.format("Updating %s to %s is not allowed," + " which has %d values, but a property can only have 1 value. ", property, value.toString(), ((Set) value).size()));
						else
							throw new RuntimeException(String.format("Updating property %s to %s is not allowed! (type %s is not expected for %s) ", property, value.toString(), value.getClass(), property));
	}

	@SuppressWarnings("unchecked")
	public static Voxel fromJSON(final String json)
	{
		final List<Object> props = Json.readValueHard(json, List.class);
		return fromJSONObject(props);
	}

	@SuppressWarnings("unchecked")
	public static Voxel fromJSONObject(final List<Object> props)
	{
		final Voxel retcube = new Voxel();
		retcube.row = (Integer) props.get(0);
		retcube.col = (Integer) props.get(1);
		retcube.height = (Integer) props.get(2);
		retcube.color = Color.fromString((String) props.get(3));

		retcube.names.addAll((List<String>) props.get(4));
		return retcube;
	}

	public Object toJSON()
	{
		final List<String> globalNames = names.stream().collect(Collectors.toList());
		final List<Object> cube = Lists.newArrayList(row, col, height, color.toString(), globalNames);
		return cube;
	}

	@Override
	public Voxel clone()
	{
		final Voxel c = new Voxel(row, col, height, color.toString());
		return c;
	}

	@Override
	public int hashCode()
	{
		final int prime = 19;
		int result = 1;
		result = prime * result + col;
		result = prime * result + height;
		result = prime * result + row;
		// result = prime * result + (this.color == (CubeColor.Anchor)? 1 : 0);

		return result;
	}

	@Override
	public boolean equals(final Object obj)
	{
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		final Voxel other = (Voxel) obj;

		if (col != other.col)
			return false;
		if (height != other.height)
			return false;
		if (row != other.row)
			return false;

		// only anchored colors need to be the same.
		// if (this.color == CubeColor.Anchor ^ other.color == CubeColor.Anchor &&
		// this.height == 0)
		// return false;
		return true;
	}

	@Override
	public boolean selected()
	{
		return names.contains("S");
	}

	@Override
	public void select(final boolean s)
	{
		if (s)
			names.add("S");
		else
			names.remove("S");
	}

	@Override
	public String toString()
	{
		return toJSON().toString();
	}
}
