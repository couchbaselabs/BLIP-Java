package com.couchbase.blip;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;

import org.java_websocket.WebSocketImpl;
import org.junit.Test;

public class WebSocketTest
{
	@Test
	public void testSendAndReceive() throws Throwable
	{
		System.out.println("Testing WebSocket connection & listener");
		
		//WebSocketImpl.DEBUG = true;
		
		Server l = new Server(8887);
		l.setListener(new ServerListener()
		{
			@Override
			public void connectionOpened(Server server, Connection connection)
			{
				connection.setListener(new ConnectionListener()
				{
					@Override
					public void onRequest(Connection connection, Message request)
					{
						System.out.println("[Server] Request received: " + request.toStringWithProperties());
						Message reply = request.newResponse();
						reply.setProperty("Hello", "World");
						System.out.println("[Server] Sending reply: " + reply.toStringWithProperties());
						reply.send();
					}

					@Override
					public void onResponse(Connection connection, Message response) {}

					@Override
					public void onError(Connection connection, Message error)
					{
						fail("Should never reach here");
					}
				});
			}
			
			@Override
			public void connectionClosed(Server server, Connection connection) {}
		});
		l.start();
		
		Thread.sleep(200);
		
		WebSocketConnection c = new WebSocketConnection("ws://localhost:8887");
		c.connect();
		
		//System.out.println("using connection obj #" + System.identityHashCode(c));
		
		Thread.sleep(200);
		
		Message m1 = c.newRequest();
		m1.setBody(ByteBuffer.wrap("Hello world, this is a test message!".getBytes()));
		m1.setProfile("Test profile");
		m1.setProperty("another property", "value");
		System.out.println("[Client] Sending request: " + m1.toStringWithProperties());
		
		Message m2 = c.newRequest();
		m2.setBody(ByteBuffer.wrap("This is a second test message!".getBytes()));
		m2.setProfile("Another profile");
		m2.setProperty("test key", "test value");
		System.out.println("[Client] Sending request: " + m2.toStringWithProperties());
		
		Message r1 = m1.send();
		Message r2 = m2.send();
		
		ReplyListener del = new ReplyListener()
		{
			@Override
			public void onCompleted(Message msg)
			{
				System.out.println("[Client] Response received: " + msg.toStringWithProperties());
			}
		};
		
		r1.setListener(del);
		r2.setListener(del);
		
		Thread.sleep(200);
		
		c.close();
		l.stop();
	}
}
