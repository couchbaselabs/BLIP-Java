package com.couchbase.blip;

/**
 * A delegate which handles events such as incoming messages over a BLIP connection.
 * 
 * @author Jed Foss-Alfke
 * @see {@link Connection}, {@link Message}
 */
public interface ConnectionDelegate
{
	void onOpen(Connection connection);
	void onClose(Connection connection);
	void onRequest(Connection connection, Message request);
	void onResponse(Connection connection, Message response);
	void onError(Connection connection, Message error);
}
