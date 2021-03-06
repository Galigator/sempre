package edu.stanford.nlp.sempre;

import fig.basic.LispTree;

/**
 * Created by joberant on 1/23/15. Value for representing time
 */
public class TimeValue extends Value
{

	public final int hour;
	public final int minute;

	public TimeValue(final int hour, final int minute)
	{
		if (hour > 23 || hour < 0)
			throw new RuntimeException("Illegal hour: " + hour);
		if (minute > 59 || minute < 0)
			throw new RuntimeException("Illegal minute: " + minute);
		this.hour = hour;
		this.minute = minute;
	}

	public TimeValue(final LispTree tree)
	{
		hour = Integer.valueOf(tree.child(1).value);
		minute = Integer.valueOf(tree.child(2).value);
	}

	@Override
	public LispTree toLispTree()
	{
		final LispTree tree = LispTree.proto.newList();
		tree.addChild("time");
		tree.addChild(String.valueOf(hour));
		tree.addChild(String.valueOf(minute));
		return tree;
	}

	@Override
	public boolean equals(final Object o)
	{
		if (this == o)
			return true;
		if (o == null || getClass() != o.getClass())
			return false;
		final TimeValue timeValue = (TimeValue) o;
		if (hour != timeValue.hour)
			return false;
		if (minute != timeValue.minute)
			return false;
		return true;
	}

	@Override
	public int hashCode()
	{
		int result = hour;
		result = 31 * result + minute;
		return result;
	}
}
