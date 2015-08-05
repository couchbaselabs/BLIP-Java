package com.couchbase.blip;

/**
 * An abstract class representing a network connection that can send and receive BLIP messages.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Message}, {@link SimpleWebSocketConnection}
 */
public abstract class Connection
{
	protected transient ConnectionDelegate delegate;
	protected transient int                currentNumber = 0;
	
	/**
	 * Opens this connection to the network
	 * @throws IllegalStateException if this connection has already been opened
	 */
	public abstract void connect();
	
	/**
	 * Closes this connection
	 */
	public abstract void close();
	
	/**
	 * Returns the delegate for this connection
	 * @return the delegate
	 */
	public final ConnectionDelegate getDelegate()
	{
		return this.delegate;
	}
	
	/**
	 * Sets the delegate for this connection
	 * @param delegate the delegate
	 */
	public final void setDelegate(ConnectionDelegate delegate)
	{
		this.delegate = delegate;
	}
	
	/**
	 * Creates a new request associated with this connection
	 * @return The new request
	 */
	public final Message newRequest()
	{
		Message msg    = new Message();
		msg.isMine     = true;
		msg.isMutable  = true;
		msg.connection = this;
		msg.number     = this.currentNumber++;
		msg.flags      = Message.MSG;
		return msg;
	}
	
	/**
	 * Sends a request along this connection. Typically invoked through the request's send() method.
	 * @param request the request to send
	 * @return the response to this request
	 * @see {@link Message#send()}
	 */
	protected abstract Message sendRequest(Message request);
}
