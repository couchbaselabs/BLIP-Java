package com.couchbase.blip;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;

import org.java_websocket.WebSocketImpl;
import org.junit.Test;

class WebSocketTest
{	
	@Test
	public void testSend() throws Throwable
	{	
		System.out.println("Testing WebSocket connection & listener");
		
		//WebSocketImpl.DEBUG = true;
		
		WebSocketListener l = new WebSocketListener(8887);
		l.setDelegate(new ConnectionDelegate()
		{
			@Override
			public void onOpen(Connection connection)
			{
				connection.setDelegate(new ConnectionDelegate()
				{
					@Override
					public void onOpen(Connection connection) {}

					@Override
					public void onClose(Connection connection) {}

					@Override
					public void onRequest(Connection connection, Message request)
					{
						System.out.println("got request");
					}

					@Override
					public void onResponse(Connection connection, Message response)
					{
						
					}

					@Override
					public void onError(Connection connection, Message error) {}
				});
			}

			@Override
			public void onClose(Connection connection) {}

			@Override
			public void onRequest(Connection connection, Message request)
			{
				
			}

			@Override
			public void onResponse(Connection connection, Message response) {}

			@Override
			public void onError(Connection connection, Message error) {}
		});
		l.start();
		
		Thread.sleep(200);
		
		WebSocketConnection c = new WebSocketConnection("ws://localhost:8887");
		c.connect();
		
		System.out.println("using connection obj #" + System.identityHashCode(c));
		
		Thread.sleep(200);
		
		Message m1 = c.newRequest();
		m1.setBody(ByteBuffer.wrap("Hello world, this is a test message!".getBytes()));
		m1.setProfile("Test profile");
		m1.setProperty("another property", "value");
		
		Message m2 = c.newRequest();
		m2.setBody(ByteBuffer.wrap("This is a second test message!".getBytes()));
		m2.setProfile("Another profile");
		m2.setProperty("test key", "test value");
		
		c.sendRequest(m1);
		c.sendRequest(m2);
		
		Thread.sleep(200);
		
		c.close();
		l.stop();
	}
}
