package com.couchbase.blip;

import static org.junit.Assert.*;

import org.java_websocket.WebSocketImpl;
import org.junit.Test;

public class WebSocketTest
{
	@Test
	public void test() throws Throwable
	{	
		WebSocketImpl.DEBUG = true;
		
		WebSocketListener l = new WebSocketListener(8887);
		l.start();
		
		Thread.sleep(200);
		
		WebSocketConnection c = new WebSocketConnection("ws://localhost:8887");
		c.connect();
		
		Thread.sleep(200);
		
		Message m = c.newRequest();
		
		c.close();
		l.stop();
	}
}
