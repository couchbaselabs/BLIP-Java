package com.couchbase.blip;

import static org.junit.Assert.*;

import org.java_websocket.WebSocket;
import org.java_websocket.WebSocketImpl;
import org.java_websocket.client.WebSocketClient;

import org.junit.Test;

@Deprecated
public class SimpleWebSocketTest
{
	@Test
	public void testCompatibility()
	{
		// Make sure the WebSocketClient class implements the WebSocket interface
		assertTrue(WebSocket.class.isAssignableFrom(WebSocketClient.class));
	}
	
	@Test
	public void test()
	{
		WebSocketImpl.DEBUG = true;
		
		SimpleWebSocketConnection client;
		SimpleWebSocketListener   server;
		
		try
		{
			Logger.log("Starting server");
			server = new SimpleWebSocketListener(8887);
			server.start();
			
			Thread.sleep(500);
			
			Logger.log("Starting client");
			client = new SimpleWebSocketConnection("ws://localhost:8887");
			client.connect();
			
			Thread.sleep(500);
			
			Logger.log("Sending message");
			Message m = client.newRequest();
			m.body = new byte[]{};
			m.setProperty("Test", "Test prop");
			m.send();
			
			Thread.sleep(500);
			
			Logger.log("Closing client");
			client.close();
			
			Logger.log("Closing server");
			server.stop();
		}
		catch (Throwable t) { t.printStackTrace(); }
	}
}
