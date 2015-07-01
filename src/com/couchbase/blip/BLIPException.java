package com.couchbase.blip;

import java.util.HashMap;
import java.util.Map;

/**
 * A BLIPException wraps a BLIP error message as a Java exception.
 * 
 * @author Jed Foss-Alfke
 */
public final class BLIPException extends Exception
{
	private final int                 code;
	private final String              domain;
	private final Map<String, String> properties;
	
	protected BLIPException(Message msg) throws NumberFormatException
	{
		this.code       = Integer.parseInt(msg.getProperty("Error-Code"));
		this.domain     = msg.getProperty("Error-Domain");
		this.properties = msg.properties;
	}
	
	public int getErrorCode()
	{
		return this.code;
	}
	
	public String getErrorDomain()
	{
		return this.domain;
	}
	
	public String getProperty(String property)
	{
		return this.properties.get(property);
	}
	
	public boolean hasProperty(String property)
	{
		return this.properties.containsKey(property);
	}
}
