package com.dooglemaps;

import java.lang.reflect.Constructor;

/**
 * Reflectively invokes a package-private {@code @Inject} constructor for a test.
 *
 * <p>Exists because eighteen test classes each carried an identical private copy of this
 * loop, and a handful more carried near-copies that grabbed {@code getDeclaredConstructors()[0]}
 * and hoped. One shared copy means the matching rules live in one place — and they are
 * slightly stronger here than in the copies: a constructor has to fit the argument types,
 * not just the argument count, so a class growing an overload cannot silently hand a test
 * the wrong constructor.
 *
 * <p>Constructors stay package-private in main code because Guice is the only intended
 * caller; {@code setAccessible(true)} is what lets tests in other packages use them anyway.
 */
public final class Construct
{
	private Construct()
	{
	}

	/**
	 * Finds a declared constructor whose parameters match {@code args} in both count and
	 * type, opens it, and invokes it. A null argument is a wildcard and fits any parameter.
	 *
	 * @throws IllegalStateException when no constructor matches, or the match cannot be invoked
	 */
	@SuppressWarnings("unchecked")
	public static <T> T construct(Class<T> type, Object... args)
	{
		for (Constructor<?> candidate : type.getDeclaredConstructors())
		{
			if (candidate.getParameterCount() != args.length || !accepts(candidate, args))
			{
				continue;
			}
			candidate.setAccessible(true);
			try
			{
				return (T) candidate.newInstance(args);
			}
			catch (ReflectiveOperationException e)
			{
				throw new IllegalStateException("could not invoke " + candidate, e);
			}
		}
		throw new IllegalStateException("no constructor of arity " + args.length
			+ " matching the given argument types on " + type);
	}

	private static boolean accepts(Constructor<?> candidate, Object[] args)
	{
		Class<?>[] parameters = candidate.getParameterTypes();
		for (int i = 0; i < args.length; i++)
		{
			if (args[i] != null && !fits(parameters[i], args[i]))
			{
				return false;
			}
		}
		return true;
	}

	/** Whether reflection would take {@code arg} for the parameter: instance, boxed, or widened. */
	private static boolean fits(Class<?> parameter, Object arg)
	{
		if (!parameter.isPrimitive())
		{
			return parameter.isInstance(arg);
		}
		if (parameter == boolean.class)
		{
			return arg instanceof Boolean;
		}
		if (parameter == char.class)
		{
			return arg instanceof Character;
		}
		return width(parameter) >= widthOf(arg);
	}

	/** Rank in the widening chain byte &lt; short &lt; int &lt; long &lt; float &lt; double. */
	private static int width(Class<?> primitive)
	{
		if (primitive == byte.class)
		{
			return 0;
		}
		if (primitive == short.class)
		{
			return 1;
		}
		if (primitive == long.class)
		{
			return 3;
		}
		if (primitive == float.class)
		{
			return 4;
		}
		if (primitive == double.class)
		{
			return 5;
		}
		return 2; // int — boolean and char never reach here
	}

	/** The narrowest primitive {@code arg} unboxes to; char sits with int, where it widens from. */
	private static int widthOf(Object arg)
	{
		if (arg instanceof Byte)
		{
			return 0;
		}
		if (arg instanceof Short)
		{
			return 1;
		}
		if (arg instanceof Character || arg instanceof Integer)
		{
			return 2;
		}
		if (arg instanceof Long)
		{
			return 3;
		}
		if (arg instanceof Float)
		{
			return 4;
		}
		if (arg instanceof Double)
		{
			return 5;
		}
		return Integer.MAX_VALUE; // not a number at all — never fits a numeric primitive
	}
}
