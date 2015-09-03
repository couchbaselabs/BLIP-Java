package com.couchbase.blip;

/**
 * A listener which handles incoming BLIP connections over a BLIP server.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Server}
 */
public interface ServerListener
{
	/**
	 * Called when a new connection connects to this server
	 * @param server this server
	 * @param connection the new connection
	 */
	public void connectionOpened(Server server, Connection connection);
	
	/**
	 * Called when a connection associated with this server closes
	 * @param server this server
	 * @param connection the closing connection
	 */
	public void connectionClosed(Server server, Connection connection);
}