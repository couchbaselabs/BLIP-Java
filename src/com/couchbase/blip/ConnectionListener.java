package com.couchbase.blip;

/**
 * A listener which handles incoming messages over a BLIP connection.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Connection}, {@link Message}
 */
public interface ConnectionListener
{
	/**
	 * Called when the connection receives a request message.
	 * @param connection the connection
	 * @param request the request message
	 */
	void onRequest(Connection connection, Message request);
	
	/**
	 * Called when the connection receives a response message
	 * @param connection the connection
	 * @param response the response message
	 */
	void onResponse(Connection connection, Message response);
	
	/**
	 * Called when the connection receives an error message
	 * @param connection the connection
	 * @param error the error message
	 */
	void onError(Connection connection, Message error);
}
