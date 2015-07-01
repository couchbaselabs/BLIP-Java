package com.couchbase.blip;

public final class Logger
{
	private Logger() {}
	
	public static void log(String message)
	{
		System.out.println(message);
	}
	
	public static void warn(String message)
	{
		System.err.println(message);
	}
	
	public static void warn(String message, Throwable error)
	{
		System.err.println(message);
		error.printStackTrace(System.err);
	}
	
	public static void fatal(String message)
	{
		System.err.println(message);
	}
	
	public static void fatal(String message, Throwable error)
	{
		System.err.println(message);
		error.printStackTrace(System.err);
	}
}
