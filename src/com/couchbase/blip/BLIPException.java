package com.couchbase.blip;

import java.util.Collections;
import java.util.Map;

/**
 * A BLIPException wraps a BLIP error message as a Java exception.
 * An error message can be converted to a BLIPException by calling its {@code toException()} method.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Message}, {@link Message#toException()}
 */
public final class BLIPException extends Exception
{
	// UID for Java serialization
	private static final long serialVersionUID = -9156435372115404611L;
	
	private final int                 code;
	private final String              domain;
	private final Map<String, String> properties;
	
	// Internal use only, called by Message.toException()
	BLIPException(Message msg) throws NumberFormatException
	{
		this.code       = msg.getErrorCode();
		this.domain     = msg.getProperty("Error-Domain");
		this.properties = Collections.unmodifiableMap(msg.properties);
	}
	
	/**
	 * Returns the error code of this exception
	 * @return the error code of this exception
	 */
	public int getErrorCode()
	{
		return this.code;
	}
	
	/**
	 * Returns the error domain of this exception
	 * @return the error domain of this exception
	 */
	public String getErrorDomain()
	{
		return this.domain;
	}
	
	/**
	 * Returns a map of this exception's properties
	 * @return a map of this exception's properties
	 */
	public Map<String, String> getProperties()
	{
		return this.properties;
	}
	
	/**
	 * Gets the value of the specified property
	 * @param property the property name
	 * @return the property value
	 */
	public String getProperty(String property)
	{
		return this.properties.get(property);
	}
	
	/**
	 * Returns true if the specified property exists
	 * @param property the property name
	 * @return true if the property exists
	 */
	public boolean hasProperty(String property)
	{
		return this.properties.containsKey(property);
	}
}
