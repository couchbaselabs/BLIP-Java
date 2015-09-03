package com.couchbase.blip;

import java.io.Closeable;

/**
 * An abstract class representing a network connection that can send and receive BLIP messages.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Message}, {@link WebSocketConnection}
 */
public abstract class Connection implements Closeable
{	
	// The serial number of the next message created
	private transient int currentNumber = 0;
	
	
	protected transient ConnectionListener listener;

	
	/**
	 * Opens this connection to the network
	 * @throws IllegalStateException if this connection has already been opened
	 */
	public abstract void connect() throws IllegalStateException;
	
	/**
	 * Closes this connection
	 */
	@Override
	public abstract void close();
	
	/**
	 * Returns the listener for this connection
	 * @return the listener
	 */
	public final ConnectionListener getListener()
	{
		return this.listener;
	}
	
	/**
	 * Sets the listener for this connection
	 * @param listener the listener
	 */
	public final void setListener(ConnectionListener listener)
	{
		this.listener = listener;
	}
	
	/**
	 * Creates a new request associated with this connection
	 * @return The new request
	 */
	public final Message newRequest()
	{
		return new Message(this, this.currentNumber++, Message.MSG, true);
	}
	
	/**
	 * Sends a request along this connection. Typically invoked through the request's send() method.
	 * @param request the request to send
	 * @return the response to this request
	 * @see {@link Message#send()}
	 */
	protected abstract Message sendMessage(Message request);
}
