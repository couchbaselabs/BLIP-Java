package com.couchbase.blip;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.IdentityHashMap;
import java.nio.ByteBuffer;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

/**
 * A listener which manages a group of BLIP web socket connections.
 * 
 * @author Jed Foss-Alfke
 * @see {@link WebSocketConnection}, {@link Message}
 */
public final class WebSocketListener
{	
	private WebSocketServer   socket;
	private InetSocketAddress address;
	private boolean           stopped;
	
	final IdentityHashMap<WebSocket, WebSocketConnection> connections = new IdentityHashMap<WebSocket, WebSocketConnection>();
	
	ConnectionDelegate delegate;
	
	
	/**
	 * Creates a new WebSocketListener that listens on the specified port
	 * @param port
	 * @throws UnknownHostException if the port is invalid
	 */
	public WebSocketListener(int port) throws UnknownHostException
	{
		this(new InetSocketAddress(port));
	}
	
	/**
	 * Creates a new WebSocketListener that listens on the specified address
	 * @param address
	 * @throws NullPointerException if the address is null
	 */
	public WebSocketListener(InetSocketAddress address)
	{
		if (address == null) throw new NullPointerException("Address is null");
		this.address = address;
	}
	
	
	/**
	 * Starts the listener
	 * @throws IllegalStateException if the listener is currently running or has been run before
	 */
	public void start()
	{
		if (this.stopped)        throw new IllegalStateException("Listener has been stopped");
		if (this.socket != null) throw new IllegalStateException("Listener is already running");
		
		WebSocketServer socket = new WebSocketServer(this.address)
		{
			@Override
			public void onOpen(WebSocket socket, ClientHandshake handshake)
			{				
				WebSocketConnection connection = new WebSocketConnection(socket);
				WebSocketListener.this.connections.put(socket, connection);
				
				ConnectionDelegate del = WebSocketListener.this.delegate;
				if (del != null)
					del.onOpen(connection);
			}
			
			@Override
			public void onClose(WebSocket socket, int code, String reason, boolean remote)
			{
				WebSocketConnection connection = WebSocketListener.this.connections.remove(socket);				
				ConnectionDelegate del;
				
				del = WebSocketListener.this.delegate;
				if (del != null)
					del.onClose(connection);
				
				del = connection.delegate;
				if (del != null) 
					del.onClose(connection);
				
				connection.shutdown();
			}

			// Ignore text-based messages
			@Override
			public void onMessage(WebSocket socket, String message) {}
			
			@Override
			public void onMessage(WebSocket socket, ByteBuffer message)
			{
				WebSocketConnection c = WebSocketListener.this.connections.get(socket);
				if (c != null)
				{
					c.onFrame(message);
				}
			}
			
			@Override
			public void onError(WebSocket socket, Exception error)
			{
				// TODO handle errors better than this
				if (error != null) error.printStackTrace();
			}
		};
		this.socket = socket;
		socket.start();
	}
	
	/**
	 * Stops the listener
	 * @throws InterruptedException 
	 * @throws IOException 
	 */
	public void stop() throws IOException, InterruptedException
	{
		this.socket.stop();
		this.stopped = true;
	}
	
	/**
	 * Returns the IP Socket Address this listener is listening on
	 * @return the listener's IP Socket Address
	 */
	public InetSocketAddress getAddress()
	{
		return this.address;
	}
	
	/**
	 * Returns the delegate for this listener
	 * @return the delegate
	 */
	public ConnectionDelegate getDelegate()
	{
		return this.delegate;
	}
	
	/**
	 * Sets the delegate for this listener
	 * @param delegate the delegate
	 */
	public void setDelegate(ConnectionDelegate delegate)
	{
		this.delegate = delegate;
	}
	
	
	@Override
	public String toString()
	{
		return "BLIP listener (" + this.address + ")";
	}
}
