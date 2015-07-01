package com.couchbase.blip;

/**
 * An abstract class representing a network connection that can send and receive BLIP messages.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Message}, {@link SimpleWebSocketConnection}
 */
public abstract class Connection
{
	protected ConnectionDelegate delegate;
	protected transient int      currentNumber = 0;
	
	public abstract void connect();
	public abstract void close();
	
	public final void setDelegate(ConnectionDelegate delegate)
	{
		this.delegate = delegate;
	}
	
	public final Message newRequest()
	{
		Message msg    = new Message();
		msg.isMine     = true;
		msg.isMutable  = true;
		msg.connection = this;
		msg.number     = this.currentNumber++;
		return msg;
	}
	
	public abstract Message sendRequest(Message request);
}
